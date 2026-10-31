package com.airecord.app.data

import kotlinx.serialization.Serializable

@Serializable
data class Segment(val startMs: Long, val endMs: Long, val text: String)

@Serializable
enum class MeetingStatus { RECORDING, IMPORTING, RECORDED, TRANSCRIBING, TRANSCRIBED, SUMMARIZING, DONE, ERROR }

@Serializable
data class Meeting(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0L,
    val status: MeetingStatus = MeetingStatus.RECORDED,
    val segments: List<Segment> = emptyList(),
    val summary: String? = null,
    val error: String? = null,
    val sttModel: String? = null,
    val llmModel: String? = null,
    val llmBackend: String? = null,
) {
    val transcriptText: String get() = segments.joinToString("\n") { it.text }
}
