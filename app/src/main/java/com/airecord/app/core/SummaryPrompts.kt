package com.airecord.app.core

/** How the AI output is organised. */
enum class SummaryStyle(val key: String) {
    /** Formal meeting minutes: agenda, discussion per agenda item, decisions, action items. */
    MINUTES("minutes"),
    /** Short summary: one-liner, key points, decisions, action items. */
    BRIEF("brief");

    companion object {
        fun from(key: String?): SummaryStyle = entries.firstOrNull { it.key == key } ?: MINUTES
    }
}

/** Everything the prompts need to know about the meeting besides the transcript. */
data class SummaryRequest(
    val title: String,
    val lang: String = "ko",
    val style: SummaryStyle = SummaryStyle.MINUTES,
    /** Free text entered by the user, e.g. "김팀장, 이대리, 박주임". */
    val attendees: String = "",
)

/**
 * Prompts for on-device summarisation. Small local models have short context
 * windows, so long meetings are summarised map-reduce style:
 * chunk notes -> (optional condense rounds) -> final document.
 *
 * The model only writes the body. The minutes header (date, duration, place,
 * attendees) is filled in from the meeting's metadata by [MeetingExporter], so
 * the model never has to guess it.
 */
object SummaryPrompts {

    /** Rough char budget per chunk for a model with [maxTokens] total (input + output) tokens. */
    fun chunkCharBudget(maxTokens: Int): Int {
        // Reserve room for the instructions and the generated answer; assume ~1 token per Korean char.
        val reservedTokens = 1300
        return ((maxTokens - reservedTokens).coerceAtLeast(400) * 0.9).toInt()
    }

    private fun formatSpec(r: SummaryRequest): String = when {
        r.style == SummaryStyle.MINUTES && r.lang == "en" -> """
            |Respond ONLY with Markdown in exactly this structure (do not add a title or date header):
            |## 1. Agenda
            |1. (agenda item)
            |2. ...
            |## 2. Discussion
            |### 2-1. (agenda item)
            |- (who said / proposed what, key facts, numbers, dates)
            |### 2-2. (agenda item)
            |- ...
            |## 3. Decisions
            |- ... (write "None" if there are none)
            |## 4. Action items
            |- [Owner] Task (Due: date) — write "TBD" for an owner or date that was not mentioned
            |## 5. Other / next meeting
            |- (open issues, next meeting schedule; "None" if nothing)
        """.trimMargin()
        r.style == SummaryStyle.MINUTES -> """
            |다음 구조의 마크다운으로만 답하라 (제목·일시 머리말은 쓰지 마라):
            |## 1. 회의 안건
            |1. (안건)
            |2. ...
            |## 2. 안건별 논의 내용
            |### 2-1. (안건명)
            |- (누가 어떤 의견·제안을 했는지, 핵심 사실, 수치, 일정)
            |### 2-2. (안건명)
            |- ...
            |## 3. 결정 사항
            |- ... (없으면 "없음")
            |## 4. 향후 조치 사항 (Action Items)
            |- [담당자] 조치 내용 (기한: 날짜) — 언급되지 않은 담당자·기한은 "미정"
            |## 5. 기타 사항 및 차기 회의
            |- (미해결 이슈, 다음 회의 일정. 없으면 "없음")
        """.trimMargin()
        r.lang == "en" -> """
            |Respond ONLY with Markdown in exactly this structure:
            |## Summary
            |(one or two sentences)
            |## Key discussion points
            |- ...
            |## Decisions
            |- ... (write "None" if there are none)
            |## Action items
            |- [Owner] Task (Due date) — omit owner/date if not mentioned
            |## Open questions / follow-ups
            |- ...
        """.trimMargin()
        else -> """
            |다음 구조의 마크다운으로만 답하라:
            |## 한 줄 요약
            |(한두 문장)
            |## 주요 논의 내용
            |- ...
            |## 결정 사항
            |- ... (없으면 "없음")
            |## 할 일 (Action Items)
            |- [담당자] 할 일 (기한) — 언급이 없으면 담당자/기한 생략
            |## 추가 논의 필요 사항
            |- ...
        """.trimMargin()
    }

    private fun rules(r: SummaryRequest): String {
        val base = if (r.lang == "en") """
            |You are an expert minute-taker. The transcript comes from speech recognition and may contain errors; interpret it from context.
            |Do not invent anything that is not in the source. Write in English.
        """.trimMargin() else """
            |너는 회의록 작성 전문가다. 원문은 음성 인식 결과라 오탈자가 있을 수 있으니 문맥에 맞게 해석하라.
            |원문에 없는 내용은 절대 지어내지 마라. 반드시 한국어로 작성하라.
        """.trimMargin()
        val style = when {
            r.style != SummaryStyle.MINUTES -> ""
            r.lang == "en" -> "\nUse a formal, objective minutes tone: short noun phrases, no filler words or small talk."
            // 공문서식 개조식: "~함", "~하기로 함", "~예정" 으로 끝맺는다.
            else -> "\n공식 회의록 문체(개조식)로 써라: 문장은 \"~함\", \"~하기로 함\", \"~예정\", \"~필요\" 형태로 간결하게 끝맺고, 잡담·감탄사·반복 발언은 빼라."
        }
        val people = when {
            r.attendees.isBlank() -> ""
            r.lang == "en" -> "\nAttendees: ${r.attendees.trim()} — use these names for speakers and owners when the transcript makes it clear."
            else -> "\n참석자: ${r.attendees.trim()} — 녹취록에서 분명할 때 발언자·담당자 이름으로 사용하라."
        }
        return base + style + people
    }

    fun finalPrompt(transcript: String, r: SummaryRequest): String = if (r.lang == "en") """
        |${rules(r)}
        |Write the meeting minutes for the transcript below.
        |
        |${formatSpec(r)}
        |
        |Meeting title: ${r.title}
        |Transcript:
        |""${'"'}
        |$transcript
        |""${'"'}
    """.trimMargin() else """
        |${rules(r)}
        |아래 회의 녹취록을 읽고 회의록을 작성하라.
        |
        |${formatSpec(r)}
        |
        |회의 제목: ${r.title}
        |녹취록:
        |""${'"'}
        |$transcript
        |""${'"'}
    """.trimMargin()

    fun chunkPrompt(chunk: String, index: Int, total: Int, r: SummaryRequest): String = if (r.lang == "en") """
        |${rules(r)}
        |Below is part ${index + 1} of $total of a long meeting transcript.
        |List, as concise bullet points grouped by topic, every agenda item, opinion (with speaker if known), decision, action item, number, date and name in this part.
        |Output only the bullet points.
        |
        |""${'"'}
        |$chunk
        |""${'"'}
    """.trimMargin() else """
        |${rules(r)}
        |다음은 긴 회의 녹취록의 ${index + 1}/$total 부분이다.
        |이 부분에서 다룬 안건, (알 수 있으면 발언자별) 의견, 결정 사항, 할 일, 수치·일정·이름 등 중요한 사실을 주제별로 묶어 간결한 글머리표(-)로 빠짐없이 정리하라.
        |글머리표 목록만 출력하라.
        |
        |""${'"'}
        |$chunk
        |""${'"'}
    """.trimMargin()

    fun condensePrompt(notes: String, r: SummaryRequest): String = if (r.lang == "en") """
        |${rules(r)}
        |Condense the following meeting notes into fewer bullet points without losing agenda items, decisions, action items, numbers, dates or names.
        |Output only the bullet points.
        |
        |$notes
    """.trimMargin() else """
        |${rules(r)}
        |다음 회의 메모를 안건·결정 사항·할 일·수치·일정·이름을 잃지 않도록 더 간결한 글머리표 목록으로 줄여라.
        |글머리표 목록만 출력하라.
        |
        |$notes
    """.trimMargin()

    fun mergePrompt(notes: String, r: SummaryRequest): String = if (r.lang == "en") """
        |${rules(r)}
        |Below are notes taken from each part of one meeting, in order. Combine them into the final meeting minutes.
        |
        |${formatSpec(r)}
        |
        |Meeting title: ${r.title}
        |Notes:
        |$notes
    """.trimMargin() else """
        |${rules(r)}
        |다음은 한 회의의 각 부분을 순서대로 정리한 메모다. 이를 종합해 최종 회의록을 작성하라.
        |
        |${formatSpec(r)}
        |
        |회의 제목: ${r.title}
        |메모:
        |$notes
    """.trimMargin()

    /**
     * Strips wrapping code fences, stray end-of-turn tokens and any title/date header the
     * model added on its own (the app writes the header from real metadata).
     */
    fun cleanOutput(raw: String): String {
        var t = raw.trim()
        listOf("<end_of_turn>", "<eos>", "<|im_end|>", "<|endoftext|>").forEach { t = t.replace(it, "") }
        t = t.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "")
            if (t.trimEnd().endsWith("```")) t = t.trimEnd().removeSuffix("```")
        }
        t = t.trim()
        // Drop a leading "# 회의록 ..." line (a single '#', i.e. a document title).
        if (t.startsWith("# ")) t = t.substringAfter('\n', "").trim()
        return t
    }
}
