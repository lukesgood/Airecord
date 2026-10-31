package com.airecord.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {
    @Test
    fun resamplerProducesExpectedLengthAcrossBuffers() {
        val r = LinearResampler(48_000, 16_000)
        var total = 0
        repeat(10) { total += r.process(FloatArray(4800) { 0.5f }).size }
        // 48000 input samples -> ~16000 output samples
        assertTrue("got $total", total in 15_990..16_010)
    }

    @Test
    fun resamplerKeepsDcLevel() {
        val r = LinearResampler(44_100, 16_000)
        val out = r.process(FloatArray(44_100) { 0.25f })
        assertTrue(out.all { kotlin.math.abs(it - 0.25f) < 1e-6f })
    }

    @Test
    fun meterLevelBounds() {
        assertEquals(0f, AudioMath.meterLevel(0f))
        assertEquals(1f, AudioMath.meterLevel(1f), 1e-6f)
    }
}
