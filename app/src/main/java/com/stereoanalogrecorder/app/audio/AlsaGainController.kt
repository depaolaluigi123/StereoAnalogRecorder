package com.stereoanalogrecorder.app.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Real analog microphone-gain controller via the ALSA mixer (tinyalsa).
 *
 * This is the only strategy that can prevent clipping at the source: by lowering
 * the analog pre-ADC gain of the codec (e.g. Qualcomm WCD937x `ADC1 Volume` /
 * `ADC2 Volume`), loud sounds no longer saturate the ADC, so the samples that
 * reach `AudioRecord` are clean. This is fundamentally different from the digital
 * gain DSP in [GainMath], which can only scale an *already-captured* signal —
 * once the ADC has clipped to ±32767, no amount of digital attenuation recovers
 * the lost waveform.
 *
 * ## Requirements
 *  - **Root** (via Magisk `su`, or `adb root` on userdebug builds). The ALSA
 *    control device `/dev/snd/controlC0` is owned by `system:audio` and not
 *    readable/writable by a normal app UID, even with `MODIFY_AUDIO_SETTINGS`.
 *    The actual value writes are performed by a `tinymix` helper binary launched
 *    under `su`.
 *  - A `tinymix` binary for the correct CPU architecture (ARM64, ARM32, x86_64, x86).
 *    This binary is NOT bundled in the APK. It must be deployed to the device using
 *    the `install-android.sh` script, which cross-compiles it from the tinyalsa source
 *    in `dependencies/src/tinyalsa/` or uses a pre-built binary from `dependencies/tinymix/`.
 *    The script places tinymix into the app's private files directory at
 *    `<app-data>/files/tinymix` on first launch.
 *
 * ## Fallback
 *  If root is unavailable, the helper isn't runnable, or the expected ADC
 *  controls can't be found, [adjustGain] / [setAnalogGainDb] become no-ops and
 *  [isAnalogGainReady] returns false. The caller (MicCapture) then falls back to the
 *  existing digital-DSP gain path — so the app keeps working on stock devices,
 *  just without the analog-clip-prevention benefit.
 *
 * ## dB mapping (WCD937x and similar Qualcomm codecs)
 *  `ADCn Volume` is a 0..20 integer. Empirically the LSB is **1.5 dB**, so the
 *  range spans +0 dB (val 0) … +30 dB (val 20), with the stock default at
 *  val 12 ≈ +18 dB of analog gain. To *reduce* clipping the app drives the
 *  value *down* from the user's chosen point — i.e. a negative user dB request
 *  maps to a lower ADC value than the default, lowering the pre-amp gain. The
 *  exact LSB is auto-probed; see [probeStepDb].
 *
 *  This class is thread-safe — [setAnalogGainDb] may be called from the capture
 *  thread on every slider drag.
 */
class AlsaGainController private constructor(private val appContext: Context) {

    /** Outcome of a setup/discovery attempt. */
    enum class Status {
        /** Root + tinymix + ADC controls all present and writable. */
        READY,
        /** Not rooted (no working `su`). */
        NO_ROOT,
        /** Rooted but the bundled tinymix couldn't be installed/run. */
        HELPER_FAILED,
        /** Helper runs but the ADC volume controls aren't found on this codec. */
        NO_CONTROLS
    }

    /** Info about the analog control range, exposed so the UI can map the slider. */
    data class ControlRange(
        val min: Int,
        val max: Int,
        val defaultVal: Int,
        val stepDb: Float
    )

    /** Snapshot of one analog channel's discovered control. */
    private data class AdcControl(
        val name: String,
        /** ALSA numid — used because names can collide / change between ROMs. */
        val numid: Int,
        val min: Int,
        val max: Int,
        /** Current value at discovery time, parsed from the `contents` dump. */
        val currentValue: Int?
    )

    var status: Status = Status.NO_ROOT
        private set

    
    // Quick-reference for the two readiness predicates:
    //   isRootAvailable     --> su (root) OK
    //   isAnalogGainReady   --> su (root) + tinymix + ADC controls --> all OK

    /** True when [status] == [Status.READY] (analog gain is actually in effect). */
    val isAnalogGainReady: Boolean get() = status == Status.READY  // su (root) + tinymix + ADC controls --> all OK

    /** True when root is present (any status except NO_ROOT). */
    val isRootAvailable: Boolean get() = status != Status.NO_ROOT  // su (root) OK
    

    /**
     * True when the su binary was *found* on disk (the device is rooted), even if
     * the app hasn't been granted permission to use it yet. Used by the UI to
     * decide whether to prompt the user to grant root access.
     */
    var suBinaryDetected: Boolean = false
        private set

    /** True once [initialize] has been attempted — prevents re-probing across activity recreation. */
    private var initialized = false

    /** True once [initialize] has been attempted (any result, not just READY). */
    val isInitialized: Boolean get() = initialized

    /**
     * Returns the control range for mic 1, or null if discovery hasn't completed yet.
     * Use this to map the GAIN-mode slider to raw ALSA values.
     */
    fun mic1ControlRange(): ControlRange? {
        val ctl = mic1Ctl ?: return null
        return ControlRange(ctl.min, ctl.max, defaultVal, stepDb)
    }

    private val lock = ReentrantLock()

    /** Resolved controls by channel index (1-based to match the UI's Mic1/Mic2). */
    private var mic1Ctl: AdcControl? = null
    private var mic2Ctl: AdcControl? = null

    /** Path to the installed tinymix helper, or null if not installable. */
    private var helperPath: String? = null

    /** Nominal (manufacturer-default) ADC value, used when restoring on release. */
    private var defaultVal: Int = DEFAULT_ADC_VAL

    /** Per-step dB scale probed from the codec. LSB ≈ 1.5 dB on WCD937x. */
    private var stepDb: Float = DEFAULT_STEP_DB

    /**
     * Auto-detect a working `su` chain. Returns the su invocation prefix
     * ("su -c" or just "" if we're already uid 0) or null if none works.
     *
     * Two cheap checks first, then a real probe:
     *  1. If our UID is already 0 (e.g. launched via `adb root`) — no su needed.
     *  2. Check that /system/bin/su exists & is executable (fast file stat).
     *  3. Probe su for real: `su -c "id -u"` with a 10-second timeout.
     *     The probe uses a single exec call so the PID can be waited on.
     *     If Magisk auto-grant is enabled this should succeed silently;
     *     if the user has yet to tap "Allow", it times out → null.
     */
    private fun detectSuPrefix(): String? {
        // Case 1: we're already root (e.g. app launched via `adb root`).
        if (android.os.Process.myUid() == 0) return ""

        // Case 2: the su binary exists on disk (necessary condition).
        val suBinary = File("/system/bin/su")
        val suFound = if (suBinary.isFile && suBinary.canExecute()) {
            true
        } else {
            listOf("/sbin/su", "/su/bin/su")
                .map { File(it) }
                .any { it.isFile && it.canExecute() }
        }
        suBinaryDetected = suFound
        // Do NOT return null here if suFound is false: on Magisk 24+ the su
        // binary may be accessible via PATH even when it can't be stat'd at a
        // known path (SELinux denies canExecute() to a non-root UID). The probe
        // below will still succeed via PATH and, crucially, will trigger the
        // Magisk permission prompt so the app appears in Superuser settings.

        // Case 3: probe su for real — if Magisk auto-grant is ON this
        // should be fast and silent; otherwise (no grant yet) it either
        // blocks (waiting for user prompt) or fails, so we timeout.
        // IMPORTANT: waitFor() FIRST to avoid blocking on readText()
        // when su is waiting for user interaction (stdout never closes).
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
            val exited = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            val out = if (exited) p.inputStream.bufferedReader().readText().trim() else ""
            if (!exited) {
                p.destroyForcibly()
                Log.w(TAG, "su probe timed out — user needs to grant root in Magisk")
                null
            } else if (out == "0") "su -c" else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Locate the tinymix helper binary on this device.
     *
     * The binary is deployed by the `install-android.sh` script, which
     * cross-compiles tinyalsa for the connected device's architecture
     * (see `dependencies/src/tinyalsa/` in the project source) and places
     * the resulting binary into the app's private files directory at
     * `<app-data>/files/tinymix`. The script also runs `restorecon` on the
     * destination (so SELinux labels it as `app_data_file` and the
     * untrusted_app domain can exec it) and removes the staging copy at
     * `/data/local/tmp/tinymix` after a successful copy.
     *
     * **Single-source**: only `filesDir/tinymix` is consulted. There is no
     * fallback to the staging location — that path was removed because the
     * script now guarantees the private copy on rooted devices, and a
     * staging-only copy is unusable (SELinux blocks exec from
     * `/data/local/tmp`). If the private copy is missing, tinymix is
     * unavailable until `install-android.sh` is re-run on a rooted device.
     *
     * Returns the absolute path if found and executable, or null otherwise.
     */
    private fun installHelper(): String? {
        val appPrivate = File(appContext.filesDir, "tinymix")
        if (appPrivate.isFile && appPrivate.length() > 0L && appPrivate.canExecute()) {
            Log.d(TAG, "installHelper: found tinymix at ${appPrivate.absolutePath}")
            return appPrivate.absolutePath
        }
        Log.w(TAG, "installHelper: tinymix not found at ${appPrivate.absolutePath}. " +
            "Run install-android.sh on a rooted device to deploy it.")
        return null
    }

    /**
     * Run tinymix under su (or directly if already root). Returns stdout, or
     * null on failure. The command is properly single-quoted so that `su -c`
     * receives the full helper+args as one shell word.
     */
    private fun runTinymix(args: String): String? {
        val helper = helperPath ?: return null
        val suPrefix = suPrefix ?: return null
        return try {
            // Single-quote the whole command so that sh doesn't split
            // helper path + args into separate words before `su -c`
            // gets to parse them. The helper path itself has no
            // single quotes, so this is safe.
            val command = "'$helper' $args"
            val cmdline = if (suPrefix.isEmpty()) command else "$suPrefix $command"
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmdline))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            // Give su up to 8 seconds — it can be slow on some devices
            // while Magisk initializes or awaits user grant.
            val exited = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                p.destroyForcibly()
                Log.w(TAG, "tinymix '$args' timed out after 8s")
                return null
            }
            if (p.exitValue() != 0) {
                Log.d(TAG, "tinymix '$args' rc=${p.exitValue()} err=${err.take(200)}")
                return null
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "runTinymix('$args') failed", e)
            null
        }
    }

    private var suPrefix: String? = null

    /** Parse `tinymix contents` line: `2141\tINT\t1\tADC1 Volume\t012 (range 0->20)` */
    private fun parseContentsLine(line: String): AdcControl? {
        // Format from tinymix: "<numid>\t<type>\t<count>\t<name padded>\t<device>\t<values>"
        val parts = line.split("\t")
        if (parts.size < 5) return null
        if (parts[1] != "INT") return null
        val numid = parts[0].trim().toIntOrNull() ?: return null
        val name = parts[3].trim()
        // Value/range comes after device: "012 (range 0->20)" → extract min/max
        val rangeMatch = Regex("""range\s+(\-?\d+)->(\-?\d+)""").find(line) ?: return null
        val min = rangeMatch.groupValues[1].toInt()
        val max = rangeMatch.groupValues[2].toInt()
        if (max <= min) return null // degenerate — skip
        // Also extract the current value so discoverControls() doesn't need a
        // separate `tinymix get <numid>` subprocess call.
        val valueMatch = Regex("""(\-?\d+)\s*\(range""").find(line)
        val currentValue = valueMatch?.groupValues?.get(1)?.toIntOrNull()
        return AdcControl(name, numid, min, max, currentValue)
    }

    /**
     * Probe the per-step dB scale empirically. Cheap heuristic: Qualcomm codecs
     * (WCD937x family) use 1.5 dB/step on ADC volumes spanning 21 steps. We can't
     * read TLV metadata portably via tinymix, so fall back to a per-range guess:
     *   - 21 steps  → 1.5 dB/step (WCD937x ADC1/2/3)
     *   - 125 steps → ~0.25 dB/step (TX_DEC digital gain)
     */
    private fun guessStepDb(max: Int, min: Int): Float {
        val steps = max - min + 1
        return when {
            steps in 18..24 -> 1.5f   // ADC analog, WCD937x
            steps in 100..200 -> 0.25f // digital DEC
            steps in 30..50 -> 1.0f
            else -> 1.0f
        }
    }

    /** Discover the ADC gain controls for mic1 / mic2.
     *
     * @param contentsDump Pre-fetched output of `tinymix contents` from the
     *        smoke-test step in [initialize], to avoid a redundant call.
     */
    private fun discoverControls(contentsDump: String): Boolean {
        val dump = contentsDump
        // Candidate names, in priority order. WCD937x exposes "ADC1 Volume",
        // "ADC2 Volume". Older codecs (WCD9335) use "ADC1", with the same idea.
        val mic1Names = listOf("ADC1 Volume", "ADC1", "Mic ADC Volume")
        val mic2Names = listOf("ADC2 Volume", "ADC2")

        val allCtrls = dump.lineSequence()
            .mapNotNull(::parseContentsLine)
            .filter { it.name.contains("ADC", ignoreCase = true) && it.name.contains("Volume") }
            .toList()

        mic1Ctl = allCtrls.firstOrNull { it.name in mic1Names }
        // Prefer a distinct control for mic2; if the codec only has one ADC,
        // mic2 mirrors mic1 (single physical mic — same as the stereo/mono probe).
        mic2Ctl = allCtrls.firstOrNull { it.name in mic2Names } ?: mic1Ctl

        val ctl = mic1Ctl ?: return false
        // Use the current value parsed from the `contents` dump as the codec's
        // boot default. WCD937x boots ADC at 12; we cache it so [release] can
        // restore it. Falls back to a separate `tinymix get` if the dump didn't
        // contain the value.
        defaultVal = ctl.currentValue ?: readControl(ctl) ?: ((ctl.min + ctl.max) / 2)
        stepDb = guessStepDb(ctl.max, ctl.min)
        Log.i(TAG, "Discovered ADC controls: mic1=${mic1Ctl?.name}#${mic1Ctl?.numid} " +
                "mic2=${mic2Ctl?.name}#${mic2Ctl?.numid} range=[${ctl.min}, ${ctl.max}] " +
                "default=$defaultVal stepDb=$stepDb")
        return mic1Ctl != null
    }

    /** Read the current integer value of a control via `tinymix get <numid>`. */
    private fun readControl(ctl: AdcControl): Int? {
        val out = runTinymix("get ${ctl.numid}") ?: return null
        // tinymix prints e.g. "12 (range 0->20)" — first integer token is the value.
        return out.trim().split(Regex("\\s")).firstOrNull()?.toIntOrNull()
    }

    /** Write the integer value of a control via `tinymix set <numid> <value>`. */
    private fun writeControl(ctl: AdcControl, value: Int): Boolean {
        val v = value.coerceIn(ctl.min, ctl.max)
        return runTinymix("set ${ctl.numid} $v") != null
    }

    // ---- Public API -------------------------------------------------------

    /**
     * Force a re-probe of the su chain. Resets the initialization guard so
     * [initialize] will re-run: re-dispatches `su -c "id -u"` (which triggers
     * the Magisk permission prompt), re-installs the helper, and re-discovers
     * ADC controls.
     *
     * Intended for when the user explicitly taps a "request root" button
     * in the UI — e.g. after a previous probe timed out because they hadn't
     * granted permission yet.
     *
     * MUST be called from a background thread (spawns `su` and `tinymix`).
     */
    fun requestRootAccess(): Boolean {
        initialized = false
        return initialize()
    }

    /**
     * Call once at startup (foreground service onCreate / app start). Performs
     * the root check, installs the helper, and discovers ADC controls. Idempotent
     * and cheap on repeat calls if status is already settled.
     */
    fun initialize(): Boolean {
        synchronized(lock) {
            if (initialized) {
                Log.d(TAG, "already initialized (status=$status)")
                return status == Status.READY
            }
            initialized = true
            Log.d(TAG, "initialize() called")
            suPrefix = detectSuPrefix()
            Log.d(TAG, "detectSuPrefix() => ${suPrefix ?: "null"}")
            if (suPrefix == null) { status = Status.NO_ROOT; return false }
            helperPath = installHelper()
            Log.d(TAG, "installHelper() => ${helperPath ?: "null"}")
            if (helperPath == null) { status = Status.HELPER_FAILED; return false }
            // Smoke test: confirm the helper actually runs under our su chain.
            // The contents dump is reused by discoverControls() to avoid a
            // second (redundant) `tinymix contents` invocation.
            val contents = runTinymix("contents")
            Log.d(TAG, "smoke test (tinymix contents) => ${if (contents != null) "OK (${contents.take(60)})" else "FAILED"}")
            if (contents == null) {
                status = Status.HELPER_FAILED
                return false
            }
            if (!discoverControls(contents)) { status = Status.NO_CONTROLS; return false }
            status = Status.READY
            Log.i(TAG, "ALSA analog-gain controller READY.")
            return true
        }
    }

    /**
     * Total analog headroom (dB) available *downward* from the chip's default
     * boot value. A user asking to attenuate by more than this must be made up
     * by digital DSP gain (the remaining fraction) — see [MicCapture] where the
     * two are split.
     */
    fun maxAttenuationDb(): Int {
        val ctl = mic1Ctl ?: return 0
        return ((defaultVal - ctl.min) * stepDb).toInt()
    }

    /**
     * Set the analog gain for the given channel (1 or 2).
     *
     * @param channel 1 = Mic 1, 2 = Mic 2
     * @param db User-requested analog attenuation in dB. **Negative = quieter**
     *          (anti-clip), positive = boost toward the codec max. Anything
     *          outside the reachable analog range is clamped to the nearest
     *          endpoint. Returns the dB value actually applied (relative to the
     *          codec's boot default), or 0 if not available / write failed.
     */
    fun setAnalogGainDb(channel: Int, db: Float): Float {
        val ctl = when (channel) {
            1 -> mic1Ctl
            2 -> mic2Ctl
            else -> null
        } ?: return 0f
        if (!isAnalogGainReady) return 0f
        synchronized(lock) {
            // Convert "user dB relative to default boot gain" → raw ALSA value.
            // User db < 0 means attenuate below default; the codec value goes DOWN.
            val stepCount = (db / stepDb).toInt()
            val targetRaw = (defaultVal + stepCount).coerceIn(ctl.min, ctl.max)
            if (!writeControl(ctl, targetRaw)) return 0f
            // Report the dB actually achieved, relative to the boot default.
            return (targetRaw - defaultVal) * stepDb
        }
    }

    /** Restore the codec's boot-default analog gain (call on service teardown). */
    fun release() {
        if (!isAnalogGainReady) return
        synchronized(lock) {
            mic1Ctl?.let { writeControl(it, defaultVal) }
            if (mic2Ctl != null && mic2Ctl !== mic1Ctl) mic2Ctl?.let { writeControl(it, defaultVal) }
        }
    }

    companion object {
        private const val TAG = "AlsaGainController"

        /** WCD937x ADC boots at 12/20. Used as fallback if read fails. */
        private const val DEFAULT_ADC_VAL = 12

        /** WCD937x ADC LSB (≈1.5 dB per step over the 0..20 range). */
        private const val DEFAULT_STEP_DB = 1.5f

        @Volatile private var INSTANCE: AlsaGainController? = null

        fun get(context: Context): AlsaGainController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AlsaGainController(context.applicationContext).also { INSTANCE = it }
            }
    }
}
