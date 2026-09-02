package com.stereoanalogrecorder.app.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * dB / linear conversions and peak metering helpers for 16-bit PCM.
 *
 * Full scale (peak = 32768) maps to 0 dBFS. Negative dB = attenuation (the user's
 * anti-clip goal); positive dB = boost (clamped to int16 range downstream).
 */
object GainMath {

    /** 1 dB ↔ linear amplitude factor: 10^(db/20). */
    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    /** Sample-amplitude (0..32768) → dBFS (−Inf .. 0]. */
    fun amplitudeToDb(amplitude: Float): Float {
        if (amplitude <= 0f) return Float.NEGATIVE_INFINITY
        val a = amplitude / 32768f
        return 20f * log10(a.coerceAtLeast(Float.MIN_VALUE))
    }

    /** Apply a per-channel linear gain to an int16 sample, hard-clipping to int16 range. */
    fun applyGain(sample: Int, linear: Float): Int = (sample * linear).toInt().coerceIn(-32768, 32767)
}
