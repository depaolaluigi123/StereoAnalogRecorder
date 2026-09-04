package com.stereoanalogrecorder.app.state

import android.os.Handler
import android.os.Looper
import com.stereoanalogrecorder.app.settings.GainControlMode
import com.stereoanalogrecorder.app.settings.MeterStyle
import com.stereoanalogrecorder.app.settings.PreferencesRepository
import com.stereoanalogrecorder.app.settings.RecordFormat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process single source of truth for the mic-gain / recording UI.
 *
 * Gain/UI fields are writable from the activity; the recording service updates
 * `isRecording` / elapsed / meter / peak. The monitor (headphone live-listen)
 * service updates `isMonitoring`. The two are independent: a user can monitor
 * without recording, monitor while recording, or record without monitoring.
 * Observers refresh every view. [PreferencesRepository] is persistence only —
 * not the live sync bus.
 */
class MicStateStore(
    private val preferences: PreferencesRepository
) {

    data class State(
        val isRecording: Boolean,
        val isMonitoring: Boolean,
        val outputFileName: String?,
        val gainMic1Db: Int,
        val gainMic2Db: Int,
        val linkGains: Boolean,
        val maxGainScale: Int,
        val meterStyle: MeterStyle,
        val recordFormat: RecordFormat,
        val aacBitrateKbps: Int,
        /**
         * Sample rate of the current capture (Hz). Resolved from the user's
         * spinner selection and applied to both the live AudioRecord and the
         * output file (WAV header / AAC MediaFormat). A change to this field
         * requires the live capture to be re-opened; the spinner is therefore
         * disabled while a recording is in progress.
         */
        val sampleRateHz: Int,
        val elapsedMs: Long,
        val meterDb1: Float,
        val meterDb2: Float,
        val peakDb1: Float,
        val peakDb2: Float,
        /** True if a wired or Bluetooth audio output device suitable for live-listen is connected. */
        val headphonesConnected: Boolean,
        val gainControlMode: GainControlMode,
        /**
         * Per-instance listening volume for the Live listen AudioTrack,
         * -100..+100 percent (0 = unity). Applied to the AudioTrack via
         * [android.media.AudioTrack.setVolume] in
         * [com.stereoanalogrecorder.app.service.MonitorService] — does NOT
         * touch any system stream volume (media / ring / alarm / etc.), only
         * the monitor track itself, so the user's music / call volume is left
         * intact. Boost above the platform's [android.media.AudioTrack.getMaxVolume]
         * (often exactly 1.0) is applied as a per-sample buffer multiplication
         * in the monitor tap, so the user can actually hear amplification
         * beyond unity on devices where setVolume alone can't.
         */
        val monitorVolume: Int,
        /**
         * Maximum attenuation/boost (in dB, symmetric) that the [monitorVolume]
         * slider can apply. Range 0..75, default 20. The linear gain factor
         * applied to the monitor tap is
         * `10^((monitorVolume / 100) * (listenMaxDb / 20))` — i.e. the
         * volume slider linearly maps -100..+100 to -maxDb..+maxDb on a
         * logarithmic (dB) scale.
         */
        val listenMaxDb: Int
    )

    fun interface Observer {
        fun onStateChanged(state: State)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()

    @Volatile
    private var state: State = readFromPreferences()

    fun snapshot(): State = state

    fun observe(observer: Observer): () -> Unit {
        observers.add(observer)
        return { observers.remove(observer) }
    }

    // ---- Gain / UI writers (activity) ----------------------------------------------

    fun setGainMic1(db: Int) {
        update {
            val v = db.coerceIn(-maxGainScale, maxGainScale)
            val newMic2 = if (linkGains) v else gainMic2Db
            copy(gainMic1Db = v, gainMic2Db = newMic2)
        }
    }

    fun setGainMic2(db: Int) {
        update {
            val v = db.coerceIn(-maxGainScale, maxGainScale)
            val newMic1 = if (linkGains) v else gainMic1Db
            copy(gainMic2Db = v, gainMic1Db = newMic1)
        }
    }

    fun setLinkGains(linked: Boolean) {
        update {
            if (linked) {
                // When linking, average both into one common value (prefer mic1).
                val common = gainMic1Db
                copy(linkGains = true, gainMic1Db = common, gainMic2Db = common)
            } else {
                copy(linkGains = false)
            }
        }
    }

    fun setMaxGainScale(scale: Int) {
        update {
            val clamped = scale.coerceIn(
                PreferencesRepository.MIN_MAX_GAIN_SCALE,
                PreferencesRepository.MAX_MAX_GAIN_SCALE
            )
            copy(
                maxGainScale = clamped,
                gainMic1Db = gainMic1Db.coerceIn(-clamped, clamped),
                gainMic2Db = gainMic2Db.coerceIn(-clamped, clamped)
            )
        }
    }

    fun setGainControlMode(mode: GainControlMode) = update { copy(gainControlMode = mode) }
    fun setMeterStyle(style: MeterStyle) = update { copy(meterStyle = style) }
    fun setRecordFormat(format: RecordFormat) = update { copy(recordFormat = format) }
    fun setAacBitrateKbps(kbps: Int) = update {
        copy(aacBitrateKbps = if (kbps in PreferencesRepository.ALLOWED_BITRATES) kbps else aacBitrateKbps)
    }

    /**
     * Update the per-instance Live listen volume (percent, -100..+100; 0 = unity).
     * Coerced to the slider bounds defined by [PreferencesRepository.MIN_MONITOR_VOLUME]
     * / [PreferencesRepository.MAX_MONITOR_VOLUME]. No-op when the value is
     * already at the target so we don't trigger spurious state dispatches (and
     * therefore unnecessary AudioTrack.setVolume() calls) on every onChange
     * tick when the slider sits on the same step.
     */
    fun setMonitorVolume(percent: Int) = update {
        val clamped = percent.coerceIn(
            PreferencesRepository.MIN_MONITOR_VOLUME,
            PreferencesRepository.MAX_MONITOR_VOLUME
        )
        if (monitorVolume == clamped) this else copy(monitorVolume = clamped)
    }

    /**
     * Update the max attenuation/boost range (dB, 0..75) of the Live listen
     * volume slider. 0 locks the volume slider to 0 % (unity, no effect); 75
     * gives it the widest possible ±75 dB reach. Coerced to the slider bounds
     * defined by [PreferencesRepository.MIN_LISTEN_MAX_DB] /
     * [PreferencesRepository.MAX_LISTEN_MAX_DB]; no-op when unchanged.
     */
    fun setListenMaxDb(db: Int) = update {
        val clamped = db.coerceIn(
            PreferencesRepository.MIN_LISTEN_MAX_DB,
            PreferencesRepository.MAX_LISTEN_MAX_DB
        )
        if (listenMaxDb == clamped) this else copy(listenMaxDb = clamped)
    }

    /**
     * Update the selected sample rate (Hz). Coerces to the device's supported
     * set so the preference can never hold an unusable value. Persistence is
     * handled inside [persist] so the choice survives process restarts.
     */
    fun setSampleRateHz(hz: Int) = update {
        val allowed = PreferencesRepository.ALLOWED_SAMPLE_RATES
        val coerced = when {
            allowed.isEmpty() -> hz
            hz in allowed -> hz
            else -> allowed.firstOrNull { it == sampleRateHz } ?: allowed.first()
        }
        if (coerced == sampleRateHz) this else copy(sampleRateHz = coerced)
    }

    // ---- Headphone-availability hint (pushed by the activity, not persisted) ------

    fun setHeadphonesConnected(connected: Boolean) {
        update { if (headphonesConnected == connected) this else copy(headphonesConnected = connected) }
    }

    // ---- Recording writers (service) -----------------------------------------------

    fun setRecording(recording: Boolean, outputFileName: String? = null) =
        update {
            copy(
                isRecording = recording,
                outputFileName = if (recording) (outputFileName ?: this.outputFileName) else null,
                elapsedMs = if (recording) elapsedMs else 0L,
                meterDb1 = if (recording) meterDb1 else Float.NEGATIVE_INFINITY,
                meterDb2 = if (recording) meterDb2 else Float.NEGATIVE_INFINITY,
                peakDb1 = if (recording) peakDb1 else Float.NEGATIVE_INFINITY,
                peakDb2 = if (recording) peakDb2 else Float.NEGATIVE_INFINITY
            )
        }

    fun startRecordingFresh(fileName: String) = update {
        copy(
            isRecording = true,
            outputFileName = fileName,
            elapsedMs = 0L,
            meterDb1 = Float.NEGATIVE_INFINITY,
            meterDb2 = Float.NEGATIVE_INFINITY,
            peakDb1 = Float.NEGATIVE_INFINITY,
            peakDb2 = Float.NEGATIVE_INFINITY
        )
    }

    fun updateLive(elapsedMs: Long, meterDb1: Float, meterDb2: Float, peakDb1: Float, peakDb2: Float) =
        update {
            copy(
                elapsedMs = elapsedMs,
                meterDb1 = meterDb1,
                meterDb2 = meterDb2,
                peakDb1 = maxOf(peakDb1, this.peakDb1),
                peakDb2 = maxOf(peakDb2, this.peakDb2)
            )
        }

    /**
     * Update meter levels only — no elapsed (the live capture stays ambient; only the
     * recording service owns elapsed when a file is open). Keeps meters live while idle.
     *
     * `peakDb1/2` here is the *capture-side running max*; we overwrite (not maxOf) so
     * that a `MicCapture.resetPeaks()` (called when a recording starts, or when the
     * live capture starts) actually brings the displayed "highest level" back down —
     * otherwise a long-lived live capture would lock the peak number at the session max.
     */
    fun updateMeters(meterDb1: Float, meterDb2: Float, peakDb1: Float, peakDb2: Float) =
        update {
            copy(
                meterDb1 = meterDb1,
                meterDb2 = meterDb2,
                peakDb1 = peakDb1,
                peakDb2 = peakDb2
            )
        }

    /** Update only the elapsed counter (used by the recording service while a file is open). */
    fun setElapsed(elapsedMs: Long) = update {
        if (this.elapsedMs == elapsedMs) this else copy(elapsedMs = elapsedMs)
    }

    /** Reset peak-hold (called when a new recording starts). */
    fun resetPeaks() = update {
        copy(
            peakDb1 = Float.NEGATIVE_INFINITY,
            peakDb2 = Float.NEGATIVE_INFINITY
        )
    }

    /** Reset peak-hold for ONE channel only (1 or 2). DAW-style per-channel tap reset. */
    fun resetPeak(channel: Int) = update {
        if (channel == 1) copy(peakDb1 = Float.NEGATIVE_INFINITY)
        else copy(peakDb2 = Float.NEGATIVE_INFINITY)
    }

    // ---- Monitor (headphone live-listen) ------------------------------------------

    fun setMonitoring(active: Boolean) = update {
        if (isMonitoring == active) return@update this
        // When monitoring stops and no recording is running, reset the live meters
        // so the UI falls back to its idle "00:00 / rest" state.
        if (!active && !isRecording) {
            copy(
                isMonitoring = false,
                meterDb1 = Float.NEGATIVE_INFINITY,
                meterDb2 = Float.NEGATIVE_INFINITY,
                peakDb1 = Float.NEGATIVE_INFINITY,
                peakDb2 = Float.NEGATIVE_INFINITY,
                elapsedMs = 0L
            )
        } else {
            copy(isMonitoring = active)
        }
    }

    // ---- internals ------------------------------------------------------------------

    private fun update(transform: State.() -> State) {
        val next: State
        synchronized(this) {
            next = state.transform()
            if (next == state) return
            state = next
            persist(next)
        }
        dispatch(next)
    }

    private fun persist(next: State) {
        preferences.writeGainState(next.gainMic1Db, next.gainMic2Db, next.linkGains, next.maxGainScale)
        preferences.meterStyle = next.meterStyle
        preferences.recordFormat = next.recordFormat
        preferences.aacBitrateKbps = next.aacBitrateKbps
        preferences.sampleRateHz = next.sampleRateHz
        preferences.gainControlMode = next.gainControlMode
        preferences.monitorVolumePercent = next.monitorVolume
        preferences.listenMaxDb = next.listenMaxDb
    }

    private fun dispatch(next: State) {
        val deliver = {
            for (observer in observers) observer.onStateChanged(next)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else mainHandler.post(deliver)
    }

    private fun readFromPreferences(): State = State(
        isRecording = false,
        isMonitoring = false,
        outputFileName = null,
        gainMic1Db = preferences.gainMic1Db,
        gainMic2Db = preferences.gainMic2Db,
        linkGains = preferences.linkGains,
        maxGainScale = preferences.maxGainScale,
        meterStyle = preferences.meterStyle,
        recordFormat = preferences.recordFormat,
        aacBitrateKbps = preferences.aacBitrateKbps,
        sampleRateHz = preferences.sampleRateHz,
        elapsedMs = 0L,
        meterDb1 = Float.NEGATIVE_INFINITY,
        meterDb2 = Float.NEGATIVE_INFINITY,
        peakDb1 = Float.NEGATIVE_INFINITY,
        peakDb2 = Float.NEGATIVE_INFINITY,
        headphonesConnected = false,
        gainControlMode = preferences.gainControlMode,
        monitorVolume = preferences.monitorVolumePercent,
        listenMaxDb = preferences.listenMaxDb
    )
}
