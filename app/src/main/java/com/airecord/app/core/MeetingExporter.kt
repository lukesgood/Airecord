package com.airecord.app.core

import com.airecord.app.data.Meeting

/** Text / Markdown renderings of a meeting used for sharing and export. */
object MeetingExporter {
    fun header(m: Meeting): String =
        "# ${m.title}\n${TimeFormat.dateTime(m.createdAt)} · ${TimeFormat.duration(m.durationMs)}"

    fun summary(m: Meeting): String = buildString {
        append(header(m))
        append("\n\n")
        append(m.summary?.trim().takeUnless { it.isNullOrEmpty() } ?: "(요약 없음)")
        append('\n')
    }

    fun transcript(m: Meeting, withTimestamps: Boolean = true): String =
        m.segments.joinToString("\n") { s ->
            if (withTimestamps) "[${TimeFormat.duration(s.startMs)}] ${s.text}" else s.text
        }

    fun full(m: Meeting): String = buildString {
        append(summary(m))
        if (m.segments.isNotEmpty()) {
            append("\n---\n\n## 녹취록\n\n")
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
