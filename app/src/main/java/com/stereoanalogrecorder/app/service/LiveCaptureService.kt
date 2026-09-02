package com.stereoanalogrecorder.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.stereoanalogrecorder.app.StereoAnalogRecorderApp
import com.stereoanalogrecorder.app.R
import com.stereoanalogrecorder.app.audio.AlsaGainController
import com.stereoanalogrecorder.app.audio.CaptureSession
import com.stereoanalogrecorder.app.audio.MicCapture
import com.stereoanalogrecorder.app.settings.GainControlMode
import com.stereoanalogrecorder.app.state.MicStateStore

/**
 * Owns the single always-on [MicCapture] while the app is in the foreground or
 * running in the background with the classic-controls notification enabled.
 *
 * The live capture is what keeps the on-screen meters active even when no file is being
 * recorded — so the user can ride the gain before pressing record. Recording and
 * headphone monitoring attach to this same capture (via [CaptureSession]) rather than
 * opening their own `AudioRecord`, which avoids device-level mic contention.
 *
 * While idle (no recording in progress), this service posts the classic-controls
 * foreground notification with a "Rec" button and per-mic ±1 gain buttons. When a
 * recording starts, [RecordingService] takes over the foreground notification with a
 * "Stop" button; this service observes [MicStateStore] and removes its own notification
 * so only one is visible at a time. When the recording ends, this service re-shows its
 * idle classic notification.
 */
class LiveCaptureService : Service() {

    private val prefs get() = (application as StereoAnalogRecorderApp).preferences
    private val store: MicStateStore get() = (application as StereoAnalogRecorderApp).micStateStore
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private var capture: MicCapture? = null
    private var unsubscribeState: (() -> Unit)? = null

    /** True while our classic/minimal notification is the active foreground notification. */
    @Volatile private var notifVisible = false

    /** Cached gain values so the observer can detect changes without refreshing on every
     *  meter sample (which arrives ~15 fps). */
    @Volatile private var lastNotifGain1 = 0
    @Volatile private var lastNotifGain2 = 0
    /** Cached control type so a Level↔Gain switch refreshes the notification even
     *  when the gain values themselves didn't change (e.g. both reset to 0 on
     *  mode switch). Without this, switching modes while gains were already 0
     *  would leave the old dB/raw format in the notification. */
    @Volatile private var lastNotifMode: GainControlMode? = null
    /** Last sample rate the live capture was opened with. Used to detect when
     *  the user has selected a new rate and the AudioRecord needs to be
     *  re-opened at that rate. */
    @Volatile private var lastOpenedSampleRateHz: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
            ACTION_ADJUST -> handleAdjust(intent)
        }
        return START_NOT_STICKY
    }

    /**
     * Handle a per-mic ±1 adjustment from the idle classic notification.
     * Updates the shared [MicStateStore]; the observer below detects the gain change
     * and re-renders the notification automatically.
     *
     * The button is "no-op at the boundary": if the mic is already at the
     * minimum (and the user pressed −1) or at the maximum (and the user
     * pressed +1), the store is left untouched. The bounds depend on the
     * effective control mode, mirroring [MainActivity.bindStateInternal]:
     *  - "Gain (root only)" with ALSA READY → bounds are the codec's raw
     *    range (`ControlRange.min`..`ControlRange.max`, e.g. 0..20 on WCD937x).
     *    The button steps the raw value by 1, which translates to `stepDb`
     *    dB in the store (1.5 dB on WCD937x).
     *  - otherwise ("Level (volume)" or GAIN-but-ALSA-unavailable) → bounds
     *    are ±[MicStateStore.State.maxGainScale]. The button steps the dB
     *    value by 1.
     */
    private fun handleAdjust(intent: Intent) {
        val snapshot = store.snapshot()
        if (snapshot.isRecording || !notifVisible) return
        val delta = intent.getIntExtra(RecordingService.EXTRA_DELTA_DB, 0)
        val mic = intent.getIntExtra(RecordingService.EXTRA_MIC_INDEX, 0)
        val alsaController = AlsaGainController.get(this)
        val effectiveGainMode = snapshot.gainControlMode == GainControlMode.ANALOG_GAIN &&
            alsaController.isAnalogGainReady
        val currentDb = if (mic == 0) snapshot.gainMic1Db else snapshot.gainMic2Db
        val newDb = nextAdjustedDb(
            currentDb = currentDb,
            delta = delta,
            effectiveGainMode = effectiveGainMode,
            alsaController = alsaController,
            maxGainScale = snapshot.maxGainScale
        ) ?: return // already at the boundary in the requested direction
        if (mic == 0) store.setGainMic1(newDb) else store.setGainMic2(newDb)
        // The observer fires synchronously on the main thread (we're in onStartCommand)
        // and will refresh the notification via the gainChanged path.
    }

    /**
     * Compute the next dB value after a ±1 step from the notification button,
     * clamping at the mode-appropriate bound. Returns `null` when the
     * requested step would not move the value (already at min for −1 or
     * already at max for +1) — the caller treats null as "do nothing" so
     * the user gets no feedback on a no-op press.
     */
    private fun nextAdjustedDb(
        currentDb: Int,
        delta: Int,
        effectiveGainMode: Boolean,
        alsaController: AlsaGainController,
        maxGainScale: Int
    ): Int? {
        if (effectiveGainMode) {
            // The user-facing value in this mode is the raw ALSA integer
            // (e.g. 0..20), so the button steps it by 1 in raw units.
            val range = alsaController.mic1ControlRange() ?: return null
            val currentRaw = ((currentDb / range.stepDb) + range.defaultVal).toInt()
                .coerceIn(range.min, range.max)
            val newRaw = (currentRaw + delta).coerceIn(range.min, range.max)
            if (newRaw == currentRaw) return null
            return ((newRaw - range.defaultVal) * range.stepDb).toInt()
        } else {
            // LEVEL mode: dB directly, bounds = ±maxGainScale.
            val newDb = (currentDb + delta).coerceIn(-maxGainScale, maxGainScale)
            if (newDb == currentDb) return null
            return newDb
        }
    }

    // ---- Capture lifecycle ---------------------------------------------------------

    private fun startCapture() {
        if (capture?.isRunning() == true) return

        // Post the foreground notification IMMEDIATELY (within the ~5 s window).
        // While idle, this shows the classic controls with a "Rec" button.
        showIdleNotification()

        openCaptureAt(store.snapshot().sampleRateHz)

        // Observe recording state to coordinate notifications with RecordingService.
        // When a recording starts, RecordingService shows its own foreground
        // notification (with a Stop button); we remove ours so only one is visible.
        // When the recording ends, we re-show the idle classic notification.
        unsubscribeState = store.observe { state ->
            when {
                state.isRecording -> {
                    if (notifVisible) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        notifVisible = false
                    }
                }
                notifVisible -> {
                    // Already showing — refresh only if the gain or the control
                    // mode changed (e.g. user adjusted via sliders in the
                    // activity, or toggled Level↔Gain) to avoid refreshing on
                    // every meter sample (~15 fps). The mode check matters
                    // because switching Level↔Gain resets the gains to 0; if
                    // they were already 0 the gain diff alone wouldn't fire and
                    // the notification would stay in the old dB/raw format.
                    val gainChanged = state.gainMic1Db != lastNotifGain1 ||
                        state.gainMic2Db != lastNotifGain2
                    val modeChanged = state.gainControlMode != lastNotifMode
                    if (gainChanged || modeChanged) {
                        refreshIdleNotification(state)
                    }
                }
                capture?.isRunning() == true -> {
                    // Not visible (recording just stopped or first start) — re-show.
                    showIdleNotification()
                }
            }
            // If the user changed the sample rate and the live capture is
            // already running, re-open it at the new rate. AudioRecord is bound
            // to a sample rate at construction, so the only way to honor a
            // change is to tear it down and rebuild it. The recording service
            // is *not* affected — it always reads the latest rate from the
            // store when it starts a new take.
            if (!state.isRecording && state.sampleRateHz != lastOpenedSampleRateHz &&
                capture?.isRunning() == true
            ) {
                Log.i(TAG, "Sample rate changed (${lastOpenedSampleRateHz} -> ${state.sampleRateHz}) — restarting live capture")
                reopenCaptureAt(state.sampleRateHz)
            }
        }
    }

    /**
     * Open a new [MicCapture] at the given sample rate. Idempotent: if a
     * capture is already running it is stopped first. Sets [capture] and
     * publishes the new capture via [CaptureSession] so the recording service
     * can attach a sink to it.
     */
    private fun openCaptureAt(sampleRateHz: Int) {
        capture?.stop()
        capture = null
        CaptureSession.set(null)

        // Provider resolves the analog-controllers dynamically from the live store
        // mode. When the user toggles LEVEL ↔ GAIN while capture is running, the
        // capture loop re-resolves this on every iteration — no service restart
        // needed. Returns null when in LEVEL mode (digital-only path) or when
        // root/ALSA controls are unavailable.
        val analogGainProvider = {
            val s = store.snapshot()
            if (s.gainControlMode == GainControlMode.ANALOG_GAIN) AlsaGainController.get(this) else null
        }
        val cap = MicCapture(
            sampleRate = sampleRateHz,
            gainProvider = {
                val s = store.snapshot()
                intArrayOf(s.gainMic1Db, s.gainMic2Db)
            },
            meter = { peak1, peak2, run1, run2 ->
                store.updateMeters(peak1, peak2, run1, run2)
            },
            analogGainProvider = analogGainProvider
        )
        val started = cap.start()
        if (!started) {
            Log.e(TAG, "Live capture could not start at $sampleRateHz Hz (mic permission or device issue)")
            notifVisible = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        cap.resetPeaks() // start every live session with a clean "highest level" baseline
        capture = cap
        lastOpenedSampleRateHz = sampleRateHz
        CaptureSession.set(cap)
    }

    /**
     * Restart the live capture at a new sample rate without tearing down the
     * foreground service. Called from the state observer when the user picks
     * a different rate from the spinner.
     */
    private fun reopenCaptureAt(sampleRateHz: Int) {
        openCaptureAt(sampleRateHz)
    }

    private fun stopCapture() {
        unsubscribeState?.invoke()
        unsubscribeState = null
        CaptureSession.set(null)
        capture?.stop()
        capture = null
        notifVisible = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Notification --------------------------------------------------------------

    /** Show or re-show the idle foreground notification (classic or minimal). */
    private fun showIdleNotification() {
        val notif = if (prefs.showClassicNotification) {
            buildClassicNotification()
        } else {
            NotificationFactory.minimal(this, prefs)
        }
        startForegroundCompat(NOTIF_ID, notif)
        notifVisible = true
        val state = store.snapshot()
        lastNotifGain1 = state.gainMic1Db
        lastNotifGain2 = state.gainMic2Db
        lastNotifMode = state.gainControlMode
    }

    /** Re-render the idle notification because the gain values or control mode changed. */
    private fun refreshIdleNotification(state: MicStateStore.State) {
        val notif = if (prefs.showClassicNotification) {
            buildClassicNotification()
        } else {
            NotificationFactory.minimal(this, prefs)
        }
        startForegroundCompat(NOTIF_ID, notif)
        lastNotifGain1 = state.gainMic1Db
        lastNotifGain2 = state.gainMic2Db
        lastNotifMode = state.gainControlMode
    }

    private fun buildClassicNotification(): Notification {
        val state = store.snapshot()
        val contentPi = NotificationFactory.contentPendingIntent(this)

        val recPi = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, state.recordFormat.storageValue)
            putExtra(RecordingService.EXTRA_BITRATE_KBPS, state.aacBitrateKbps)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE_HZ, state.sampleRateHz)
        }.let { NotificationFactory.pendingServiceIntent(this, REQ_CODE_REC, it, true) }

        val stopPi = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }.let { NotificationFactory.pendingServiceIntent(this, REQ_CODE_STOP, it, false) }

        val piM1M = Intent(this, LiveCaptureService::class.java).apply {
            action = ACTION_ADJUST
            putExtra(RecordingService.EXTRA_DELTA_DB, -1)
            putExtra(RecordingService.EXTRA_MIC_INDEX, 0)
        }.let { NotificationFactory.pendingServiceIntent(this, REQ_CODE_ADJ_M1_M, it, false) }

        val piM1P = Intent(this, LiveCaptureService::class.java).apply {
            action = ACTION_ADJUST
            putExtra(RecordingService.EXTRA_DELTA_DB, 1)
            putExtra(RecordingService.EXTRA_MIC_INDEX, 0)
        }.let { NotificationFactory.pendingServiceIntent(this, REQ_CODE_ADJ_M1_P, it, false) }

        val piM2M = Intent(this, LiveCaptureService::class.java).apply {
            action = ACTION_ADJUST
            putExtra(RecordingService.EXTRA_DELTA_DB, -1)
            putExtra(RecordingService.EXTRA_MIC_INDEX, 1)
        }.let { NotificationFactory.pendingServiceIntent(this, REQ_CODE_ADJ_M2_M, it, false) }

        val piM2P = Intent(this, LiveCaptureService::class.java).apply {
            action = ACTION_ADJUST
            putExtra(RecordingService.EXTRA_DELTA_DB, 1)
            putExtra(RecordingService.EXTRA_MIC_INDEX, 1)
        }.let { NotificationFactory.pendingServiceIntent(this, REQ_CODE_ADJ_M2_P, it, false) }

        return NotificationFactory.buildClassicNotification(
            context = this,
            prefs = prefs,
            store = store,
            isRecording = false,
            contentPi = contentPi,
            stopPi = stopPi,
            recPi = recPi,
            adjustPiMic1Minus = piM1M,
            adjustPiMic1Plus = piM1P,
            adjustPiMic2Minus = piM2M,
            adjustPiMic2Plus = piM2P,
            // Pass the controller so the notification can show the raw ALSA
            // value (matching the slider) when the user has selected the
            // "Gain (root only)" control type. Null is safe — the factory
            // falls back to the dB label.
            alsaController = AlsaGainController.get(this)
        )
    }

    private fun startForegroundCompat(id: Int, notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(id, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unsubscribeState?.invoke()
        unsubscribeState = null
        CaptureSession.set(null)
        capture?.stop()
        capture = null
        notifVisible = false
    }

    companion object {
        private const val TAG = "LiveCaptureService"
        private const val NOTIF_ID = 2003

        const val ACTION_START = "com.stereoanalogrecorder.app.action.LIVE_START"
        const val ACTION_STOP = "com.stereoanalogrecorder.app.action.LIVE_STOP"
        const val ACTION_ADJUST = "com.stereoanalogrecorder.app.action.LIVE_ADJUST"

        // Unique request codes for idle-notification PendingIntents.
        private const val REQ_CODE_REC = 100
        private const val REQ_CODE_STOP = 101
        private const val REQ_CODE_ADJ_M1_M = 110
        private const val REQ_CODE_ADJ_M1_P = 111
        private const val REQ_CODE_ADJ_M2_M = 112
        private const val REQ_CODE_ADJ_M2_P = 113

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveCaptureService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
