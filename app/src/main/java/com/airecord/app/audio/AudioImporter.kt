package com.airecord.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.airecord.app.core.LinearResampler
import com.airecord.app.core.Wav
import com.airecord.app.core.WavWriter
import java.io.File
import java.nio.ByteOrder

/** Decodes any audio file Android can play (m4a, mp3, ogg, wav…) into 16 kHz mono WAV. */
object AudioImporter {

    /** @return duration of the written WAV in ms. */
    fun importToWav(context: Context, uri: Uri, out: File, onProgress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            error("오디오 트랙을 찾을 수 없습니다")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler = LinearResampler(sampleRate, Wav.SAMPLE_RATE)

        try {
            WavWriter(out).use { writer ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val mono = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = buf.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                FloatArray(frames) { f ->
                                    var s = 0f
                                    for (c in 0 until channels) s += fb.get(f * channels + c)
                                    s / channels
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                val frames = sb.remaining() / channels
                                FloatArray(frames) { f ->
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get(f * channels + c) / 32768f
                                    s / channels
                                }
                            }
                            val resampled = resampler.process(mono)
                            writer.write(resampled)
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            resampler = LinearResampler(sampleRate, Wav.SAMPLE_RATE)
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        onProgress(1f)
        return Wav.durationMs(out)
    }
}
