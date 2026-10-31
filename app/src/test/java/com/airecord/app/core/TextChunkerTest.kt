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
    fun promptsAndOutputCleaning() {
        assertTrue(SummaryPrompts.finalPrompt("본문", "주간회의", "ko").contains("\"\"\"\n본문\n\"\"\""))
        assertTrue(SummaryPrompts.chunkCharBudget(4096) in 2000..3000)
        assertEquals("## 요약\n- a", SummaryPrompts.cleanOutput("```markdown\n## 요약\n- a\n```<end_of_turn>"))
    }
}
