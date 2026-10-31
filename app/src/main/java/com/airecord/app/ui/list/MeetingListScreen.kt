package com.airecord.app.ui.list

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airecord.app.audio.AudioImporter
import com.airecord.app.container
import com.airecord.app.core.TimeFormat
import com.airecord.app.data.Meeting
import com.airecord.app.data.MeetingStatus
import com.airecord.app.models.ModelKind
import com.airecord.app.service.MeetingService
import com.airecord.app.service.ProcessingTask
import com.airecord.app.service.ServiceStatus
import com.airecord.app.ui.common.StatusChip
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingListScreen(
    onOpenMeeting: (String) -> Unit,
    onOpenRecorder: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val meetings by container.meetings.meetings.collectAsStateWithLifecycle()
    val recorder by ServiceStatus.recorder.collectAsStateWithLifecycle()
    val processing by ServiceStatus.processing.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val installed by container.models.installed.collectAsStateWithLifecycle()

    val sttReady = container.models.file(ModelKind.WHISPER, settings.whisperModel) != null &&
        installed[ModelKind.WHISPER].orEmpty().isNotEmpty()
    val llmReady = container.models.file(ModelKind.LLM, settings.llmModel) != null &&
        installed[ModelKind.LLM].orEmpty().isNotEmpty()

    fun startNewRecording() {
        val title = "회의 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))
        val meeting = container.meetings.create(title, MeetingStatus.RECORDING)
        MeetingService.startRecording(context, meeting.id)
        onOpenRecorder(meeting.id)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startNewRecording()
        else Toast.makeText(context, "녹음하려면 마이크 권한이 필요합니다", Toast.LENGTH_LONG).show()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.') ?: "가져온 오디오"
        val meeting = container.meetings.create(name, MeetingStatus.IMPORTING)
        container.appScope.launch {
            runCatching {
                AudioImporter.importToWav(context, uri, container.meetings.audioFile(meeting.id)) {}
            }.onSuccess { duration ->
                container.meetings.update(meeting.id) { it.copy(status = MeetingStatus.RECORDED, durationMs = duration) }
                if (container.settings.state.value.autoProcess) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        MeetingService.enqueue(context, meeting.id, ProcessingTask.FULL)
                    }
                }
            }.onFailure { t ->
                container.meetings.update(meeting.id) { it.copy(status = MeetingStatus.ERROR, error = "가져오기 실패: ${t.message}") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 회의록") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "오디오 파일 가져오기")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "설정") }
                },
            )
        },
        floatingActionButton = {
            if (!recorder.active) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val perms = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) startNewRecording() else permissionLauncher.launch(perms.toTypedArray())
                    },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    text = { Text("녹음 시작") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!sttReady || !llmReady) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("AI 모델 설정이 필요합니다", style = MaterialTheme.typography.titleSmall)
                            Text(
                                buildString {
                                    if (!sttReady) append("· 음성 인식(Whisper) 모델 없음\n")
                                    if (!llmReady) append("· 요약 AI(LLM) 모델 없음\n")
                                    append("모든 처리는 기기 안에서만 이루어지며 인터넷으로 음성을 보내지 않습니다.")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onOpenSettings) { Text("모델 설정하기") }
                        }
                    }
                }
            }
            if (recorder.active && recorder.meetingId != null) {
                item {
                    Card(
                        onClick = { onOpenRecorder(recorder.meetingId!!) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                (if (recorder.paused) "일시정지 " else "녹음 중 ") + TimeFormat.duration(recorder.elapsedMs),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            if (meetings.isEmpty()) {
                item {
                    Text(
                        "아직 회의가 없습니다.\n아래 '녹음 시작'을 눌러 첫 회의를 녹음하세요.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    )
                }
            }
            items(meetings, key = { it.id }) { m ->
                MeetingCard(
                    meeting = m,
                    progress = processing?.takeIf { it.meetingId == m.id }?.progress,
                    isProcessing = processing?.meetingId == m.id,
                    onClick = { onOpenMeeting(m.id) },
                )
            }
        }
    }
}

@Composable
private fun MeetingCard(meeting: Meeting, progress: Float?, isProcessing: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(meeting.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${TimeFormat.dateTime(meeting.createdAt)} · ${TimeFormat.duration(meeting.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(meeting.status)
            }
            val preview = meeting.summary?.lines()?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: meeting.error
            if (preview != null) {
                Text(
                    preview.removePrefix("- "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (meeting.status == MeetingStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (isProcessing) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}
