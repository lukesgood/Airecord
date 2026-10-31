package com.airecord.app.core

import com.airecord.app.data.Meeting
import com.airecord.app.data.MeetingRepository
import com.airecord.app.data.MeetingStatus
import com.airecord.app.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ExporterTest {
    private val meeting = Meeting(
        id = "1", title = "주간/회의: 1차", createdAt = 0, durationMs = 65_000,
        segments = listOf(Segment(0, 1000, "안녕하세요"), Segment(61_000, 62_000, "끝")),
        summary = "## 한 줄 요약\n테스트",
    )

    @Test
    fun minutesExportHasHeaderFromMetadata() {
        val m = meeting.copy(location = "3층 회의실", attendees = "김팀장, 이대리", summaryStyle = "minutes")
        val text = MeetingExporter.full(m)
        assertTrue(text, text.startsWith("# 회의록: 주간/회의: 1차\n"))
        assertTrue(text.contains("- **일시:** "))
        assertTrue(text.contains("(01:05)"))
        assertTrue(text.contains("- **장소:** 3층 회의실"))
        assertTrue(text.contains("- **참석자:** 김팀장, 이대리"))
        assertTrue(text.contains("## 첨부: 녹취록"))
        assertTrue(text.contains("[01:01] 끝"))

        // Empty place / attendees are left out rather than printed blank.
        val bare = MeetingExporter.summary(meeting)
        assertTrue(!bare.contains("장소") && !bare.contains("참석자"))

        val en = MeetingExporter.summary(m.copy(summaryLanguage = "en"))
        assertTrue(en.startsWith("# Meeting Minutes: "))
        assertTrue(en.contains("- **Attendees:** 김팀장, 이대리"))
    }

    @Test
    fun briefExportKeepsShortHeader() {
        val text = MeetingExporter.full(meeting.copy(summaryStyle = "brief"))
        assertTrue(text.startsWith("# 주간/회의: 1차\n"))
        assertTrue(text.contains("01:05"))
        assertTrue(text.contains("[01:01] 끝"))
        assertEquals("주간_회의_ 1차.md", MeetingExporter.fileName(meeting, "md"))
        assertEquals("1:01:01", TimeFormat.duration(3_661_000))
    }

    @Test
    fun repositoryPersistsAndRecovers() {
        val root = Files.createTempDirectory("meetings").toFile()
        val repo = MeetingRepository(root)
        val a = repo.create("녹음 중", MeetingStatus.RECORDING)
        val b = repo.create("요약 중", MeetingStatus.SUMMARIZING)
        WavWriter(repo.audioFile(a.id)).use { it.write(ShortArray(16_000)) }
        repo.update(b.id) { it.copy(summary = "x") }

        val reopened = MeetingRepository(root)
        assertEquals(MeetingStatus.RECORDED, reopened.get(a.id)!!.status)
        assertEquals(1000L, reopened.get(a.id)!!.durationMs)
        assertEquals(MeetingStatus.ERROR, reopened.get(b.id)!!.status)
        assertEquals("x", reopened.get(b.id)!!.summary)

        reopened.delete(a.id)
        assertEquals(1, MeetingRepository(root).meetings.value.size)
    }
}
