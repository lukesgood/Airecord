package com.airecord.app.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.airecord.app.container
import com.airecord.app.core.MeetingExporter
import com.airecord.app.data.Meeting
import java.io.File

/** Sends meeting summaries / transcripts / audio to other apps. */
object ShareHelper {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** Plain text via the system share sheet (KakaoTalk, Slack, Messages, Notes, Gmail…). */
    fun shareText(context: Context, meeting: Meeting, includeTranscript: Boolean) {
        val text = if (includeTranscript) MeetingExporter.full(meeting) else MeetingExporter.summary(meeting)
        ShareCompat.IntentBuilder(context)
            .setType("text/plain")
            .setSubject("[회의록] ${meeting.title}")
            .setText(text)
            .setChooserTitle("회의록 공유")
            .startChooser()
    }

    /** Summary + transcript as a Markdown file attachment (Drive, Notion, email, messengers…). */
    fun shareMarkdownFile(context: Context, meeting: Meeting) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, MeetingExporter.fileName(meeting, "md"))
        file.writeText(MeetingExporter.full(meeting))
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        ShareCompat.IntentBuilder(context)
            .setType("text/markdown")
            .setSubject("[회의록] ${meeting.title}")
            .setText(MeetingExporter.summary(meeting))
            .setStream(uri)
            .setChooserTitle("회의록 파일 공유")
            .startChooser()
    }

    fun shareAudio(context: Context, meeting: Meeting) {
        val audio = context.container.meetings.audioFile(meeting.id)
        if (!audio.exists()) {
            Toast.makeText(context, "녹음 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, authority(context), audio)
        ShareCompat.IntentBuilder(context)
            .setType("audio/wav")
            .setSubject("[녹음] ${meeting.title}")
            .setStream(uri)
            .setChooserTitle("녹음 파일 공유")
            .startChooser()
    }

    /** Opens only e-mail apps, with subject and body pre-filled. */
    fun email(context: Context, meeting: Meeting) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_SUBJECT, "[회의록] ${meeting.title}")
            .putExtra(Intent.EXTRA_TEXT, MeetingExporter.full(meeting))
        runCatching { context.startActivity(Intent.createChooser(intent, "이메일로 보내기")) }
            .onFailure { Toast.makeText(context, "이메일 앱이 없습니다", Toast.LENGTH_SHORT).show() }
    }

    fun copyToClipboard(context: Context, meeting: Meeting) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(meeting.title, MeetingExporter.summary(meeting)))
        Toast.makeText(context, "회의록을 클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    /** Writes the full Markdown to a location the user picked with ACTION_CREATE_DOCUMENT. */
    fun saveTo(context: Context, meeting: Meeting, uri: Uri) {
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(MeetingExporter.full(meeting).toByteArray()) }
        }.onSuccess { Toast.makeText(context, "저장했습니다", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "저장 실패: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}
