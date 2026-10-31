package com.airecord.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.airecord.app.data.MeetingStatus

fun MeetingStatus.label(): String = when (this) {
    MeetingStatus.RECORDING -> "녹음 중"
    MeetingStatus.IMPORTING -> "가져오는 중"
    MeetingStatus.RECORDED -> "녹음 완료"
    MeetingStatus.TRANSCRIBING -> "전사 중"
    MeetingStatus.TRANSCRIBED -> "전사 완료"
    MeetingStatus.SUMMARIZING -> "요약 중"
    MeetingStatus.DONE -> "요약 완료"
    MeetingStatus.ERROR -> "오류"
}

@Composable
fun StatusChip(status: MeetingStatus) {
    AssistChip(onClick = {}, label = { Text(status.label(), style = MaterialTheme.typography.labelSmall) })
}

/** Minimal Markdown renderer for the summaries (headings, bullets, bold). */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.padding(vertical = 3.dp))
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(
                        inline(line.drop(level).trim()),
                        style = when (level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = line.length - line.trimStart().length
                    Row(Modifier.padding(start = (indent * 6).dp, bottom = 2.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(8.dp))
                        Text(inline(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                NUMBERED.matches(line.trimStart()) -> {
                    val indent = line.length - line.trimStart().length
                    Text(
                        inline(line.trimStart()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = (indent * 6).dp, bottom = 2.dp),
                    )
                }
                else -> Text(inline(line), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

private val NUMBERED = Regex("^\\d+[.)] .*")

private fun inline(text: String) = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}
