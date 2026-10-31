package com.airecord.app.core

import com.airecord.app.data.Meeting

/** Text / Markdown renderings of a meeting used for sharing and export. */
object MeetingExporter {

    private fun isEnglish(m: Meeting) = m.summaryLanguage == "en"

    /**
     * Document header built from the meeting's real metadata (never from the AI).
     * Minutes style gets the classic 회의록 info block; brief style a one-line header.
     */
    fun header(m: Meeting): String {
        val en = isEnglish(m)
        val time = "${TimeFormat.dateTime(m.createdAt)} (${TimeFormat.duration(m.durationMs)})"
        if (SummaryStyle.from(m.summaryStyle) == SummaryStyle.BRIEF) {
            return "# ${m.title}\n$time"
        }
        return buildString {
            append(if (en) "# Meeting Minutes: ${m.title}\n\n" else "# 회의록: ${m.title}\n\n")
            append(if (en) "- **Date/time:** " else "- **일시:** ").append(time).append('\n')
            if (m.location.isNotBlank()) {
                append(if (en) "- **Location:** " else "- **장소:** ").append(m.location.trim()).append('\n')
            }
            if (m.attendees.isNotBlank()) {
                append(if (en) "- **Attendees:** " else "- **참석자:** ").append(m.attendees.trim()).append('\n')
            }
            append(
                if (en) "- **Prepared by:** AI (on-device), please review"
                else "- **작성:** AI 자동 작성 (기기 내 처리, 검토 필요)",
            )
        }
    }

    fun summary(m: Meeting): String = buildString {
        append(header(m))
        append("\n\n")
        append(m.summary?.trim().takeUnless { it.isNullOrEmpty() } ?: if (isEnglish(m)) "(No summary)" else "(요약 없음)")
        append('\n')
    }

    fun transcript(m: Meeting, withTimestamps: Boolean = true): String =
        m.segments.joinToString("\n") { s ->
            if (withTimestamps) "[${TimeFormat.duration(s.startMs)}] ${s.text}" else s.text
        }

    fun full(m: Meeting): String = buildString {
        append(summary(m))
        if (m.segments.isNotEmpty()) {
            append(if (isEnglish(m)) "\n---\n\n## Appendix: Transcript\n\n" else "\n---\n\n## 첨부: 녹취록\n\n")
            append(transcript(m))
            append('\n')
        }
    }

    /** A filesystem-safe file name derived from the meeting title. */
    fun fileName(m: Meeting, extension: String): String {
        val base = m.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(60)
            .ifEmpty { "meeting" }
        return "$base.$extension"
    }
}
