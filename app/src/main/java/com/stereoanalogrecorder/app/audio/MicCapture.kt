package com.stereoanalogrecorder.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stereo AudioRecord capture with an empirical fake-stereo probe + mono fallback,
 * per-channel digital gain DSP (applied in-place to the 16-bit PCM buffer),
 * and per-buffer peak + running-max metering.
 *
 * The capture owns the single live `AudioRecord`; multiple optional consumers tap it:
 *  - [Sink]  — file writer; attach/detach dynamically with [setSink].
 *  - [MonitorTap] — headphone live-listen; attach/detach with [setMonitorTap].
 *  - [Meter] — always active; pushes per-buffer levels to the store so meters stay
 *    live even when no file is being written. This is what lets the user ride the gain
 *    before pressing record.
 *
 * Recommended lifecycle: one long-lived [MicCapture] held by an always-on foreground
 * service while the app is in the foreground.
 */
class MicCapture(
    private val sampleRate: Int = 44100,
    private val gainProvider: GainProvider,
    private val meter: Meter,
    sink: Sink? = null,
    /**
     * Optional provider for the analog gain controller backed by the ALSA mixer
     * (tinyalsa under root). Returning a non-null, available controller means the
     * user's requested dB is split into an analog portion (applied to the codec
     * pre-ADC, preventing clipping at the source) and a residual digital-DSP portion
     * (applied to the PCM, for fine adjustment beyond the analog range). Returning
     * null (or an unavailable controller) means the full user dB is applied
     * digitally exactly as before — preserving behavior on stock devices.
     *
     * This is a [() -> AlsaGainController?] provider rather than a fixed value so
     * that the mode can be toggled at runtime (LEVEL ↔ GAIN) without restarting the
     * capture service: the loop re-resolves the controller on every iteration and
     * picks up the current store mode immediately.
     */
    private val analogGainProvider: () -> AlsaGainController?
) {

    fun interface GainProvider {
        /** Returns [gainMic1Db, gainMic2Db] at call time (live from the store). */
        fun gains(): IntArray
    }

    /** Receives processed interleaved 16-bit PCM and a finalize callback when recording stops. */
    interface Sink {
        fun onChunk(shortBuf: ShortArray, frames: Int, channels: Int)
        fun finalize()
    }

    /**
     * Optional live-listen tap: receives each processed chunk while the capture loop is
     * running. Independent of [Sink] — set it to null to disable. The tap is invoked
     * synchronously inside the capture thread, so it must not block.
     */
    fun interface MonitorTap {
        /** [shortBuf] is interleaved L/R (16-bit PCM, gain already applied). */
        fun onChunk(shortBuf: ShortArray, frames: Int, channels: Int)
    }

    fun interface Meter {
        /** peak1/peak2 in dBFS; running peak already merged inside the impl via [updateRunning]. */
        fun onLevels(peakDb1: Float, peakDb2: Float, runningDb1: Float, runningDb2: Float)
    }

    enum class ChannelMode { STEREO, MONO_DUPLICATED }

    private var audioRecord: AudioRecord? = null
    @Volatile var running: Boolean = false
        private set
    @Volatile private var monitorTap: MonitorTap? = null
    @Volatile private var sink: Sink? = sink
    @Volatile private var runningPeak1 = 0
    @Volatile private var runningPeak2 = 0
    private var thread: Thread? = null

    /**
     * Last analog dB actually applied to each channel, so we only write to the ALSA
     * mixer when the requested value changes (slider drags are continuous; ALSA writes
     * are forked `su -c` calls and would thrash if emitted per buffer).
     */
    @Volatile private var lastAnalogDb1: Float = Float.NaN
    @Volatile private var lastAnalogDb2: Float = Float.NaN

    var channelMode: ChannelMode = ChannelMode.STEREO
        private set

    /** Attach / replace the file sink. Pass null to detach (stops writing, finalizes prior). */
    fun setSink(newSink: Sink?) {
        val old = sink
        sink = newSink
        // Finalize the outgoing sink outside the capture thread to flush audio headers etc.
        if (old != null && old !== newSink) {
            try { old.finalize() } catch (_: Throwable) {}
        }
    }

    /** Attach / replace the monitor tap. Pass null to detach. */
    fun setMonitorTap(tap: MonitorTap?) { this.monitorTap = tap }

    /**
     * Reset the running-max (peak-hold) accumulator. Call this when a new recording
     * starts (and when the live capture starts) so the "highest level measured" value
     * shown below each meter reflects THIS session/take, not the whole app lifetime
     * (the live capture is shared and long-lived, otherwise the running max would
     * never come down).
     */
    fun resetPeaks() {
        runningPeak1 = 0
        runningPeak2 = 0
    }

    /** Reset the running-max for ONE channel only (1 or 2). DAW-style per-channel tap. */
    fun resetPeak(channel: Int) {
        if (channel == 1) runningPeak1 = 0 else runningPeak2 = 0
    }

    /**
     * Split a user-requested total gain (dB) into (analogDb, dspDb) so that the analog
     * portion stays within the codec's reachable range [maxAnalogAtten, maxAnalogBoost]
     * and the digital DSP absorbs the remainder. Both outputs are in dB.
     *
     * Example (WCD937x, default ADC=12, step=1.5 dB, so maxAnalogAtten ≈ -18 dB):
     *   userDb = -20 dB → analogDb = -18 dB, dspDb = -2 dB
     *   userDb = -10 dB → analogDb = -10 dB, dspDb =   0 dB
     *   userDb = +6 dB → analogDb =   0 dB, dspDb = +6 dB  (analog only attenuates here)
     */
    private fun splitGain(userDb: Float, maxAnalogAtten: Float, maxAnalogBoost: Float): Pair<Float, Float> {
        // If the analog path is unavailable (maxAtten==0 && maxBoost==0), all-digital.
        if (maxAnalogAtten == 0f && maxAnalogBoost == 0f) return 0f to userDb
        val analogDb = userDb.coerceIn(maxAnalogAtten, maxAnalogBoost)
        val dspDb = userDb - analogDb
        return analogDb to dspDb
    }

    /** Whether the capture loop is currently running. */
    fun isRunning(): Boolean = running

    /** Returns false if recording cannot start (no mic / bad config). */
    fun start(): Boolean {
        val stereoCfg = AudioFormat.CHANNEL_IN_STEREO
        val mode = discoverAndInit(stereoCfg)
        if (mode == null) return false
        channelMode = mode

        // ALSA analog-gain initialization already runs asynchronously in
        // MainActivity.onCreate() — do NOT call initialize() here; doing
        // so synchronously on the service's main thread blocks the UI and
        // triggers "App Not Responding" while root detection is in progress.
        // The capture loop checks isAnalogGainReady on every iteration, settling
        // as soon as the background init finishes.

        running = true
        thread = Thread({ captureLoop() }, "mic-capture").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        val rec = audioRecord
        audioRecord = null
        try {
            rec?.stop()
        } catch (_: Throwable) {}
        try {
            rec?.release()
        } catch (_: Throwable) {}
        val s = sink
        sink = null
        try { s?.finalize() } catch (_: Throwable) {}
        // Restore the codec's boot-default analog gain so we don't leave the mic
        // attenuated after the app closes.
        analogGainProvider()?.release()
    }

    private fun discoverAndInit(stereoCfg: Int): ChannelMode? {
        val monoCfg = AudioFormat.CHANNEL_IN_MONO
        // Try stereo first; probe to distinguish real vs fake (downmixed) stereo.
        if (tryInit(stereoCfg)) {
            if (probeStereoIsReal()) return ChannelMode.STEREO
            // Fake stereo — release and fall back to real mono.
            releaseCurrent()
        }
        return if (tryInit(monoCfg)) ChannelMode.MONO_DUPLICATED else null
    }

    private fun tryInit(channels: Int): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize($channels) = $minBuf")
            return false
        }
        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channels,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                false
            } else {
                audioRecord = rec
                rec.startRecording()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed for channels=$channels", e)
            false
        }
    }

    private fun releaseCurrent() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    private fun probeStereoIsReal(): Boolean {
        val rec = audioRecord ?: return false
        val probeFrames = 2048
        val buf = ShortArray(probeFrames * 2)
        var total = 0
        var diff = 0L
        var sum = 0L
        readForProbe(rec, buf)
        // Use first read to warm up; read again for the measure.
        val n = readForProbe(rec, buf)
        for (i in 0 until n step 2) {
            val l = abs(buf[i].toInt())
            val r = abs(buf[i + 1].toInt())
            diff += abs(l - r)
            sum += max(l, r)
            total++
        }
        if (total == 0 || sum == 0L) return true // can't tell; assume stereo exists
        val ratio = diff.toDouble() / sum.toDouble()
        Log.d(TAG, "stereo probe ratio=$ratio (frames=$total)")
        return ratio > 1e-3
    }

    private fun readForProbe(rec: AudioRecord, buf: ShortArray): Int {
        return try {
            rec.read(buf, 0, buf.size)
        } catch (_: Throwable) { 0 }
    }

    private fun captureLoop() {
        val rec = audioRecord ?: return
        val channels = when (channelMode) {
            ChannelMode.STEREO -> 2
            ChannelMode.MONO_DUPLICATED -> 1
        }
        val chunkFrames = 1024
        val chunkShorts = chunkFrames * channels
        val buf = ShortArray(chunkShorts)
        // For mono-duplicated capture we expand mono→stereo into a separate buffer so the
        // mono source samples are not overwritten before they are read.
        val stereoOut = ShortArray(chunkFrames * 2)

        while (running) {
            val read = rec.read(buf, 0, chunkShorts)
            if (read <= 0) continue
            val sampleCount = read // shorts read
            val frames = sampleCount / channels

            // Read live gains (user-requested total dB per channel).
            val gains = gainProvider.gains()
            val userDb1 = gains[0].toFloat()
            val userDb2 = gains[1].toFloat()

            // Split each channel's requested gain into an analog portion (applied to the
            // codec pre-ADC via ALSA, which actually prevents clipping at the source) and
            // a residual digital portion (applied to the PCM in this loop). The analog
            // path only handles what its range can reach; the rest is made up digitally
            // on samples that are already clean (since the analog stage attenuated first).
            // Split each channel's requested gain into an analog portion (applied to the
            // codec pre-ADC via ALSA, which actually prevents clipping at the source) and
            // a residual digital portion (applied to the PCM in this loop). The analog
            // path only handles what its range can reach; the rest is made up digitally
            // on samples that are already clean (since the analog stage attenuated first).
            // Resolve the analog gain controller dynamically on every iteration.
            // This lets the mode be switched at runtime (LEVEL ↔ GAIN) without
            // restarting the LiveCaptureService — the loop picks up the new mode
            // immediately and engages/disengages the analog path accordingly.
            val ag = analogGainProvider()
            val analogAvailable = ag?.isAnalogGainReady == true
            val maxAnalogAtten = if (analogAvailable) -ag!!.maxAttenuationDb().toFloat() else 0f
            val maxAnalogBoost = 0f // our model only attenuates analog relative to boot default
            val (analogDb1, dspDb1) = splitGain(userDb1, maxAnalogAtten, maxAnalogBoost)
            val (analogDb2, dspDb2) = splitGain(userDb2, maxAnalogAtten, maxAnalogBoost)

            // Push analog gains to the codec — only when the user's requested
            // dB for that channel actually changed (slider drag). The write is
            // fire-and-forget: if it fails (e.g. su prompt not yet accepted), the
            // DSP absorbs the remainder gracefully and no clipping occurs.
            if (analogAvailable) {
                // ag is non-null here (analogAvailable => ag?.isAnalogGainReady == true => ag != null)
                val a = ag!! // guarded by `analogAvailable` check above
                if (analogDb1 != lastAnalogDb1) {
                    a.setAnalogGainDb(1, analogDb1)
                    lastAnalogDb1 = analogDb1
                }
                if (analogDb2 != lastAnalogDb2) {
                    a.setAnalogGainDb(2, analogDb2)
                    lastAnalogDb2 = analogDb2
                }
            }

            val lin1 = GainMath.dbToLinear(dspDb1)
            val lin2 = GainMath.dbToLinear(dspDb2)

            var peak1 = 0
            var peak2 = 0

            if (channelMode == ChannelMode.STEREO) {
                for (i in 0 until sampleCount step 2) {
                    val l = GainMath.applyGain(buf[i].toInt(), lin1)
                    val r = GainMath.applyGain(buf[i + 1].toInt(), lin2)
                    buf[i] = l.toShort()
                    buf[i + 1] = r.toShort()
                    if (abs(l) > peak1) peak1 = abs(l)
                    if (abs(r) > peak2) peak2 = abs(r)
                }
                sink?.onChunk(buf, frames, 2)
                monitorTap?.onChunk(buf, frames, 2)
            } else {
                // Mono → duplicate into two independent-gain channels in stereoOut.
                for (i in 0 until frames) {
                    val mono = buf[i].toInt()
                    val l = GainMath.applyGain(mono, lin1)
                    val r = GainMath.applyGain(mono, lin2)
                    stereoOut[i * 2] = l.toShort()
                    stereoOut[i * 2 + 1] = r.toShort()
                    if (abs(l) > peak1) peak1 = abs(l)
                    if (abs(r) > peak2) peak2 = abs(r)
                }
                sink?.onChunk(stereoOut, frames, 2)
                monitorTap?.onChunk(stereoOut, frames, 2)
            }

            if (peak1 > runningPeak1) runningPeak1 = peak1
            if (peak2 > runningPeak2) runningPeak2 = peak2

            val peakDb1 = GainMath.amplitudeToDb(peak1.toFloat())
            val peakDb2 = GainMath.amplitudeToDb(peak2.toFloat())
            val runDb1 = GainMath.amplitudeToDb(runningPeak1.toFloat())
            val runDb2 = GainMath.amplitudeToDb(runningPeak2.toFloat())
            meter.onLevels(peakDb1, peakDb2, runDb1, runDb2)
        }
    }

    companion object {
        private const val TAG = "MicCapture"
    }
}
