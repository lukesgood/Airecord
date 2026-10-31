package com.airecord.app.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airecord.app.container
import com.airecord.app.core.TimeFormat
import com.airecord.app.data.MeetingStatus
import com.airecord.app.service.MeetingService
import com.airecord.app.service.ServiceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(meetingId: String, onBack: () -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val recorder by ServiceStatus.recorder.collectAsStateWithLifecycle()
    val meeting by context.container.meetings.observe(meetingId).collectAsStateWithLifecycle(null)
    var finished by remember { mutableStateOf(false) }

    // Leave once recording for this meeting has ended (stopped here, from the notification, or failed).
    LaunchedEffect(recorder.active, recorder.meetingId, meeting?.status) {
        val recordingThis = recorder.active && recorder.meetingId == meetingId
        val m = meeting
        if (!finished && !recordingThis && m != null && m.status != MeetingStatus.RECORDING) {
            finished = true
            onFinished()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meeting?.title ?: "녹음") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (recorder.paused) "일시정지됨" else "녹음 중",
                style = MaterialTheme.typography.titleMedium,
                color = if (recorder.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Text(TimeFormat.duration(recorder.elapsedMs), fontSize = 56.sp, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(24.dp))
            LinearProgressIndicator(
                progress = { recorder.level },
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )
            Text("입력 레벨", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(
                    onClick = { if (recorder.paused) MeetingService.resume(context) else MeetingService.pause(context) },
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        if (recorder.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (recorder.paused) "계속" else "일시정지",
                        modifier = Modifier.size(36.dp),
                    )
                }
                FilledIconButton(
                    onClick = { MeetingService.stopRecording(context) },
                    modifier = Modifier.size(96.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "녹음 종료", modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "화면을 꺼도 녹음은 계속됩니다.\n종료하면 기기 안에서 자동으로 전사·요약합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
