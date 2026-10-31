package com.airecord.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProcessingCancelledException : RuntimeException("취소됨")

/** A user-facing error (e.g. a model is not configured). */
class UserFacingException(message: String) : RuntimeException(message)

enum class ProcessingTask { FULL, TRANSCRIBE, SUMMARIZE }

enum class Stage(val label: String) {
    WAITING("대기 중"),
    LOADING_STT("음성 인식 모델 로딩"),
    TRANSCRIBING("음성 인식(전사) 중"),
    LOADING_LLM("요약 모델 로딩"),
    SUMMARIZING("AI 요약 중"),
}

data class ProcessingState(
    val meetingId: String,
    val stage: Stage,
    val progress: Float? = null,
    val detail: String = "",
)

data class RecorderState(
    val meetingId: String? = null,
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
)

/** Process-wide observable state shared between [MeetingService] and the UI. */
object ServiceStatus {
    internal val recorderFlow = MutableStateFlow(RecorderState())
    val recorder: StateFlow<RecorderState> = recorderFlow.asStateFlow()

    internal val processingFlow = MutableStateFlow<ProcessingState?>(null)
    val processing: StateFlow<ProcessingState?> = processingFlow.asStateFlow()

    internal val queueFlow = MutableStateFlow<List<String>>(emptyList())
    /** Meeting ids waiting to be processed. */
    val queue: StateFlow<List<String>> = queueFlow.asStateFlow()
}
