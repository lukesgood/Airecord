package com.airecord.app.core

import kotlin.math.sqrt

object AudioMath {
    fun rms(buffer: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val v = buffer[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / length).toFloat()
    }

    fun rms(buffer: FloatArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0.0
        for (v in buffer) sum += v.toDouble() * v
        return sqrt(sum / buffer.size).toFloat()
    }

    /** Maps RMS to a 0..1 value that looks reasonable on a level meter. */
    fun meterLevel(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20 * kotlin.math.log10(rms)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }
}

/** Streaming linear-interpolation resampler for mono float audio. */
class LinearResampler(private val srcRate: Int, private val dstRate: Int) {
    private val step = srcRate.toDouble() / dstRate
    private var pos = 0.0
    private var last = 0f
    private var primed = false

    fun process(input: FloatArray, length: Int = input.size): FloatArray {
        if (length <= 0) return FloatArray(0)
        if (srcRate == dstRate) return input.copyOf(length)
        if (!primed) {
            last = input[0]
            primed = true
        }
        // Virtual array: index -1 is the last sample of the previous buffer.
        val estimate = ((length - pos) / step).toInt() + 2
        val out = FloatArray(estimate.coerceAtLeast(0))
        var n = 0
        while (pos < length - 1 && n < out.size) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) last else input[i]
            val b = input[i + 1]
            out[n++] = a + (b - a) * frac
            pos += step
        }
        pos -= length
        last = input[length - 1]
        return if (n == out.size) out else out.copyOf(n)
    }
}
