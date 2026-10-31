package com.airecord.app.core

/**
 * Prompts for on-device summarisation. Small local models have short context
 * windows, so long meetings are summarised map-reduce style:
 * chunk notes -> (optional condense rounds) -> final minutes.
 */
object SummaryPrompts {

    /** Rough char budget per chunk for a model with [maxTokens] total (input + output) tokens. */
    fun chunkCharBudget(maxTokens: Int): Int {
        // Reserve room for the instructions and the generated answer; assume ~1 token per Korean char.
        val reservedTokens = 1300
        return ((maxTokens - reservedTokens).coerceAtLeast(400) * 0.9).toInt()
    }

    private fun formatSpec(lang: String) = if (lang == "en") """
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
    """.trimMargin() else """
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

    private fun rules(lang: String) = if (lang == "en") """
        |You are an expert meeting-minutes writer. The transcript comes from speech recognition and may contain errors; interpret it from context.
        |Do not invent anything that is not in the source. Write in English.
    """.trimMargin() else """
        |너는 회의록 작성 전문가다. 원문은 음성 인식 결과라 오탈자가 있을 수 있으니 문맥에 맞게 해석하라.
        |원문에 없는 내용은 절대 지어내지 마라. 반드시 한국어로 작성하라.
    """.trimMargin()

    fun finalPrompt(transcript: String, title: String, lang: String): String = if (lang == "en") """
        |${rules(lang)}
        |Write the meeting minutes for the transcript below.
        |
        |${formatSpec(lang)}
        |
        |Meeting title: $title
        |Transcript:
        |""${'"'}
        |$transcript
        |""${'"'}
    """.trimMargin() else """
        |${rules(lang)}
        |아래 회의 녹취록을 읽고 회의록을 작성하라.
        |
        |${formatSpec(lang)}
        |
        |회의 제목: $title
        |녹취록:
        |""${'"'}
        |$transcript
        |""${'"'}
    """.trimMargin()

    fun chunkPrompt(chunk: String, index: Int, total: Int, lang: String): String = if (lang == "en") """
        |${rules(lang)}
        |Below is part ${index + 1} of $total of a long meeting transcript.
        |List, as concise bullet points, every important topic, decision, action item, number, date and name in this part.
        |Output only the bullet points.
        |
        |""${'"'}
        |$chunk
        |""${'"'}
    """.trimMargin() else """
        |${rules(lang)}
        |다음은 긴 회의 녹취록의 ${index + 1}/$total 부분이다.
        |이 부분에서 논의된 주제, 결정 사항, 할 일, 수치·일정·이름 등 중요한 사실을 빠짐없이 간결한 글머리표(-)로 정리하라.
        |글머리표 목록만 출력하라.
        |
        |""${'"'}
        |$chunk
        |""${'"'}
    """.trimMargin()

    fun condensePrompt(notes: String, lang: String): String = if (lang == "en") """
        |${rules(lang)}
        |Condense the following meeting notes into fewer bullet points without losing decisions, action items, numbers, dates or names.
        |Output only the bullet points.
        |
        |$notes
    """.trimMargin() else """
        |${rules(lang)}
        |다음 회의 메모를 결정 사항·할 일·수치·일정·이름을 잃지 않도록 더 간결한 글머리표 목록으로 줄여라.
        |글머리표 목록만 출력하라.
        |
        |$notes
    """.trimMargin()

    fun mergePrompt(notes: String, title: String, lang: String): String = if (lang == "en") """
        |${rules(lang)}
        |Below are notes taken from each part of one meeting, in order. Combine them into the final meeting minutes.
        |
        |${formatSpec(lang)}
        |
        |Meeting title: $title
        |Notes:
        |$notes
    """.trimMargin() else """
        |${rules(lang)}
        |다음은 한 회의의 각 부분을 순서대로 정리한 메모다. 이를 종합해 최종 회의록을 작성하라.
        |
        |${formatSpec(lang)}
        |
        |회의 제목: $title
        |메모:
        |$notes
    """.trimMargin()

    /** Strips wrapping code fences and stray end-of-turn tokens some models emit. */
    fun cleanOutput(raw: String): String {
        var t = raw.trim()
        listOf("<end_of_turn>", "<eos>", "<|im_end|>", "<|endoftext|>").forEach { t = t.replace(it, "") }
        t = t.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "")
            if (t.trimEnd().endsWith("```")) t = t.trimEnd().removeSuffix("```")
        }
        return t.trim()
    }
}
