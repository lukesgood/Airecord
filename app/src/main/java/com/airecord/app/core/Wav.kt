package com.airecord.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono 16-bit PCM is what whisper.cpp expects, so the whole app uses it. */
object Wav {
    const val SAMPLE_RATE = 16_000
    const val HEADER_SIZE = 44

    fun header(dataBytes: Long, sampleRate: Int = SAMPLE_RATE, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val data = dataBytes.coerceIn(0L, 0xFFFF_FFFFL - 36)
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + data).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(data.toInt())
        }.array()
    }

    /** Rewrites the header of a file written by [WavWriter] (e.g. after the app was killed mid-recording). */
    fun fixHeader(file: File) {
        if (!file.exists() || file.length() < HEADER_SIZE) return
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(file.length() - HEADER_SIZE))
        }
    }

    /** Duration of a 16 kHz mono 16-bit file written by [WavWriter]. */
    fun durationMs(file: File): Long {
        if (!file.exists()) return 0
        val samples = (file.length() - HEADER_SIZE).coerceAtLeast(0) / 2
        return samples * 1000 / SAMPLE_RATE
    }
}

class WavWriter(file: File, private val sampleRate: Int = Wav.SAMPLE_RATE) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(Wav.header(0, sampleRate))
    }
    private var scratch = ByteArray(0)

    var samplesWritten: Long = 0
        private set

    fun write(buffer: ShortArray, length: Int = buffer.size) {
        if (length <= 0) return
        if (scratch.size < length * 2) scratch = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = buffer[i].toInt()
            scratch[i * 2] = (v and 0xFF).toByte()
            scratch[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(scratch, 0, length * 2)
        samplesWritten += length
    }

    fun write(buffer: FloatArray, length: Int = buffer.size) {
        val shorts = ShortArray(length) { i ->
            (buffer[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        write(shorts, length)
    }

    override fun close() {
        raf.seek(0)
        raf.write(Wav.header(samplesWritten * 2, sampleRate))
        raf.close()
    }
}

/** Reads 16-bit PCM WAV files as mono float samples in [-1, 1]. */
class WavReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    private val dataOffset: Long
    private val dataSize: Long
    private var position: Long = 0

    init {
        val head = ByteArray(12)
        raf.readFully(head)
        require(String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "WAV 파일이 아닙니다"
        }
        var rate = 0
        var ch = 0
        var bits = 0
        var offset = -1L
        var size = 0L
        val chunkHeader = ByteArray(8)
        while (raf.filePointer + 8 <= raf.length()) {
            raf.readFully(chunkHeader)
            val bb = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(4).toLong() and 0xFFFF_FFFFL
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(chunkSize.toInt().coerceAtLeast(16))
                    raf.readFully(fmt, 0, chunkSize.toInt())
                    val f = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    ch = f.getShort(2).toInt()
                    rate = f.getInt(4)
                    bits = f.getShort(14).toInt()
                    if (chunkSize % 2 == 1L) raf.skipBytes(1)
                }
                "data" -> {
                    offset = raf.filePointer
                    val remaining = raf.length() - offset
                    // A header left at 0 (interrupted recording) or bogus: use the rest of the file.
                    size = if (chunkSize == 0L || chunkSize > remaining) remaining else chunkSize
                    break
                }
                else -> raf.seek(raf.filePointer + chunkSize + (chunkSize and 1))
            }
        }
        require(offset >= 0) { "WAV data 청크가 없습니다" }
        require(bits == 16) { "16비트 PCM WAV만 지원합니다 (현재 ${bits}비트)" }
        require(ch >= 1) { "잘못된 채널 수" }
        sampleRate = rate
        channels = ch
        bitsPerSample = bits
        dataOffset = offset
        dataSize = size
        raf.seek(dataOffset)
    }

    private val frameBytes get() = channels * 2

    /** Number of (mono) frames in the file. */
    val totalFrames: Long get() = dataSize / frameBytes

    val durationMs: Long get() = if (sampleRate == 0) 0 else totalFrames * 1000 / sampleRate

    /** Returns up to [maxFrames] mono samples, or null at end of data. */
    fun readFloats(maxFrames: Int): FloatArray? {
        val remainingFrames = (dataSize - position) / frameBytes
        if (remainingFrames <= 0) return null
        val frames = minOf(maxFrames.toLong(), remainingFrames).toInt()
        val bytes = ByteArray(frames * frameBytes)
        raf.readFully(bytes)
        position += bytes.size
        val out = FloatArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val lo = bytes[idx].toInt() and 0xFF
                val hi = bytes[idx + 1].toInt()
                sum += ((hi shl 8) or lo).toShort() / 32768f
                idx += 2
            }
            out[f] = sum / channels
        }
        return out
    }

    override fun close() = raf.close()
}
