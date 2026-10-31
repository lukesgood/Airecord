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
    /** Free-text meeting place, entered by the user. */
    val location: String = "",
    /** Free-text attendee list, entered by the user. Also given to the AI for names. */
    val attendees: String = "",
    /** [com.airecord.app.core.SummaryStyle] key the summary was written in. */
    val summaryStyle: String? = null,
    /** Language ("ko"/"en") the summary was written in. */
    val summaryLanguage: String? = null,
) {
    val transcriptText: String get() = segments.joinToString("\n") { it.text }
}
