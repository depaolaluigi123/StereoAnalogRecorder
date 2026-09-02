package com.stereoanalogrecorder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stereoanalogrecorder.app.StereoAnalogRecorderApp
import com.stereoanalogrecorder.app.R
import com.stereoanalogrecorder.app.audio.AacEncoder
import com.stereoanalogrecorder.app.audio.AlsaGainController
import com.stereoanalogrecorder.app.audio.CaptureSession
import com.stereoanalogrecorder.app.audio.MicCapture
import com.stereoanalogrecorder.app.audio.WavFileWriter
import com.stereoanalogrecorder.app.settings.LocaleManager
import com.stereoanalogrecorder.app.settings.RecordFormat
import com.stereoanalogrecorder.app.state.MicStateStore
import com.stereoanalogrecorder.app.ui.MainActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Foreground recording service (type = microphone).
 *
 * Owns the [MicCapture] thread, a [WavFileWriter] or [AacEncoder], the elapsed-time
 * clock, and a persistent recording notification (classic RemoteViews and/or a minimal
 * notice — chosen in Settings, present in both themes). Per-buffer meter samples are
 * pushed to [MicStateStore] at ~15 fps so the UI meters/peak indicators update
 * without flooding the main thread.
 */
class RecordingService : Service() {

    private val prefs get() = (application as StereoAnalogRecorderApp).preferences
    private val store: MicStateStore get() = (application as StereoAnalogRecorderApp).micStateStore

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var capture: MicCapture? = null
    private var wavWriter: WavFileWriter? = null
    private var aacEncoder: AacEncoder? = null
    private var tempFile: File? = null
    private var outFormat = RecordFormat.WAV16

    private var meterRunnable: Runnable? = null
    private var startElapsedMs = 0L
    @Volatile private var lastNotifySec = -1L
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val fmt = intent.getStringExtra(EXTRA_FORMAT) ?: prefs.recordFormat.storageValue
                val bitrate = intent.getIntExtra(EXTRA_BITRATE_KBPS, prefs.aacBitrateKbps)
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE_HZ, prefs.sampleRateHz)
                startRecording(fmt, bitrate, sampleRate)
            }
            ACTION_STOP -> stopAndFinalize()
            ACTION_ADJUST -> {
                val delta = intent.getIntExtra(EXTRA_DELTA_DB, 0)
                val mic = intent.getIntExtra(EXTRA_MIC_INDEX, 0)
                if (mic == 0) store.setGainMic1(store.snapshot().gainMic1Db + delta)
                else store.setGainMic2(store.snapshot().gainMic2Db + delta)
                publishNotification()
            }
            ACTION_NOTIFY -> if (running) publishNotification()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(fmt: String, bitrateKbps: Int, sampleRateHz: Int) {
        if (running) { publishNotification(); return }

        // Post a minimal FGS notification IMMEDIATELY (within the ~5 s window).
        ensureChannel()
        startForegroundCompat(NOTIF_ID, buildMinimalNotification())

        outFormat = RecordFormat.fromStorage(fmt)
        val baseName = "StereoAnalogRecorder_${fileTimestamp()}.${outFormat.extension}"
        val temp = File(cacheDir, baseName)
        tempFile = temp

        // Build the file sink. The sample rate is taken from the live store
        // (via the intent extra) so the WAV header and AAC MediaFormat both
        // match what the AudioRecord actually captured.
        when (outFormat) {
            RecordFormat.WAV16, RecordFormat.WAV24 -> {
                val bits = if (outFormat == RecordFormat.WAV24) 24 else 16
                val writer = WavFileWriter(temp, channels = 2, sampleRate = sampleRateHz, bitsPerSample = bits)
                writer.start()
                wavWriter = writer
            }
            RecordFormat.M4A -> {
                val enc = AacEncoder(temp, sampleRateHz, 2, bitrateKbps)
                enc.start()
                aacEncoder = enc
            }
        }

        // Attach to the shared live capture (owned by LiveCaptureService). If it isn't
        // running yet, start it on demand. We must NOT open a second AudioRecord — most
        // devices refuse it and we'd lose the live meters.
        var target = CaptureSession.get()
        if (target == null || !target.isRunning()) {
            LiveCaptureService.start(this)
            Thread.sleep(150) // give the live service a moment to publish its capture
            target = CaptureSession.get()
        }
        if (target == null || !target.isRunning()) {
            Log.e(TAG, "Live capture unavailable — cannot record")
            wavWriter?.release(); wavWriter = null
            aacEncoder?.release(); aacEncoder = null
            tempFile = null
            store.setRecording(false)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }
        capture = target
        // Reset the running peak-hold so the "highest level measured" shown below each
        // meter is for THIS take — the live capture would otherwise carry the previous
        // session's max.
        target.resetPeaks()
        target.setSink(object : MicCapture.Sink {
            override fun onChunk(shortBuf: ShortArray, frames: Int, channels: Int) =
                onChunkData(shortBuf, frames)
            override fun finalize() { /* writers finalized at stop */ }
        })

        store.startRecordingFresh(baseName)
        store.resetPeaks()
        running = true
        startElapsedMs = SystemClock.elapsedRealtime()
        lastNotifySec = -1L

        startElapsedHeartbeat()
        publishNotification()
    }

    private fun onChunkData(buf: ShortArray, frames: Int) {
        try {
            wavWriter?.writePcm(buf, frames)
            aacEncoder?.feedPcm(buf, frames)
        } catch (e: Exception) {
            Log.e(TAG, "writer feed failed", e)
        }
    }

    private fun stopAndFinalize() {
        if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        running = false
        stopElapsedHeartbeat()

        // Detach our sink from the shared capture (keeps the live capture running for
        // metering). setSink(null) finalizes the outgoing sink callback atomically.
        capture?.setSink(null)
        capture = null

        // Finalize writers (patch WAV sizes / AAC container).
        wavWriter?.finalize(); wavWriter = null
        aacEncoder?.finalize(); aacEncoder = null

        // Publish to MediaStore.
        val savedName = store.snapshot().outputFileName
        val temp = tempFile; tempFile = null
        var published = false
        if (temp != null && temp.exists()) {
            published = publishToMediaStore(temp, outFormat) != null
            if (published) temp.delete()
        }
        store.setRecording(false)
        sendBroadcast(Intent(ACTION_SAVED).setPackage(packageName).putExtra(EXTRA_SAVED_NAME, savedName ?: ""))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Drives the elapsed counter + notification refresh while a file is open. */
    private fun startElapsedHeartbeat() {
        stopElapsedHeartbeat()
        val r = object : Runnable {
            override fun run() {
                if (!running) return
                val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
                store.setElapsed(elapsed)
                val sec = elapsed / 1000
                if (sec != lastNotifySec) {
                    lastNotifySec = sec
                    publishNotification()
                }
                mainHandler.postDelayed(this, 66)
            }
        }
        meterRunnable = r
        mainHandler.post(r)
    }

    private fun stopElapsedHeartbeat() {
        meterRunnable?.let { mainHandler.removeCallbacks(it) }
        meterRunnable = null
    }

    // ---- Output to MediaStore ---------------------------------------------------------

    private fun publishToMediaStore(tempFile: File, format: RecordFormat): Uri? {
        val resolver = contentResolver
        return try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, tempFile.name)
                put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Recordings/StereoAnalogRecorder/")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(pfd.fileDescriptor).use { out -> input.copyTo(out) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore publish failed", e)
            null
        }
    }

    // ---- Notification ----------------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localized = LocaleManager.wrapContext(this, prefs)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localized.getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun contentPendingIntent(): PendingIntent {
        val launch = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        return PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        return this.getForegroundServiceCompat(1, intent)
    }

    private fun recPendingIntent(): PendingIntent {
        val state = store.snapshot()
        val intent = Intent(this, RecordingService::class.java).setAction(ACTION_START)
            .putExtra(EXTRA_FORMAT, state.recordFormat.storageValue)
            .putExtra(EXTRA_BITRATE_KBPS, state.aacBitrateKbps)
            .putExtra(EXTRA_SAMPLE_RATE_HZ, state.sampleRateHz)
        return this.getForegroundServiceCompat(2, intent)
    }

    private fun adjustPendingIntent(micIdx: Int, delta: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(ACTION_ADJUST)
            .putExtra(EXTRA_DELTA_DB, delta)
            .putExtra(EXTRA_MIC_INDEX, micIdx)
        val code = 20 + micIdx * 2 + (if (delta > 0) 1 else 0)
        return this.getForegroundServiceCompat(code, intent)
    }

    private fun buildMinimalNotification(): Notification {
        ensureChannel()
        val localized = LocaleManager.wrapContext(this, prefs)
        val state = store.snapshot()
        val text = if (state.isRecording)
            localized.getString(R.string.notification_text_recording, formatElapsed(state.elapsedMs))
        else localized.getString(R.string.notification_text_idle)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_notification, localized.getString(R.string.notification_stop), stopPendingIntent())
            .build()
    }

    private fun buildClassicNotification(isRecording: Boolean = running): Notification {
        return NotificationFactory.buildClassicNotification(
            context = this,
            prefs = prefs,
            store = store,
            isRecording = isRecording,
            contentPi = contentPendingIntent(),
            stopPi = stopPendingIntent(),
            recPi = recPendingIntent(),
            adjustPiMic1Minus = adjustPendingIntent(0, -1),
            adjustPiMic1Plus = adjustPendingIntent(0, 1),
            adjustPiMic2Minus = adjustPendingIntent(1, -1),
            adjustPiMic2Plus = adjustPendingIntent(1, 1),
            // Pass the controller so the notification can show the raw ALSA
            // value (matching the slider) when the user has selected the
            // "Gain (root only)" control type. Null is safe — the factory
            // falls back to the dB label.
            alsaController = AlsaGainController.get(this)
        )
    }

    private fun publishNotification() {
        ensureChannel()
        if (!running) return
        val notif = if (prefs.showClassicNotification) buildClassicNotification() else buildMinimalNotification()
        startForegroundCompat(NOTIF_ID, notif)
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
        stopElapsedHeartbeat()
        if (running) {
            running = false
            // Detach our file sink; the live capture keeps running (for meters / monitor).
            capture?.setSink(null)
            capture = null
        }
        wavWriter?.release(); wavWriter = null
        aacEncoder?.release(); aacEncoder = null
    }

    // ---- Helpers ----------------------------------------------------------------------

    private fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun fileTimestamp(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d%02d%02d_%02d%02d%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND)
        )
    }

    companion object {
        const val ACTION_START = "com.stereoanalogrecorder.app.action.START"
        const val ACTION_STOP = "com.stereoanalogrecorder.app.action.STOP"
        const val ACTION_ADJUST = "com.stereoanalogrecorder.app.action.ADJUST"
        const val ACTION_NOTIFY = "com.stereoanalogrecorder.app.action.NOTIFY"
        const val ACTION_SAVED = "com.stereoanalogrecorder.app.action.SAVED"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_BITRATE_KBPS = "bitrate"
        const val EXTRA_SAMPLE_RATE_HZ = "sample_rate_hz"
        const val EXTRA_DELTA_DB = "delta_db"
        const val EXTRA_MIC_INDEX = "mic_index"
        const val EXTRA_SAVED_NAME = "saved_name"

        private const val CHANNEL_ID = "stereo_analog_recorder_controls_v1"
        private const val NOTIF_ID = 2001
        private const val TAG = "RecordingService"

        fun start(context: Context, format: RecordFormat, bitrateKbps: Int, sampleRateHz: Int) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FORMAT, format.storageValue)
                .putExtra(EXTRA_BITRATE_KBPS, bitrateKbps)
                .putExtra(EXTRA_SAMPLE_RATE_HZ, sampleRateHz)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Pick the right PendingIntent factory for the runtime API. */
private fun Context.getForegroundServiceCompat(
    requestCode: Int,
    intent: Intent
): PendingIntent {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        PendingIntent.getForegroundService(this, requestCode, intent, flags)
    else
        PendingIntent.getService(this, requestCode, intent, flags)
}
