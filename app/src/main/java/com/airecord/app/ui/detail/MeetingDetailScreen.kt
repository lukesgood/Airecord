package com.airecord.app.ui.detail

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airecord.app.container
import com.airecord.app.core.MeetingExporter
import com.airecord.app.core.TimeFormat
import com.airecord.app.data.Meeting
import com.airecord.app.data.MeetingStatus
import com.airecord.app.service.MeetingService
import com.airecord.app.service.ProcessingTask
import com.airecord.app.service.ServiceStatus
import com.airecord.app.share.ShareHelper
import com.airecord.app.ui.common.MarkdownText
import com.airecord.app.ui.common.StatusChip
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailScreen(meetingId: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.meetings
    val meeting by repo.observe(meetingId).collectAsStateWithLifecycle(repo.get(meetingId))
    val processing by ServiceStatus.processing.collectAsStateWithLifecycle()
    val queue by ServiceStatus.queue.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var shareMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var editingSummary by rememberSaveable { mutableStateOf(false) }
    var summaryDraft by rememberSaveable { mutableStateOf("") }

    val player = remember { AudioPlayerState() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val m = meeting ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) ShareHelper.saveTo(context, m, uri)
    }

    val current = processing?.takeIf { it.meetingId == m.id }
    val isQueued = m.id in queue
    val busy = current != null || isQueued || m.status == MeetingStatus.RECORDING || m.status == MeetingStatus.IMPORTING

    fun enqueue(task: ProcessingTask) = MeetingService.enqueue(context, m.id, task)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { shareMenu = true }) { Icon(Icons.Default.Share, contentDescription = "공유") }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            ShareItem("회의록 공유 (텍스트)", Icons.Default.Share) { shareMenu = false; ShareHelper.shareText(context, m, false) }
                            ShareItem("회의록 + 녹취록 공유", Icons.AutoMirrored.Filled.Subject) { shareMenu = false; ShareHelper.shareText(context, m, true) }
                            ShareItem("파일(.md)로 공유", Icons.AutoMirrored.Filled.InsertDriveFile) { shareMenu = false; ShareHelper.shareMarkdownFile(context, m) }
                            ShareItem("이메일로 보내기", Icons.Default.Email) { shareMenu = false; ShareHelper.email(context, m) }
                            ShareItem("녹음 파일 공유", Icons.Default.AudioFile) { shareMenu = false; ShareHelper.shareAudio(context, m) }
                            ShareItem("회의록 복사", Icons.Default.ContentCopy) { shareMenu = false; ShareHelper.copyToClipboard(context, m) }
                            ShareItem("기기에 저장", Icons.Default.Save) { shareMenu = false; saveLauncher.launch(MeetingExporter.fileName(m, "md")) }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "더보기") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("회의 정보 편집") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { moreMenu = false; infoDialog = true })
                            DropdownMenuItem(text = { Text("다시 전사 + 요약") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                enabled = !busy, onClick = { moreMenu = false; enqueue(ProcessingTask.FULL) })
                            DropdownMenuItem(text = { Text("다시 요약") }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                enabled = !busy && m.segments.isNotEmpty(), onClick = { moreMenu = false; enqueue(ProcessingTask.SUMMARIZE) })
                            DropdownMenuItem(text = { Text("삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                enabled = !busy, onClick = { moreMenu = false; deleteDialog = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${TimeFormat.dateTime(m.createdAt)} · ${TimeFormat.duration(m.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(m.status)
                }
            }

            if (current != null || isQueued) {
                item {
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(current?.stage?.label ?: "처리 대기 중", style = MaterialTheme.typography.titleSmall)
                            val detail = listOfNotNull(
                                current?.progress?.let { "${(it * 100).toInt()}%" },
                                current?.detail?.takeIf { it.isNotEmpty() },
                            ).joinToString(" · ")
                            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                            val p = current?.progress
                            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            TextButton(onClick = { MeetingService.cancel(context, m.id) }) { Text("취소") }
                        }
                    }
                }
            }

            if (m.error != null && current == null && !isQueued) {
                item {
                    Card(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(m.error!!, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (m.error!!.contains("모델")) TextButton(onClick = onOpenSettings) { Text("설정 열기") }
                                TextButton(onClick = {
                                    enqueue(if (m.segments.isNotEmpty()) ProcessingTask.SUMMARIZE else ProcessingTask.FULL)
                                }) { Text("다시 시도") }
                            }
                        }
                    }
                }
            }

            if (m.status != MeetingStatus.RECORDING && m.status != MeetingStatus.IMPORTING) {
                item { AudioPlayerBar(player, context.container.meetings.audioFile(m.id), m.durationMs) }
            }

            item {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("회의록") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("녹취록 (${m.segments.size})") })
                }
            }

            if (tab == 0) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        when {
                            editingSummary -> {
                                OutlinedTextField(
                                    value = summaryDraft,
                                    onValueChange = { summaryDraft = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Button(onClick = {
                                        repo.update(m.id) { it.copy(summary = summaryDraft) }
                                        editingSummary = false
                                    }) { Text("저장") }
                                    OutlinedButton(onClick = { editingSummary = false }) { Text("취소") }
                                }
                            }
                            m.summary != null -> {
                                MarkdownText(MeetingExporter.summary(m))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                                    OutlinedButton(onClick = { summaryDraft = m.summary!!; editingSummary = true }) {
                                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("본문 수정")
                                    }
                                    OutlinedButton(onClick = { infoDialog = true }) { Text("회의 정보") }
                                    Button(onClick = { ShareHelper.shareText(context, m, false) }) {
                                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("공유")
                                    }
                                }
                                m.llmBackend?.let {
                                    Text(
                                        "요약 모델: ${m.llmModel ?: "-"} · $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }
                            }
                            m.segments.isNotEmpty() && !busy -> {
                                Text("아직 회의록이 없습니다.", style = MaterialTheme.typography.bodyMedium)
                                if (m.attendees.isBlank()) {
                                    Text(
                                        "참석자를 입력하면 발언자·담당자 이름이 회의록에 반영됩니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    OutlinedButton(onClick = { infoDialog = true }) { Text("회의 정보 입력") }
                                    Button(onClick = { enqueue(ProcessingTask.SUMMARIZE) }) {
                                        Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("AI 회의록 작성")
                                    }
                                }
                            }
                            m.status == MeetingStatus.RECORDED && !busy -> {
                                Text("아직 전사하지 않았습니다.", style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { enqueue(ProcessingTask.FULL) }, modifier = Modifier.padding(top = 8.dp)) {
                                    Text("전사 + 요약 시작")
                                }
                            }
                            else -> Text("회의록을 준비하고 있습니다…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                if (m.segments.isEmpty()) {
                    item { Text("녹취록이 없습니다.", modifier = Modifier.padding(16.dp)) }
                }
                itemsIndexed(m.segments) { i, s ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { player.playFrom(context.container.meetings.audioFile(m.id), s.startMs) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            TimeFormat.duration(s.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(s.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (i < m.segments.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (infoDialog) {
        var title by remember { mutableStateOf(m.title) }
        var location by remember { mutableStateOf(m.location) }
        var attendees by remember { mutableStateOf(m.attendees) }
        AlertDialog(
            onDismissRequest = { infoDialog = false },
            title = { Text("회의 정보") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("회의명") }, singleLine = true)
                    OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("장소") }, singleLine = true)
                    OutlinedTextField(
                        value = attendees, onValueChange = { attendees = it },
                        label = { Text("참석자 (쉼표로 구분)") }, placeholder = { Text("예: 김팀장, 이대리, 박주임") },
                    )
                    Text(
                        "회의록 머리말에 들어갑니다. 참석자 이름은 다시 요약할 때 AI가 발언자·담당자로도 사용합니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.update(m.id) {
                        it.copy(title = title.trim().ifEmpty { it.title }, location = location.trim(), attendees = attendees.trim())
                    }
                    infoDialog = false
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { infoDialog = false }) { Text("취소") } },
        )
    }
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("회의 삭제") },
            text = { Text("녹음, 녹취록, 요약이 모두 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    player.release()
                    repo.delete(m.id) // the screen pops itself once the meeting disappears
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun ShareItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** Small wrapper around MediaPlayer that exposes Compose state. */
private class AudioPlayerState {
    private var player: MediaPlayer? = null
    private var loadedPath: String? = null
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableFloatStateOf(0f)

    private fun ensure(file: java.io.File): MediaPlayer? {
        if (!file.exists()) return null
        if (player == null || loadedPath != file.absolutePath) {
            release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { this@AudioPlayerState.isPlaying = false }
            }
            loadedPath = file.absolutePath
        }
        return player
    }

    fun toggle(file: java.io.File) {
        val p = ensure(file) ?: return
        if (p.isPlaying) p.pause() else p.start()
        isPlaying = p.isPlaying
    }

    fun playFrom(file: java.io.File, ms: Long) {
        val p = ensure(file) ?: return
        p.seekTo(ms.toInt())
        positionMs = ms.toFloat()
        p.start()
        isPlaying = true
    }

    fun seek(ms: Float) {
        player?.seekTo(ms.toInt())
        positionMs = ms
    }

    fun poll() {
        player?.let { if (it.isPlaying) positionMs = it.currentPosition.toFloat() }
    }

    fun release() {
        player?.release()
        player = null
        loadedPath = null
        isPlaying = false
    }
}

@Composable
private fun AudioPlayerBar(state: AudioPlayerState, file: java.io.File, durationMs: Long) {
    if (!file.exists() || durationMs <= 0) return
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            state.poll()
            delay(250)
        }
    }
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { state.toggle(file) }) {
            Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "재생")
        }
        Slider(
            value = state.positionMs.coerceIn(0f, durationMs.toFloat()),
            onValueChange = { state.seek(it) },
            valueRange = 0f..durationMs.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            TimeFormat.duration(state.positionMs.toLong()),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
