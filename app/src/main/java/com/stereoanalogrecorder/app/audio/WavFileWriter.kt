package com.stereoanalogrecorder.app.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * RIFF/WAVE PCM writer (16-bit and 24-bit packed) backed by a plain [File] in the
 * app cache dir. Writes the 44-byte header up front with placeholder sizes, streams
 * interleaved 16-bit PCM, then patches the RIFF and `data` chunk sizes on [finalize]
 * via a seekable [RandomAccessFile].
 *
 * 24-bit variant: each 16-bit processed sample is sign-extended to a 24-bit container
 * (a legitimate 24-bit PCM file playable on standard players, no device-native 24-bit
 * capture required).
 */
class WavFileWriter(
    private val file: File,
    private val channels: Int,
    private val sampleRate: Int,
    private val bitsPerSample: Int
) {

    init {
        require(bitsPerSample == 16 || bitsPerSample == 24) { "Unsupported bits per sample: $bitsPerSample" }
    }

    private val bytePerSample = bitsPerSample / 8
    private val blockAlign = channels * bytePerSample
    private var raf: RandomAccessFile? = null
    private var dataSize: Long = 0L

    fun start() {
        file.parentFile?.mkdirs()
        val r = RandomAccessFile(file, "rw")
        raf = r
        writeAscii("RIFF")
        writeIntLE(36)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * blockAlign)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample)
        writeAscii("data")
        writeIntLE(0)
    }

    fun writePcm(shortBuf: ShortArray, frames: Int) {
        val r = raf ?: return
        val sampleCount = frames * channels
        if (bitsPerSample == 16) {
            val bytes = ByteArray(sampleCount * 2)
            var bp = 0
            for (i in 0 until sampleCount) {
                val s = shortBuf[i].toInt()
                bytes[bp++] = (s and 0xFF).toByte()
                bytes[bp++] = ((s shr 8) and 0xFF).toByte()
            }
            r.write(bytes)
            dataSize += bytes.size.toLong()
        } else {
            val bytes = ByteArray(sampleCount * 3)
            var bp = 0
            for (i in 0 until sampleCount) {
                val s = shortBuf[i].toInt()
                bytes[bp++] = (s and 0xFF).toByte()
                bytes[bp++] = ((s shr 8) and 0xFF).toByte()
                bytes[bp++] = (if (s < 0) 0xFF else 0x00).toByte()
            }
            r.write(bytes)
            dataSize += bytes.size.toLong()
        }
    }

    fun finalize() {
        val r = raf ?: return
        r.seek(4)
        r.writeInt(intLE((36 + dataSize).toInt()))
        r.seek(40)
        r.writeInt(intLE(dataSize.toInt()))
        r.close()
        raf = null
    }

    fun release() {
        try { raf?.close() } catch (_: Throwable) {}
        raf = null
    }

    private fun writeAscii(s: String) {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = s[i].code.toByte()
        raf?.write(b)
    }

    private fun writeIntLE(v: Int) {
        raf?.write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        ))
    }

    private fun writeShortLE(v: Int) {
        raf?.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }

    private fun intLE(v: Int): Int =
        ((v and 0xFF) shl 24) or (((v shr 8) and 0xFF) shl 16) or
            (((v shr 16) and 0xFF) shl 8) or ((v shr 24) and 0xFF)
}
