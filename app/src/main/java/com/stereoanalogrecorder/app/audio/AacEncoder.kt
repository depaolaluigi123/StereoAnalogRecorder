package com.stereoanalogrecorder.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * AAC LC encoder feeding an .m4a container via [MediaMuxer].
 *
 * Input = interleaved 16-bit PCM (stereo) produced by the gain DSP. The user-facing
 * UI labels this format "MP3" with selectable bitrates; Android has no native MP3
 * encoder, so the actual file is AAC inside an .m4a container (see README).
 */
class AacEncoder(
    private val outputFile: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrateKbps: Int
) {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var sawInputEos = false
    private var sawOutputEos = false
    private var totalFramesEncoded = 0L

    fun start() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, chunkBytes)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        encoder = codec

        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
    }

    /** Feed one interleaved 16-bit PCM chunk (frames × channels shorts). */
    fun feedPcm(shortBuf: ShortArray, frames: Int) {
        val codec = encoder ?: return
        if (sawInputEos) return
        val sampleCount = frames * channels
        val bytes = sampleCount * 2
        val ptsUs = totalFramesEncoded * 1_000_000L / sampleRate
        totalFramesEncoded += frames.toLong()

        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val ib = codec.getInputBuffer(inIdx) ?: return
            ib.clear()
            ib.asShortBuffer().put(shortBuf, 0, sampleCount)
            codec.queueInputBuffer(inIdx, 0, bytes, ptsUs, 0)
        }
        drain(false)
    }

    /** Flush + finalize the container. */
    fun finalize() {
        val codec = encoder ?: return
        if (!sawInputEos) {
            sawInputEos = true
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                codec.queueInputBuffer(inIdx, 0, 0, totalFramesEncoded * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        drain(true)
        try {
            codec.stop()
        } catch (_: Throwable) {}
        codec.release()
        encoder = null
        muxer?.let {
            if (muxerStarted) it.stop()
            it.release()
        }
        muxer = null
    }

    fun release() {
        try {
            encoder?.release()
        } catch (_: Throwable) {}
        encoder = null
        try {
            muxer?.release()
        } catch (_: Throwable) {}
        muxer = null
    }

    private fun drain(endOfStream: Boolean) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        val timeoutUs = if (endOfStream) 50_000L else 0L
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            when (outIdx) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) continue // keep draining until EOS seen
                    return
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.w(TAG, "Output format changed unexpectedly after muxer start")
                    }
                    val newFormat = codec.outputFormat
                    trackIndex = muxer?.addTrack(newFormat) ?: -1
                    muxer?.start()
                    muxerStarted = true
                }
                else -> {
                    if (outIdx >= 0) {
                        val ob = codec.getOutputBuffer(outIdx)
                        if (ob != null && info.size > 0 && trackIndex >= 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer?.writeSampleData(trackIndex, ob, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                            return
                        }
                    } else if (endOfStream && outIdx < 0 && sawOutputEos) {
                        return
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AacEncoder"
        private const val chunkBytes = 8192

        /**
         * Standard AAC-LC bitrate ladder (kbps) from 32 to 1536.
         * Filtered at runtime by the device encoder's maximum supported bitrate
         * in [getSupportedBitratesKbps].
         */
        private val BITRATE_LADDER = listOf(
            32, 48, 64, 80, 96, 128, 160, 192, 224, 256,
            320, 384, 448, 512, 576, 640, 768, 896, 1024,
            1152, 1280, 1440, 1536
        )

        /** Fallback max bitrate (kbps) when the encoder query fails or the range is unavailable. */
        private const val DEFAULT_MAX_BITRATE_FALLBACK_KBPS = 320

        /**
         * Query the device's hardware AAC encoder for its maximum supported bitrate [bps].
         * Returns -1 if the encoder can't be found or capabilities aren't available.
         */
        fun getEncoderMaxBitrateBps(): Long = try {
            val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS)
                .getCodecInfos()
                .firstOrNull { info ->
                    info.isEncoder &&
                        MediaFormat.MIMETYPE_AUDIO_AAC in info.supportedTypes
                }
            codecInfo
                ?.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                ?.getAudioCapabilities()
                ?.getBitrateRange()
                ?.upper
                ?.toLong()
                ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query AAC encoder bitrate range", e)
            -1L
        }

        /**
         * Return the list of AAC bitrates (kbps) available to the user.
         *
         * Queries the hardware AAC encoder for its maximum supported bitrate, then
         * filters a standard AAC bitrate ladder (32–1536 kbps) to only include
         * bitrates within the encoder's supported range.
         */
        fun getSupportedBitratesKbps(): List<Int> {
            val maxBps = getEncoderMaxBitrateBps()
            val maxKbps = if (maxBps > 0) (maxBps / 1000).toInt() else DEFAULT_MAX_BITRATE_FALLBACK_KBPS
            return BITRATE_LADDER.filter { it <= maxKbps }
        }
    }
}
