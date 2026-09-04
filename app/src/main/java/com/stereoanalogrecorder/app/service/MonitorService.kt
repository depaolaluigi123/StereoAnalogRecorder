package com.stereoanalogrecorder.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stereoanalogrecorder.app.StereoAnalogRecorderApp
import com.stereoanalogrecorder.app.R
import com.stereoanalogrecorder.app.audio.CaptureSession
import com.stereoanalogrecorder.app.audio.MicCapture
import com.stereoanalogrecorder.app.settings.LocaleManager
import com.stereoanalogrecorder.app.state.MicStateStore
import com.stereoanalogrecorder.app.ui.MainActivity
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Headphone live-listen (monitor) service.
 *
 * Attaches a playback [AudioTrack] tap to the shared live capture (owned by
 * [LiveCaptureService]) so the user hears the mics through their headphones. Works
 * whether or not a recording is running — both tap the same underlying `AudioRecord`.
 * If the live capture is not yet running, we start it on demand.
 */
class MonitorService : Service() {

    private val prefs get() = (application as StereoAnalogRecorderApp).preferences
    private val store: MicStateStore get() = (application as StereoAnalogRecorderApp).micStateStore
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private var audioTrack: AudioTrack? = null
    private var attachedTo: MicCapture? = null
    private val sampleRate = 44100

    /** Observer subscription for live updates to [MicStateStore.State.monitorVolume]. */
    private var unsubscribeState: (() -> Unit)? = null

    /**
     * Snapshot of the linear gain to apply via [AudioTrack.setVolume]. Everything
     * ≤ `AudioTrack.getMaxVolume()` (typically 1.0) is delivered to setVolume;
     * any remainder is delivered via [bufferGain] on a per-sample basis. Tracked
     * separately from the store so the capture thread doesn't have to read the
     * store on every chunk.
     */
    @Volatile private var trackVolume: Float = 1.0f

    /**
     * Per-sample buffer gain applied inside the monitor tap. 1.0 means "no
     * buffer multiplication needed" (the chosen live-listen volume fits
     * entirely inside what [AudioTrack.setVolume] can express); values > 1.0
     * are the leftover boost that setVolume can't reach (because the
     * platform's [AudioTrack.getMaxVolume] is typically capped at 1.0 and
     * [android.media.AudioTrack.setVolume] throws on values above it).
     */
    @Volatile private var bufferGain: Float = 1.0f

    /** Combined linear gain last applied to the AudioTrack. Used to short-circuit
     *  the setVolume call when nothing relevant changed. */
    @Volatile private var lastAppliedGain: Float = -1f

    /** The setVolume portion last applied — tracked separately so a max-dB
     *  change at volume == 0 % (which only affects the buffer portion)
     *  still propagates to the monitor tap. */
    @Volatile private var lastAppliedTrackVolume: Float = -1f

    /**
     * Pre-allocated scratch buffer used by the monitor tap when [bufferGain]
     * != 1.0. Sized to the maximum chunk we expect from the capture loop
     * (see [MicCapture.captureLoop] — chunkFrames = 1024, stereo), so the tap
     * never allocates during audio playback. Reallocated only if a chunk
     * arrives that exceeds the current capacity.
     */
    private var monitorScratch: ShortArray = ShortArray(MONITOR_SCRATCH_INITIAL_FRAMES * 2)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) return

        ensureChannel()
        startForegroundCompat(MONITOR_NOTIF_ID, buildNotification())

        val track = buildAudioTrack()
        if (track == null) {
            Log.e(TAG, "AudioTrack could not be created")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        audioTrack = track
        // Apply the persisted Live-listen volume + max-dB range to the new
        // AudioTrack BEFORE playing it, so the user hears their stored level
        // on the very first buffer. [recomputeAndApplyGain] splits the total
        // linear gain into a `trackVolume` (≤ AudioTrack.getMaxVolume, fed to
        // setVolume) and a `bufferGain` (per-sample multiplier for the rest,
        // used by the monitor tap) — the two-stage split is what makes boost
        // above unity actually audible on devices where getMaxVolume() == 1.0
        // (setVolume alone is capped at the platform max).
        recomputeAndApplyGain(store.snapshot())
        try { track.play() } catch (e: Exception) {
            Log.e(TAG, "AudioTrack.play failed", e)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }

        val tap = MicCapture.MonitorTap { buf, frames, _ ->
            val bg = bufferGain
            val out: ShortArray
            val frameCount: Int
            if (bg == 1.0f) {
                // Most common case (user at 0 % or mild attenuation): skip the
                // buffer multiplication entirely and write the capture's PCM
                // straight through. No allocation, no per-sample math.
                out = buf
                frameCount = frames
            } else {
                // Boost case: copy into the pre-allocated scratch buffer and
                // multiply every sample by [bufferGain] with rounding and
                // hard clamping to the 16-bit signed range. Extreme settings
                // (±75 dB at the slider's extreme) will produce a fully clipped
                // signal — that's the user opting in to distortion, not a bug.
                val needed = frames * 2
                var scratch = monitorScratch
                if (scratch.size < needed) {
                    scratch = ShortArray(needed)
                    monitorScratch = scratch
                }
                val lo = Short.MIN_VALUE.toInt()
                val hi = Short.MAX_VALUE.toInt()
                var i = 0
                val end = needed
                while (i < end) {
                    val s = buf[i].toInt()
                    val g = (s * bg).roundToInt().coerceIn(lo, hi)
                    scratch[i] = g.toShort()
                    i++
                }
                out = scratch
                frameCount = frames
            }
            // BLOCKING write — the capture thread absorbs back-pressure through the
            // AudioTrack's internal ~1 s ring buffer (4 × getMinBufferSize).
            try { track.write(out, 0, frameCount * 2) } catch (_: Throwable) {}
        }

        // Make sure a live capture exists; tap it.
        var target = CaptureSession.get()
        if (target == null || !target.isRunning()) {
            LiveCaptureService.start(this)
            // Give the live service a beat to publish its capture, then re-check.
            Thread.sleep(150)
            target = CaptureSession.get()
        }
        if (target == null || !target.isRunning()) {
            Log.e(TAG, "No live capture available to tap")
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
            audioTrack = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }
        target.setMonitorTap(tap)
        attachedTo = target

        // Observe the store for monitorVolume / listenMaxDb changes while
        // monitoring is active. Only re-applies the gain split when the
        // combined linear gain actually changes — see
        // [recomputeAndApplyGain] for the change-detection logic.
        unsubscribeState = store.observe { state ->
            recomputeAndApplyGain(state)
        }

        store.setMonitoring(true)
    }

    private fun stopMonitoring() {
        unsubscribeState?.invoke()
        unsubscribeState = null

        try { attachedTo?.setMonitorTap(null) } catch (_: Throwable) {}
        attachedTo = null

        val track = audioTrack
        audioTrack = null
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}

        // Reset so a future start applies the stored gain cleanly without
        // being short-circuited by a stale "same gain" early-out.
        trackVolume = 1.0f
        bufferGain = 1.0f

        store.setMonitoring(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildAudioTrack(): AudioTrack? {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) return null
        val bufferSize = minBuf * 4 // ~1 s ring buffer at 44.1 kHz stereo 16-bit
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack build failed", e)
            null
        }
    }

    /**
     * Recompute the linear gain for the current [MicStateStore.State]
     * (volume percent + max-dB range) and apply it to the live AudioTrack as
     * a two-stage split:
     *  - `trackVolume` ≤ [AudioTrack.getMaxVolume], fed to [AudioTrack.setVolume];
     *  - `bufferGain` = `gain / trackVolume`, applied per-sample inside the
     *    monitor tap (with rounding + 16-bit clamping) for any boost that
     *    setVolume can't reach.
     *
     * The two-stage split is what makes boost above unity actually audible on
     * devices where [AudioTrack.getMaxVolume] is exactly 1.0 (which is
     * [AudioTrack.setVolume]'s hard cap on every modern Android — the call
     * throws IllegalArgumentException above it; on those devices the
     * setVolume-only path used to clip silently at unity).
     *
     * [AudioTrack.getMaxVolume] is a *static* method on the class (the
     * per-instance equivalent was removed in API 28+); we call it on the
     * class, not on the track instance.
     *
     * Both `trackVolume` and `bufferGain` are `@Volatile` so the capture
     * thread can read [bufferGain] lock-free on every chunk. Change-detection
     * (skip if the combined gain didn't move) avoids redundant setVolume calls
     * on every observer tick when the slider sits on the same step.
     *
     * Per-instance and channel-symmetric (both L and R get the same gain) —
     * does NOT modify any [android.media.AudioManager] stream volume, so the
     * user's media / ring / alarm / call volume stays untouched. Also does
     * NOT touch the PCM data being captured and written to disk, so a
     * recording running in parallel with Live listen keeps its original level.
     */
    private fun recomputeAndApplyGain(state: MicStateStore.State) {
        val track = audioTrack ?: return
        val gain = computeLinearGain(state.monitorVolume, state.listenMaxDb)
        val maxVolume = AudioTrack.getMaxVolume()
        // Early-out only when BOTH stages are unchanged; otherwise an idle
        // `bufferGain` change (max-dB slider drag while volume is at 0 %)
        // would still need to reach the tap.
        if (gain == lastAppliedGain && trackVolume == lastAppliedTrackVolume) return
        lastAppliedGain = gain
        // Clamp the setVolume portion to the platform's maxVolume. Whatever's
        // left over (gain / trackVolume) is delivered via buffer multiplication
        // inside the monitor tap.
        val setVolumePart = if (gain < maxVolume) gain else maxVolume
        val bufferPart = if (setVolumePart > 0f) gain / setVolumePart else 1.0f
        try {
            track.setVolume(setVolumePart)
            trackVolume = setVolumePart
            bufferGain = bufferPart
            lastAppliedTrackVolume = setVolumePart
        } catch (e: Exception) {
            // setVolume throws IllegalArgumentException only if the value is
            // outside [0, getMaxVolume()]; the clamp above already guarantees
            // that, but we catch defensively so a weird OEM quirk never
            // kills the foreground service.
            Log.e(TAG, "AudioTrack.setVolume($setVolumePart) failed (max=$maxVolume)", e)
        }
    }

    /**
     * Map the user's Listen-volume slider (-100..+100, 0 = unity) to a linear
     * gain factor, given the current max-dB range (0..75). The slider linearly
     * maps to ±maxDb on a logarithmic (dB) scale: gain = `10^((v/100) * (maxDb/20))`.
     * At `maxDb == 0` the gain is always 1.0 (volume slider locked).
     */
    private fun computeLinearGain(volumePercent: Int, maxDb: Int): Float {
        if (maxDb == 0) return 1.0f
        val v = volumePercent.toFloat() / 100f
        val exponent = v * (maxDb.toFloat() / 20f)
        return 10f.pow(exponent)
    }

    // ---- Notification --------------------------------------------------------------

    private fun ensureChannel() {
        NotificationFactory.ensureChannel(this, prefs)
        // A monitor-specific channel keeps the "stop" action visible with low importance.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(MONITOR_CHANNEL_ID) != null) return
        val localized = LocaleManager.wrapContext(this, prefs)
        val channel = android.app.NotificationChannel(
            MONITOR_CHANNEL_ID,
            localized.getString(R.string.monitor_label),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localized.getString(R.string.monitor_hint)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val localized = LocaleManager.wrapContext(this, prefs)
        val stopPi = PendingIntent.getService(
            this, 11, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 12, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(localized.getString(R.string.monitor_label))
            .setContentText(localized.getString(R.string.status_monitor_on))
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, localized.getString(R.string.monitor_stop), stopPi)
            .build()
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
        try { attachedTo?.setMonitorTap(null) } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        // Reset so a future start applies the stored gain cleanly without
        // being short-circuited by a stale "same gain" early-out.
        trackVolume = 1.0f
        bufferGain = 1.0f
        lastAppliedGain = -1f
        lastAppliedTrackVolume = -1f
        store.setMonitoring(false)
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val MONITOR_CHANNEL_ID = "stereo_analog_recorder_monitor_v1"
        private const val MONITOR_NOTIF_ID = 2002
        /** Initial capacity of [monitorScratch] in frames (stereo: × 2 shorts). */
        private const val MONITOR_SCRATCH_INITIAL_FRAMES = 4096

        const val ACTION_START = "com.stereoanalogrecorder.app.action.MONITOR_START"
        const val ACTION_STOP = "com.stereoanalogrecorder.app.action.MONITOR_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
