package com.airecord.app.service

import android.content.Context
import com.airecord.app.AppContainer
import com.airecord.app.data.MeetingStatus
import com.airecord.app.data.Segment
import com.airecord.app.llm.LlmSummarizer
import com.airecord.app.models.ModelKind
import com.airecord.app.stt.WhisperTranscriber

/** Transcription + summarisation pipeline for a single meeting. Runs on a background thread. */
class MeetingProcessor(private val context: Context, private val container: AppContainer) {
    private val repo get() = container.meetings

    fun process(
        meetingId: String,
        task: ProcessingTask,
        onState: (ProcessingState) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        try {
            if (task != ProcessingTask.SUMMARIZE) transcribe(meetingId, onState, isCancelled)
            if (task != ProcessingTask.TRANSCRIBE) summarize(meetingId, onState, isCancelled)
        } catch (e: ProcessingCancelledException) {
            repo.update(meetingId) {
                it.copy(
                    status = if (it.segments.isNotEmpty()) MeetingStatus.TRANSCRIBED else MeetingStatus.RECORDED,
                    error = "사용자가 처리를 취소했습니다.",
                )
            }
        } catch (t: Throwable) {
            val msg = when (t) {
                is UserFacingException -> t.message
                is OutOfMemoryError -> "메모리가 부족합니다. 더 작은 모델을 사용하세요."
                else -> t.message ?: t.toString()
            }
            repo.update(meetingId) { it.copy(status = MeetingStatus.ERROR, error = msg) }
        }
    }

    private fun transcribe(id: String, onState: (ProcessingState) -> Unit, isCancelled: () -> Boolean) {
        val settings = container.settings.state.value
        val model = container.models.file(ModelKind.WHISPER, settings.whisperModel)
            ?: throw UserFacingException("음성 인식(Whisper) 모델이 없습니다. 설정에서 모델을 다운로드하고 선택하세요.")
        val audio = repo.audioFile(id)
        if (!audio.exists()) throw UserFacingException("녹음 파일이 없습니다.")

        repo.update(id) {
            it.copy(status = MeetingStatus.TRANSCRIBING, error = null, segments = emptyList(), sttModel = model.name)
        }
        onState(ProcessingState(id, Stage.LOADING_STT, detail = model.name))

        val prompt = buildString {
            if (settings.language == "ko") append("다음은 회의 녹취록입니다.")
            if (settings.vocabularyHint.isNotBlank()) append(" ").append(settings.vocabularyHint.trim())
        }.trim().ifEmpty { null }

        val collected = mutableListOf<Segment>()
        WhisperTranscriber(model, settings.threads, settings.useGpu).use { stt ->
            stt.transcribe(
                wav = audio,
                language = settings.language,
                initialPrompt = prompt,
                onProgress = { p -> onState(ProcessingState(id, Stage.TRANSCRIBING, p)) },
                onChunk = { segs ->
                    collected += segs
                    repo.update(id) { it.copy(segments = collected.toList()) }
                },
                isCancelled = isCancelled,
            )
        }
        repo.update(id) { it.copy(status = MeetingStatus.TRANSCRIBED) }
    }

    private fun summarize(id: String, onState: (ProcessingState) -> Unit, isCancelled: () -> Boolean) {
        val settings = container.settings.state.value
        val meeting = repo.get(id) ?: return
        if (meeting.segments.isEmpty()) {
            throw UserFacingException("인식된 음성이 없어 요약할 수 없습니다.")
        }
        val model = container.models.file(ModelKind.LLM, settings.llmModel)
            ?: throw UserFacingException("요약 AI 모델이 없습니다. 설정에서 .task 모델 파일을 가져오고 선택하세요.")

        repo.update(id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null, llmModel = model.name) }
        onState(ProcessingState(id, Stage.LOADING_LLM, detail = model.name))

        LlmSummarizer.create(context, model, settings.useGpu, settings.llmMaxTokens).use { llm ->
            val summary = llm.summarize(
                transcript = meeting.transcriptText,
                title = meeting.title,
                lang = settings.summaryLanguage,
                onProgress = { p, detail -> onState(ProcessingState(id, Stage.SUMMARIZING, p, "$detail · ${llm.backend}")) },
                isCancelled = isCancelled,
            )
            repo.update(id) { it.copy(summary = summary, status = MeetingStatus.DONE, llmBackend = llm.backend) }
        }
    }
}
