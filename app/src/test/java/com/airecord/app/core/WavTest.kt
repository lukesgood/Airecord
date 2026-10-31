package com.airecord.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class WavTest {
    @Test
    fun writeThenReadRoundTrip() {
        val f = File.createTempFile("wav", ".wav")
        val samples = ShortArray(40_000) { ((it % 200) * 100 - 10_000).toShort() }
        WavWriter(f).use { it.write(samples) }

        assertEquals(Wav.HEADER_SIZE + samples.size * 2L, f.length())
        assertEquals(2500L, Wav.durationMs(f))

        WavReader(f).use { r ->
            assertEquals(16_000, r.sampleRate)
            assertEquals(1, r.channels)
            assertEquals(samples.size.toLong(), r.totalFrames)
            val a = r.readFloats(30_000)!!
            val b = r.readFloats(30_000)!!
            assertEquals(30_000, a.size)
            assertEquals(10_000, b.size)
            assertNull(r.readFloats(10))
            assertEquals(samples[0] / 32768f, a[0], 1e-6f)
            assertEquals(samples[39_999] / 32768f, b[9_999], 1e-6f)
        }
    }

    @Test
    fun readerHandlesInterruptedHeader() {
        val f = File.createTempFile("wav", ".wav")
        RandomAccessFile(f, "rw").use { raf ->
            raf.write(Wav.header(0))
            raf.write(ByteArray(3200)) // 1600 samples of silence, header never finalised
        }
        WavReader(f).use { assertEquals(1600L, it.totalFrames) }
        Wav.fixHeader(f)
        WavReader(f).use { assertEquals(100L, it.durationMs) }
    }
}
