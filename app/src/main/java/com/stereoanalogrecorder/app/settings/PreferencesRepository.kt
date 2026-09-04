package com.stereoanalogrecorder.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.stereoanalogrecorder.app.audio.AacEncoder
import com.stereoanalogrecorder.app.audio.SampleRateSupport

/**
 * Persistent storage for user choices (SharedPreferences).
 *
 * Live sync (UI ↔ service) goes through [com.stereoanalogrecorder.app.state.MicStateStore];
 * this repository is the durable backing store only.
 */
class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Per-mic gain in dB. Range: [-maxGainScale, +maxGainScale]. */
    var gainMic1Db: Int
        get() = prefs.getInt(KEY_GAIN_MIC1, 0).coerceIn(-maxGainScale, maxGainScale)
        set(value) {
            prefs.edit()
                .putInt(KEY_GAIN_MIC1, value.coerceIn(-maxGainScale, maxGainScale))
                .commit()
        }

    var gainMic2Db: Int
        get() = prefs.getInt(KEY_GAIN_MIC2, 0).coerceIn(-maxGainScale, maxGainScale)
        set(value) {
            prefs.edit()
                .putInt(KEY_GAIN_MIC2, value.coerceIn(-maxGainScale, maxGainScale))
                .commit()
        }

    var linkGains: Boolean
        get() = prefs.getBoolean(KEY_LINK_GAINS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LINK_GAINS, value).commit()
        }

    /** Symmetric ±dB ceiling for the gain sliders (the "scala del gain"). */
    var maxGainScale: Int
        get() = prefs.getInt(KEY_MAX_GAIN_SCALE, DEFAULT_MAX_GAIN_SCALE)
            .coerceIn(MIN_MAX_GAIN_SCALE, MAX_MAX_GAIN_SCALE)
        set(value) {
            val clamped = value.coerceIn(MIN_MAX_GAIN_SCALE, MAX_MAX_GAIN_SCALE)
            val editor = prefs.edit().putInt(KEY_MAX_GAIN_SCALE, clamped)
            // Clamp existing gains to the new bounds.
            if (prefs.getInt(KEY_GAIN_MIC1, 0) > clamped) editor.putInt(KEY_GAIN_MIC1, clamped)
            if (prefs.getInt(KEY_GAIN_MIC1, 0) < -clamped) editor.putInt(KEY_GAIN_MIC1, -clamped)
            if (prefs.getInt(KEY_GAIN_MIC2, 0) > clamped) editor.putInt(KEY_GAIN_MIC2, clamped)
            if (prefs.getInt(KEY_GAIN_MIC2, 0) < -clamped) editor.putInt(KEY_GAIN_MIC2, -clamped)
            editor.commit()
        }

    var meterStyle: MeterStyle
        get() = MeterStyle.fromStorage(prefs.getString(KEY_METER_STYLE, MeterStyle.DIGITAL.storageValue))
        set(value) = prefs.edit().putString(KEY_METER_STYLE, value.storageValue).apply()

    var recordFormat: RecordFormat
        get() = RecordFormat.fromStorage(prefs.getString(KEY_RECORD_FORMAT, RecordFormat.WAV16.storageValue))
        set(value) = prefs.edit().putString(KEY_RECORD_FORMAT, value.storageValue).apply()

    var aacBitrateKbps: Int
        get() = prefs.getInt(KEY_AAC_BITRATE, DEFAULT_AAC_BITRATE)
            .let { if (it in ALLOWED_BITRATES) it else DEFAULT_AAC_BITRATE }
        set(value) = prefs.edit()
            .putInt(KEY_AAC_BITRATE, if (value in ALLOWED_BITRATES) value else DEFAULT_AAC_BITRATE)
            .apply()

    /**
     * Selected capture sample rate (Hz). The list of valid values is computed
     * dynamically by [com.stereoanalogrecorder.app.audio.SampleRateSupport] for the
     * connected device. Stored value is validated against that list on read
     * and on write so the preference can never hold an unsupported rate.
     */
    var sampleRateHz: Int
        get() {
            val stored = prefs.getInt(KEY_SAMPLE_RATE_HZ, DEFAULT_SAMPLE_RATE_HZ)
            val allowed = ALLOWED_SAMPLE_RATES
            return if (allowed.isEmpty() || stored in allowed) stored
                else allowed.firstOrNull() ?: DEFAULT_SAMPLE_RATE_HZ
        }
        set(value) {
            val allowed = ALLOWED_SAMPLE_RATES
            val coerced = if (allowed.isEmpty() || value in allowed) value
                else allowed.firstOrNull() ?: DEFAULT_SAMPLE_RATE_HZ
            prefs.edit().putInt(KEY_SAMPLE_RATE_HZ, coerced).apply()
        }

    /** One async write for the whole gain snapshot (safe for rapid slider drags). */
    fun writeGainState(gainMic1Db: Int, gainMic2Db: Int, linkGains: Boolean, maxGainScale: Int) {
        val scale = maxGainScale.coerceIn(MIN_MAX_GAIN_SCALE, MAX_MAX_GAIN_SCALE)
        prefs.edit()
            .putInt(KEY_MAX_GAIN_SCALE, scale)
            .putInt(KEY_GAIN_MIC1, gainMic1Db.coerceIn(-scale, scale))
            .putInt(KEY_GAIN_MIC2, gainMic2Db.coerceIn(-scale, scale))
            .putBoolean(KEY_LINK_GAINS, linkGains)
            .apply()
    }

    var themeMode: ThemeMode
        get() = ThemeMode.fromStorage(prefs.getString(KEY_THEME, ThemeMode.DARK.storageValue))
        set(value) = prefs.edit().putString(KEY_THEME, value.storageValue).apply()

    var language: AppLanguage
        get() = AppLanguage.fromStorage(prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.storageValue))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.storageValue).apply()

    var gainControlMode: GainControlMode
        get() = GainControlMode.fromStorage(prefs.getString(KEY_GAIN_CONTROL_MODE, GainControlMode.ANALOG_GAIN.storageValue))
        set(value) = prefs.edit().putString(KEY_GAIN_CONTROL_MODE, value.storageValue).apply()

    /** Classic RemoteViews notification (per-mic ±1, rec/stop). */
    var showClassicNotification: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLASSIC_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CLASSIC_NOTIFICATION, value).apply()

    /**
     * Per-instance listening volume for the "Live listen" AudioTrack.
     *
     * Symmetric scale: -100 % (full attenuation / mute) .. +100 % (full boost),
     * with 0 % = unity (no change vs. the raw mic signal). The actual gain
     * applied to the monitor tap is
     * `10^((volume/100) * (maxDb/20))`, where [listenMaxDb] sets the upper
     * dB boundary that the slider reaches at ±100 %. The fraction that fits
     * inside `AudioTrack.setVolume()`'s range is applied there (per-instance
     * + channel-symmetric, no system stream touched); any remaining boost
     * above the platform's [android.media.AudioTrack.getMaxVolume] (often
     * exactly 1.0, which is what makes per-track boost impossible via
     * setVolume alone) is applied as a per-sample buffer multiplication
     * in the monitor tap with clamping.
     *
     * Persisted so the user's chosen headphone volume survives process
     * restarts and stays in effect across Live listen off/on cycles.
     */
    var monitorVolumePercent: Int
        get() = prefs.getInt(KEY_MONITOR_VOLUME, DEFAULT_MONITOR_VOLUME)
            .coerceIn(MIN_MONITOR_VOLUME, MAX_MONITOR_VOLUME)
        set(value) {
            prefs.edit()
                .putInt(KEY_MONITOR_VOLUME, value.coerceIn(MIN_MONITOR_VOLUME, MAX_MONITOR_VOLUME))
                .apply()
        }

    /**
     * Maximum attenuation/boost (in dB, single symmetric value, range 0..75)
     * that the "Listen volume" slider can apply. With the slider at -100 %
     * the gain is `10^(-maxDb/20)` (mute when maxDb > 0); at 0 % it's 1.0;
     * at +100 % it's `10^(+maxDb/20)`. Default 20 dB so the slider covers
     * ±20 dB on first launch — matching the existing "Gain scale" default
     * for the per-mic sliders.
     */
    var listenMaxDb: Int
        get() = prefs.getInt(KEY_LISTEN_MAX_DB, DEFAULT_LISTEN_MAX_DB)
            .coerceIn(MIN_LISTEN_MAX_DB, MAX_LISTEN_MAX_DB)
        set(value) {
            prefs.edit()
                .putInt(KEY_LISTEN_MAX_DB, value.coerceIn(MIN_LISTEN_MAX_DB, MAX_LISTEN_MAX_DB))
                .apply()
        }

    companion object {
        const val MIN_MAX_GAIN_SCALE = 12
        const val DEFAULT_MAX_GAIN_SCALE = 20
        const val MAX_MAX_GAIN_SCALE = 75

        /** Lower bound of the "Live listen" volume slider (percent). -100 = mute. */
        const val MIN_MONITOR_VOLUME = -100
        /** Upper bound of the "Live listen" volume slider (percent). +100 = max boost. */
        const val MAX_MONITOR_VOLUME = 100
        /** Default unity gain for the "Live listen" AudioTrack. */
        const val DEFAULT_MONITOR_VOLUME = 0

        /** Lower bound of the "Live listen" max-dB slider (dB). 0 = volume slider locked at 0 %. */
        const val MIN_LISTEN_MAX_DB = 0
        /** Upper bound of the "Live listen" max-dB slider (dB). */
        const val MAX_LISTEN_MAX_DB = 75
        /** Default max-dB range (matches the per-mic gain scale default). */
        const val DEFAULT_LISTEN_MAX_DB = 20

        /**
         * Bitrate options shown in the UI spinner, resolved dynamically from the
         * device's hardware AAC encoder. The result is computed lazily (once) on
         * first access and cached for subsequent calls.
         *
         * Falls back to a static ladder (32–320 kbps) if the encoder query fails.
         */
        val ALLOWED_BITRATES: List<Int> by lazy { AacEncoder.getSupportedBitratesKbps() }
        const val DEFAULT_AAC_BITRATE = 128

        /**
         * Sample rates (Hz) the device's microphone can capture AND the
         * hardware AAC encoder can accept. Resolved lazily via
         * [com.stereoanalogrecorder.app.audio.SampleRateSupport] the first time the
         * UI spinner is populated, then cached for the process lifetime.
         */
        val ALLOWED_SAMPLE_RATES: List<Int> by lazy {
            SampleRateSupport.getSupportedInputRates()
        }

        const val DEFAULT_SAMPLE_RATE_HZ: Int =
            SampleRateSupport.DEFAULT_SAMPLE_RATE_HZ

        const val KEY_GAIN_MIC1 = "gain_db_mic1"
        const val KEY_GAIN_MIC2 = "gain_db_mic2"
        const val KEY_LINK_GAINS = "link_gains"
        const val KEY_GAIN_CONTROL_MODE = "gain_control_mode"
        const val KEY_MAX_GAIN_SCALE = "max_gain_scale"

        private const val PREFS_NAME = "mic_gain_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_METER_STYLE = "meter_style"
        private const val KEY_RECORD_FORMAT = "record_format"
        private const val KEY_AAC_BITRATE = "aac_bitrate"
        private const val KEY_SAMPLE_RATE_HZ = "sample_rate_hz"
        private const val KEY_SHOW_CLASSIC_NOTIFICATION = "show_classic_notification"
        private const val KEY_MONITOR_VOLUME = "listen_volume"
        private const val KEY_LISTEN_MAX_DB = "listen_max_db"
    }
}

enum class ThemeMode(val storageValue: String) {
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: DARK
    }
}

enum class AppLanguage(val storageValue: String, val localeTag: String) {
    ENGLISH("en", "en"),
    ITALIAN("it", "it");

    companion object {
        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: ENGLISH
    }
}

enum class MeterStyle(val storageValue: String) {
    DIGITAL("digital"),
    ANALOG("analog");

    companion object {
        fun fromStorage(value: String?): MeterStyle =
            entries.firstOrNull { it.storageValue == value } ?: DIGITAL
    }
}

enum class RecordFormat(val storageValue: String, val extension: String, val mime: String) {
    WAV16("wav16", "wav", "audio/wav"),
    WAV24("wav24", "wav", "audio/wav"),
    M4A("m4a", "m4a", "audio/mp4"); // UI labels this as "MP3" — see README/AAC note.

    companion object {
        fun fromStorage(value: String?): RecordFormat =
            entries.firstOrNull { it.storageValue == value } ?: WAV16
    }
}

enum class GainControlMode(val storageValue: String) {
    ANALOG_GAIN("analog_gain"),
    LEVEL("level");

    companion object {
        fun fromStorage(value: String?): GainControlMode =
            entries.firstOrNull { it.storageValue == value } ?: ANALOG_GAIN
    }
}
