package com.stereoanalogrecorder.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Discover which input sample rates the device's microphone actually accepts,
 * so the user can pick one their hardware genuinely supports — analogous to
 * [AacEncoder.getSupportedBitratesKbps] for the AAC bitrate ladder.
 *
 * The standard list is filtered through two probes:
 *  1. `AudioRecord.getMinBufferSize(rate, …)` returns > 0 when the OS / audio
 *     HAL can service that rate. A zero / negative result is "this rate is not
 *     supported at the input layer".
 *  2. The AAC encoder is also asked which input rates it can handle (its
 *     AudioCapabilities.sampleRates list). The intersection with the
 *     AudioRecord set is the user-visible list, so the selected rate is
 *     always encodable into the .m4a container as well.
 *
 * If both probes fail, we fall back to the standard 44100 / 48000 Hz pair —
 * the lowest common denominator for Android phones since API 26.
 */
object SampleRateSupport {

    private const val TAG = "SampleRateSupport"

    /**
     * Standard PCM sample rates to probe. Order is significant: 44100 and 48000
     * are universally supported, so they appear first; hi-fi rates (88200+)
     * only show up on devices that actually support them.
     */
    val CANDIDATE_RATES: IntArray = intArrayOf(
        8000, 11025, 16000, 22050, 32000, 44100, 48000,
        88200, 96000, 176400, 192000
    )

    /** Fallback if every probe fails. 44.1k is the safest assumption. */
    private val FALLBACK_RATES = listOf(44100, 48000)

    /** Default selected rate when the stored preference is not in the supported set. */
    const val DEFAULT_SAMPLE_RATE_HZ: Int = 44100

    /**
     * Subset of [CANDIDATE_RATES] that the device can both *capture* via
     * `AudioRecord` and *encode* via the hardware AAC encoder. Returned in
     * ascending order.
     */
    fun getSupportedInputRates(): List<Int> {
        val byRecord = probeAudioRecordSupport()
        val byEncoder = probeEncoderSupport()
        val candidates = if (byRecord.isEmpty() && byEncoder.isEmpty()) {
            FALLBACK_RATES
        } else {
            // Use AudioRecord as the source of truth (the encoder subset may
            // be empty on devices that lack an AAC encoder).
            if (byEncoder.isEmpty()) byRecord else (byRecord intersect byEncoder.toSet()).sorted()
        }
        Log.i(TAG, "Supported sample rates: $candidates (record=$byRecord encoder=$byEncoder)")
        return candidates
    }

    /**
     * Return the first element of [supportedRates] equal to [preferred] (if
     * present), otherwise the highest rate in the list. Returns
     * [DEFAULT_SAMPLE_RATE_HZ] if [supportedRates] is empty.
     */
    fun pickDefault(
        supportedRates: List<Int>,
        preferred: Int
    ): Int {
        if (supportedRates.isEmpty()) return DEFAULT_SAMPLE_RATE_HZ
        return supportedRates.firstOrNull { it == preferred } ?: supportedRates.last()
    }

    /**
     * Query [AudioRecord.getMinBufferSize] for each candidate rate with
     * stereo 16-bit PCM input — the format the app actually uses. A result
     * > 0 means the rate is supported at the audio HAL.
     */
    private fun probeAudioRecordSupport(): List<Int> {
        val supported = mutableListOf<Int>()
        for (rate in CANDIDATE_RATES) {
            val minBuf = try {
                AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (e: Exception) {
                Log.d(TAG, "getMinBufferSize($rate) threw: ${e.message}")
                ERROR_BAD_VALUE
            }
            if (minBuf > 0) supported.add(rate)
        }
        return supported
    }

    /**
     * Ask the device's hardware AAC encoder which sample rates it can accept.
     * Returns the subset of [CANDIDATE_RATES] that the encoder supports, or
     * an empty list if the encoder info can't be queried.
     */
    private fun probeEncoderSupport(): List<Int> {
        return try {
            val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS)
                .getCodecInfos()
                .firstOrNull { info ->
                    info.isEncoder &&
                        MediaFormat.MIMETYPE_AUDIO_AAC in info.supportedTypes
                } ?: return emptyList()
            val caps = codecInfo
                .getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                ?.getAudioCapabilities() ?: return emptyList()
            val supported = caps.supportedSampleRates?.toList() ?: return emptyList()
            // Filter to a reasonable PCM rate set. Encoders can advertise
            // bizarre rates (e.g. 11000, 24000) that we don't want to expose.
            CANDIDATE_RATES.filter { it in supported }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query AAC encoder sample rates", e)
            emptyList()
        }
    }

    // AudioRecord.getMinBufferSize returns ERROR_BAD_VALUE on unsupported rates.
    private const val ERROR_BAD_VALUE = -2
}
