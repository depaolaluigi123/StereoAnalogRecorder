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
        try { track.play() } catch (e: Exception) {
            Log.e(TAG, "AudioTrack.play failed", e)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }

        val tap = MicCapture.MonitorTap { buf, frames, _ ->
            // BLOCKING write — the capture thread will absorb back-pressure through the
            // AudioTrack's internal ~1 s ring buffer (4 × getMinBufferSize).
            try { track.write(buf, 0, frames * 2) } catch (_: Throwable) {}
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
        store.setMonitoring(true)
    }

    private fun stopMonitoring() {
        try { attachedTo?.setMonitorTap(null) } catch (_: Throwable) {}
        attachedTo = null

        val track = audioTrack
        audioTrack = null
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}

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
        try { attachedTo?.setMonitorTap(null) } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        store.setMonitoring(false)
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val MONITOR_CHANNEL_ID = "stereo_analog_recorder_monitor_v1"
        private const val MONITOR_NOTIF_ID = 2002

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
