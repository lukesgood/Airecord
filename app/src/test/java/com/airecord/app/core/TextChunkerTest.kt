package com.airecord.app.core

import com.airecord.app.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun shortTextIsSingleChunk() {
        assertEquals(listOf("안녕하세요"), TextChunker.chunk("  안녕하세요 ", 100))
        assertEquals(emptyList<String>(), TextChunker.chunk("   ", 100))
    }

    @Test
    fun chunksRespectLimitAndKeepAllText() {
        val lines = (1..200).map { "회의 내용 $it 번째 문장입니다." }
        val text = lines.joinToString("\n")
        val chunks = TextChunker.chunk(text, 300)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 300 })
        assertEquals(text.replace("\n", ""), chunks.joinToString("") { it.replace("\n", "") })
    }

    @Test
    fun veryLongLineIsSplit() {
        val line = "가나다라마바사 ".repeat(200)
        val chunks = TextChunker.chunk(line, 250)
        assertTrue(chunks.all { it.length <= 250 })
    }

    @Test
    fun cleanerDropsHallucinationsAndLoops() {
        val segs = listOf(
            Segment(0, 1000, "예산안 검토를 시작하겠습니다."),
            Segment(1000, 2000, "시청해주셔서 감사합니다."),
            Segment(2000, 3000, "네."),
            Segment(3000, 4000, "네."),
            Segment(4000, 5000, "네."),
            Segment(5000, 6000, "다음 안건입니다."),
        )
        val cleaned = TranscriptCleaner.clean(segs).map { it.text }
        assertEquals(listOf("예산안 검토를 시작하겠습니다.", "네.", "네.", "다음 안건입니다."), cleaned)
    }

    @Test
    fun minutesPromptHasMinutesStructureAndAttendees() {
        val r = SummaryRequest("주간회의", attendees = "김팀장, 이대리")
        val p = SummaryPrompts.finalPrompt("본문", r)
        listOf("## 1. 회의 안건", "## 2. 안건별 논의 내용", "## 3. 결정 사항", "## 4. 향후 조치 사항", "## 5. 기타 사항", "개조식", "김팀장, 이대리")
            .forEach { assertTrue(it, p.contains(it)) }
        val merge = SummaryPrompts.mergePrompt("- 메모", r)
        assertTrue(merge.contains("## 4. 향후 조치 사항"))

        val brief = SummaryPrompts.finalPrompt("본문", SummaryRequest("주간회의", style = SummaryStyle.BRIEF))
        assertTrue(brief.contains("## 한 줄 요약"))
        assertTrue(!brief.contains("개조식") && !brief.contains("참석자:"))

        val en = SummaryPrompts.finalPrompt("text", SummaryRequest("Weekly", lang = "en"))
        assertTrue(en.contains("## 4. Action items"))
        assertEquals(SummaryStyle.MINUTES, SummaryStyle.from(null))
        assertEquals(SummaryStyle.BRIEF, SummaryStyle.from("brief"))
    }

    @Test
    fun promptsAndOutputCleaning() {
        assertTrue(SummaryPrompts.finalPrompt("본문", SummaryRequest("주간회의")).contains("\"\"\"\n본문\n\"\"\""))
        assertTrue(SummaryPrompts.chunkCharBudget(4096) in 2000..3000)
        assertEquals("## 요약\n- a", SummaryPrompts.cleanOutput("```markdown\n## 요약\n- a\n```<end_of_turn>"))
        // A self-made document title is dropped; the app writes the real header.
        assertEquals("## 1. 회의 안건\n1. 예산", SummaryPrompts.cleanOutput("# 회의록\n\n## 1. 회의 안건\n1. 예산"))
    }
}
