package com.airecord.app.llm

import android.content.Context
import com.airecord.app.core.SummaryPrompts
import com.airecord.app.core.TextChunker
import com.airecord.app.service.ProcessingCancelledException
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.Closeable
import java.io.File

/**
 * On-device summarisation with the MediaPipe LLM Inference API.
 * The GPU backend is tried first; if the device/model can't use it we fall back to CPU.
 */
class LlmSummarizer private constructor(
    private val llm: LlmInference,
    val backend: String,
    private val maxTokens: Int,
) : Closeable {

    fun summarize(
        transcript: String,
        title: String,
        lang: String,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        val budget = SummaryPrompts.chunkCharBudget(maxTokens)
        val chunks = TextChunker.chunk(transcript, budget)
        require(chunks.isNotEmpty()) { "요약할 내용이 없습니다" }

        if (chunks.size == 1) {
            onProgress(0f, "요약 작성 중")
            return SummaryPrompts.cleanOutput(generate(SummaryPrompts.finalPrompt(chunks[0], title, lang), isCancelled))
        }

        // Map: notes for each part. The final merge counts as one more step.
        val steps = chunks.size + 1f
        var notes = chunks.mapIndexed { i, chunk ->
            onProgress(i / steps, "부분 요약 ${i + 1}/${chunks.size}")
            SummaryPrompts.cleanOutput(generate(SummaryPrompts.chunkPrompt(chunk, i, chunks.size, lang), isCancelled))
        }

        // Reduce: condense until the notes fit in a single prompt.
        var rounds = 0
        while (notes.joinToString("\n").length > budget && rounds < 3) {
            rounds++
            val groups = TextChunker.chunk(notes.joinToString("\n"), budget)
            notes = groups.mapIndexed { i, g ->
                onProgress((steps - 1) / steps, "메모 압축 ${rounds}회차 ${i + 1}/${groups.size}")
                SummaryPrompts.cleanOutput(generate(SummaryPrompts.condensePrompt(g, lang), isCancelled))
            }
        }
        val merged = notes.joinToString("\n").let { if (it.length > budget) it.take(budget) else it }

        onProgress((steps - 1) / steps, "최종 회의록 작성 중")
        return SummaryPrompts.cleanOutput(generate(SummaryPrompts.mergePrompt(merged, title, lang), isCancelled))
    }

    private fun generate(prompt: String, isCancelled: () -> Boolean): String {
        if (isCancelled()) throw ProcessingCancelledException()
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.3f)
            .setTopK(40)
            .build()
        val session = LlmInferenceSession.createFromOptions(llm, options)
        try {
            session.addQueryChunk(prompt)
            val out = session.generateResponse()
            if (isCancelled()) throw ProcessingCancelledException()
            return out
        } finally {
            session.close()
        }
    }

    override fun close() = llm.close()

    companion object {
        fun create(context: Context, model: File, preferGpu: Boolean, maxTokens: Int): LlmSummarizer {
            val backends = if (preferGpu) listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)
            else listOf(LlmInference.Backend.CPU)
            var lastError: Throwable? = null
            for (backend in backends) {
                try {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(model.absolutePath)
                        .setMaxTokens(maxTokens)
                        .setPreferredBackend(backend)
                        .build()
                    return LlmSummarizer(LlmInference.createFromOptions(context, options), backend.name, maxTokens)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw IllegalStateException("요약 모델을 불러오지 못했습니다: ${lastError?.message}", lastError)
        }
    }
}
