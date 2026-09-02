package com.stereoanalogrecorder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.stereoanalogrecorder.app.R
import com.stereoanalogrecorder.app.audio.AlsaGainController
import com.stereoanalogrecorder.app.settings.GainControlMode
import com.stereoanalogrecorder.app.settings.LocaleManager
import com.stereoanalogrecorder.app.settings.PreferencesRepository
import com.stereoanalogrecorder.app.state.MicStateStore
import com.stereoanalogrecorder.app.ui.MainActivity

/**
 * Builds the foreground notifications used by the always-on capture services.
 *
 * The recording service has its own richer RemoteViews notification (classic controls);
 * this file also provides the bare-minimum "microphone in use" badge and the shared
 * classic-notification builder so [LiveCaptureService] and [RecordingService] produce
 * identical RemoteViews with different button sets.
 */
object NotificationFactory {

    const val CHANNEL_ID_LIVE = "stereo_analog_recorder_live_v1"
    const val CHANNEL_ID_CONTROLS = "stereo_analog_recorder_controls_v1"

    /** True the first time, to create the channel. Safe to call repeatedly. */
    fun ensureChannel(context: Context, prefs: PreferencesRepository) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr?.getNotificationChannel(CHANNEL_ID_LIVE) != null) return
        val localized = LocaleManager.wrapContext(context, prefs)
        val channel = NotificationChannel(
            CHANNEL_ID_LIVE,
            localized.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localized.getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        mgr?.createNotificationChannel(channel)
    }

    /** Create the classic-controls channel (shared by RecordingService and LiveCaptureService). */
    fun ensureControlsChannel(context: Context, prefs: PreferencesRepository) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr?.getNotificationChannel(CHANNEL_ID_CONTROLS) != null) return
        val localized = LocaleManager.wrapContext(context, prefs)
        val channel = NotificationChannel(
            CHANNEL_ID_CONTROLS,
            localized.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localized.getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        mgr?.createNotificationChannel(channel)
    }

    /** Pick the right PendingIntent factory for the runtime API. */
    fun pendingServiceIntent(
        context: Context,
        requestCode: Int,
        intent: android.content.Intent,
        useForegroundService: Boolean
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        else
            PendingIntent.getService(context, requestCode, intent, flags)
    }

    /** Format elapsed milliseconds as MM:SS. */
    fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /**
     * Build the classic RemoteViews notification (per-mic ±1 buttons, rec/stop).
     *
     * [isRecording] controls which action button is visible. Both services pass their
     * own PendingIntents so that gain-adjust commands always reach the service that
     * currently owns the foreground notification.
     *
     * [alsaController] is consulted only when the user has selected the
     * "Gain (root only)" control type — in that mode the slider in the app shows
     * the raw ALSA integer value (e.g. 0..20 on WCD937x), so the notification
     * shows the same integer (no "dB" suffix) to stay consistent with the UI.
     * When control type is "Level (volume)" — or when root/helper/controls are
     * not available and GAIN mode is silently downgraded to LEVEL — the
     * notification shows the dB value (with "dB" suffix) just like the slider.
     */
    fun buildClassicNotification(
        context: Context,
        prefs: PreferencesRepository,
        store: MicStateStore,
        isRecording: Boolean,
        contentPi: PendingIntent,
        stopPi: PendingIntent,
        recPi: PendingIntent,
        adjustPiMic1Minus: PendingIntent,
        adjustPiMic1Plus: PendingIntent,
        adjustPiMic2Minus: PendingIntent,
        adjustPiMic2Plus: PendingIntent,
        alsaController: AlsaGainController? = null
    ): Notification {
        ensureControlsChannel(context, prefs)
        val localized = LocaleManager.wrapContext(context, prefs)
        val state = store.snapshot()
        val remote = RemoteViews(context.packageName, R.layout.notification_gain)
        remote.setTextViewText(R.id.notifTitle, localized.getString(R.string.notification_title))
        remote.setTextViewText(
            R.id.notifElapsed,
            if (isRecording) localized.getString(
                R.string.notification_text_recording,
                formatElapsed(state.elapsedMs)
            )
            else localized.getString(R.string.notification_text_idle)
        )
        // Match what the slider shows: in GAIN mode (analog, root only) the
        // slider value is the raw ALSA integer step; in LEVEL mode it's dB.
        // Using the same units here keeps the notification in sync with the
        // value the user just dialed in. The "effective" mode mirrors the
        // UI's behavior: GAIN is downgraded to LEVEL when the controller is
        // not fully ready (no root, helper missing, no ADC controls).
        val showRawGain = state.gainControlMode == GainControlMode.ANALOG_GAIN &&
            alsaController?.isAnalogGainReady == true
        if (showRawGain) {
            val raw1 = dbToRaw(state.gainMic1Db, alsaController!!)
            val raw2 = dbToRaw(state.gainMic2Db, alsaController)
            remote.setTextViewText(
                R.id.notifMic1,
                localized.getString(R.string.notification_mic1_gain, raw1)
            )
            remote.setTextViewText(
                R.id.notifMic2,
                localized.getString(R.string.notification_mic2_gain, raw2)
            )
        } else {
            remote.setTextViewText(
                R.id.notifMic1,
                localized.getString(R.string.notification_mic1, state.gainMic1Db)
            )
            remote.setTextViewText(
                R.id.notifMic2,
                localized.getString(R.string.notification_mic2, state.gainMic2Db)
            )
        }
        remote.setOnClickPendingIntent(R.id.notifMic1Minus, adjustPiMic1Minus)
        remote.setOnClickPendingIntent(R.id.notifMic1Plus, adjustPiMic1Plus)
        remote.setOnClickPendingIntent(R.id.notifMic2Minus, adjustPiMic2Minus)
        remote.setOnClickPendingIntent(R.id.notifMic2Plus, adjustPiMic2Plus)
        if (isRecording) {
            remote.setViewVisibility(R.id.notifStop, View.VISIBLE)
            remote.setViewVisibility(R.id.notifRec, View.GONE)
            remote.setOnClickPendingIntent(R.id.notifStop, stopPi)
        } else {
            remote.setViewVisibility(R.id.notifStop, View.GONE)
            remote.setViewVisibility(R.id.notifRec, View.VISIBLE)
            remote.setOnClickPendingIntent(R.id.notifRec, recPi)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID_CONTROLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.notification_title))
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remote)
            .setCustomBigContentView(remote)
            .build()
    }

    /**
     * Convert a stored dB value (MicStateStore.gainMic1Db/2) to the raw ALSA
     * integer step on the codec, matching the formula used by MainActivity's
     * slider so the notification shows the same number the slider shows.
     *
     * Returns the original `db` value if the controller has no probed range
     * yet (discovery hasn't completed) — same defensive fallback as
     * MainActivity.dbToRaw so we never show "NaN" or "0" by mistake.
     */
    private fun dbToRaw(db: Int, controller: AlsaGainController): Int {
        val range = controller.mic1ControlRange() ?: return db
        return ((db / range.stepDb) + range.defaultVal).toInt()
            .coerceIn(range.min, range.max)
    }

    /** PendingIntent that opens MainActivity from the notification. */
    fun contentPendingIntent(context: Context): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun minimal(context: Context, prefs: PreferencesRepository): Notification {
        ensureChannel(context, prefs)
        val localized = LocaleManager.wrapContext(context, prefs)
        return NotificationCompat.Builder(context, CHANNEL_ID_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.notification_title))
            .setContentText(localized.getString(R.string.notification_minimal_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
