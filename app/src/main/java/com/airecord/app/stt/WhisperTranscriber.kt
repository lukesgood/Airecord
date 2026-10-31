package com.airecord.app.stt

import com.airecord.app.core.AudioMath
import com.airecord.app.core.TranscriptCleaner
import com.airecord.app.core.Wav
import com.airecord.app.core.WavReader
import com.airecord.app.data.Segment
import com.airecord.app.service.ProcessingCancelledException
import java.io.Closeable
import java.io.File

/**
 * Runs whisper.cpp over a 16 kHz WAV file. Long recordings are processed in
 * fixed windows so memory stays bounded and partial results can be saved.
 */
class WhisperTranscriber(modelFile: File, private val threads: Int, useGpu: Boolean) : Closeable {
    private val ctx: Long

    init {
        WhisperLib.loadError?.let { throw IllegalStateException("네이티브 라이브러리를 불러오지 못했습니다: ${it.message}", it) }
        ctx = WhisperLib.initContext(modelFile.absolutePath, useGpu)
        check(ctx != 0L) { "Whisper 모델을 불러오지 못했습니다: ${modelFile.name}" }
    }

    fun transcribe(
        wav: File,
        language: String,
        initialPrompt: String?,
        onProgress: (Float) -> Unit,
        onChunk: (List<Segment>) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        WavReader(wav).use { reader ->
            require(reader.sampleRate == Wav.SAMPLE_RATE) { "16kHz WAV가 필요합니다 (현재 ${reader.sampleRate}Hz)" }
            val total = reader.totalFrames.coerceAtLeast(1)
            var offset = 0L
            while (true) {
                if (isCancelled()) throw ProcessingCancelledException()
                val chunk = reader.readFloats(CHUNK_SAMPLES) ?: break
                val base = offset
                offset += chunk.size

                // Whisper invents text on silence; skip windows with essentially no signal.
                if (chunk.size < MIN_SAMPLES || AudioMath.rms(chunk) < SILENCE_RMS) {
                    onProgress(offset.toFloat() / total)
                    continue
                }

                val ret = WhisperLib.fullTranscribe(ctx, threads, chunk, language, initialPrompt) { p ->
                    onProgress(((base + chunk.size * p / 100f) / total).coerceIn(0f, 1f))
                }
                if (ret == WhisperLib.ABORTED || isCancelled()) throw ProcessingCancelledException()
                check(ret == 0) { "음성 인식 실패 (code $ret)" }

                val baseMs = base * 1000 / Wav.SAMPLE_RATE
                val segments = (0 until WhisperLib.segmentCount(ctx)).map { i ->
                    Segment(
                        startMs = baseMs + WhisperLib.segmentT0(ctx, i) * 10,
                        endMs = baseMs + WhisperLib.segmentT1(ctx, i) * 10,
                        text = String(WhisperLib.segmentText(ctx, i), Charsets.UTF_8).trim(),
                    )
                }
                onChunk(TranscriptCleaner.clean(segments))
                onProgress(offset.toFloat() / total)
            }
        }
    }

    override fun close() = WhisperLib.freeContext(ctx)

    companion object {
        /** 5 minutes per whisper_full call. */
        private const val CHUNK_SAMPLES = Wav.SAMPLE_RATE * 300
        private const val MIN_SAMPLES = Wav.SAMPLE_RATE / 2
        private const val SILENCE_RMS = 0.002f
    }
}
