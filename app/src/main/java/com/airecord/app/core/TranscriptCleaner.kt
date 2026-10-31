package com.airecord.app.core

import com.airecord.app.data.Segment

/**
 * Whisper is known to "hallucinate" stock phrases (typically YouTube outros) on
 * silence or noise, and to sometimes loop the same sentence. This removes the
 * most common cases.
 */
object TranscriptCleaner {
    private val stockPhrases = listOf(
        "시청해주셔서 감사합니다", "시청해 주셔서 감사합니다", "시청 해주셔서 감사합니다",
        "구독과 좋아요", "구독 좋아요", "좋아요와 구독", "MBC 뉴스", "KBS 뉴스", "SBS 뉴스",
        "자막 제공", "자막 by", "한글자막", "thank you for watching", "thanks for watching",
        "subtitles by", "please subscribe",
    )

    fun isLikelyHallucination(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return true
        if (t.length > 60) return false
        return stockPhrases.any { t.contains(it.lowercase()) }
    }

    fun clean(segments: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in segments) {
            val text = s.text.trim()
            if (isLikelyHallucination(text)) continue
            // Drop a segment repeated 3+ times in a row (decoder loop).
            if (out.size >= 2 && out[out.size - 1].text == text && out[out.size - 2].text == text) continue
            out += s.copy(text = text)
        }
        return out
    }
}
