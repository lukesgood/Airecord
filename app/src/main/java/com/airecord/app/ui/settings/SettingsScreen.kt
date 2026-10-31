package com.airecord.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airecord.app.container
import com.airecord.app.models.DownloadState
import com.airecord.app.models.ModelKind
import com.airecord.app.stt.WhisperLib
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val models = container.models
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val installed by models.installed.collectAsStateWithLifecycle()
    val downloads by models.downloads.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<ModelKind?>(null) }

    fun importInto(kind: ModelKind, uri: android.net.Uri?) {
        if (uri == null) return
        importing = kind
        scope.launch {
            runCatching { models.import(kind, uri) }
                .onSuccess { f ->
                    container.settings.update {
                        if (kind == ModelKind.WHISPER) it.copy(whisperModel = f.name) else it.copy(llmModel = f.name)
                    }
                    Toast.makeText(context, "${f.name} 가져오기 완료", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(context, "가져오기 실패: ${it.message}", Toast.LENGTH_LONG).show() }
            importing = null
        }
    }

    val importWhisper = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importInto(ModelKind.WHISPER, it) }
    val importLlm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importInto(ModelKind.LLM, it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정 · AI 모델") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Whisper ---------------------------------------------------------------
            Section("음성 인식 모델 (Whisper)", "녹음을 기기 안에서 텍스트로 변환합니다. 한국어는 Small 이상을 권장합니다.") {
                models.whisperPresets.forEach { preset ->
                    val file = installed[ModelKind.WHISPER].orEmpty().firstOrNull { it.name == preset.fileName }
                    ModelRow(
                        title = "${preset.label} · ${preset.sizeMb}MB",
                        subtitle = preset.note,
                        selected = settings.whisperModel == preset.fileName && file != null,
                        installed = file != null,
                        download = downloads[preset.fileName],
                        onSelect = { container.settings.update { it.copy(whisperModel = preset.fileName) } },
                        onDownload = {
                            models.download(ModelKind.WHISPER, preset.url, preset.fileName) { f ->
                                if (container.settings.state.value.whisperModel == null) {
                                    container.settings.update { it.copy(whisperModel = f.name) }
                                }
                            }
                        },
                        onCancel = { models.cancelDownload(preset.fileName) },
                        onDismissError = { models.dismissError(preset.fileName) },
                        onDelete = { file?.let { models.delete(ModelKind.WHISPER, it) } },
                    )
                }
                installed[ModelKind.WHISPER].orEmpty()
                    .filter { f -> models.whisperPresets.none { it.fileName == f.name } }
                    .forEach { f -> InstalledRow(f, settings.whisperModel == f.name,
                        onSelect = { container.settings.update { it.copy(whisperModel = f.name) } },
                        onDelete = { models.delete(ModelKind.WHISPER, f) }) }
                OutlinedButton(onClick = { importWhisper.launch(arrayOf("*/*")) }, enabled = importing == null) {
                    Text(if (importing == ModelKind.WHISPER) "가져오는 중…" else "ggml .bin 파일 가져오기")
                }
            }

            // ---- LLM ------------------------------------------------------------------
            Section(
                "요약 AI 모델 (LLM)",
                "MediaPipe LLM Inference용 .task 모델(예: Gemma 3 1B IT int4)을 사용하며 GPU로 실행됩니다. " +
                    "Gemma 모델은 라이선스 동의가 필요하므로 Kaggle/Hugging Face에서 받은 파일을 가져오거나, " +
                    "아래에 다운로드 URL과 액세스 토큰을 입력하세요.",
            ) {
                installed[ModelKind.LLM].orEmpty().forEach { f ->
                    InstalledRow(f, settings.llmModel == f.name,
                        onSelect = { container.settings.update { it.copy(llmModel = f.name) } },
                        onDelete = { models.delete(ModelKind.LLM, f) })
                }
                if (installed[ModelKind.LLM].orEmpty().isEmpty()) {
                    Text("설치된 요약 모델이 없습니다.", style = MaterialTheme.typography.bodySmall)
                }
                downloads.values.filter { it.kind == ModelKind.LLM }.forEach { d ->
                    DownloadProgress(d, onCancel = { models.cancelDownload(d.fileName) }, onDismiss = { models.dismissError(d.fileName) })
                }
                OutlinedButton(onClick = { importLlm.launch(arrayOf("*/*")) }, enabled = importing == null) {
                    Text(if (importing == ModelKind.LLM) "가져오는 중…" else ".task 파일 가져오기")
                }
                var url by remember { mutableStateOf("") }
                var token by remember { mutableStateOf("") }
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("모델 다운로드 URL (.task)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("액세스 토큰 (선택, Hugging Face)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    enabled = url.startsWith("https://"),
                    onClick = {
                        val name = url.substringBefore('?').substringAfterLast('/').ifEmpty { "model.task" }
                        models.download(ModelKind.LLM, url.trim(), name, token) { f ->
                            container.settings.update { it.copy(llmModel = f.name) }
                        }
                    },
                ) { Icon(Icons.Default.Download, null); Text(" 다운로드") }
            }

            // ---- Options --------------------------------------------------------------
            Section("처리 옵션", null) {
                ChoiceRow("인식 언어", settings.language, listOf("ko" to "한국어", "en" to "영어", "ja" to "일본어", "auto" to "자동")) { v ->
                    container.settings.update { it.copy(language = v) }
                }
                ChoiceRow("요약 언어", settings.summaryLanguage, listOf("ko" to "한국어", "en" to "English")) { v ->
                    container.settings.update { it.copy(summaryLanguage = v) }
                }
                ChoiceRow("요약 컨텍스트(토큰)", settings.llmMaxTokens.toString(), listOf("2048" to "2K", "4096" to "4K", "8192" to "8K")) { v ->
                    container.settings.update { it.copy(llmMaxTokens = v.toInt()) }
                }
                SwitchRow("GPU 가속 사용", "요약 AI를 GPU로 실행합니다. 실패하면 자동으로 CPU를 사용합니다.", settings.useGpu) { v ->
                    container.settings.update { it.copy(useGpu = v) }
                }
                SwitchRow("녹음 종료 후 자동 처리", "녹음이 끝나면 바로 전사와 요약을 시작합니다.", settings.autoProcess) { v ->
                    container.settings.update { it.copy(autoProcess = v) }
                }
                Text("음성 인식 CPU 스레드: ${settings.threads}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.threads.toFloat(),
                    onValueChange = { v -> container.settings.update { it.copy(threads = v.toInt()) } },
                    valueRange = 1f..8f, steps = 6,
                )
                var hint by remember { mutableStateOf(settings.vocabularyHint) }
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it; container.settings.update { s -> s.copy(vocabularyHint = it) } },
                    label = { Text("용어 힌트 (사람 이름, 제품명, 전문 용어)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("기기 정보", null) {
                Text(
                    if (WhisperLib.isAvailable) WhisperLib.systemInfo() else "네이티브 라이브러리 로드 실패: ${WhisperLib.loadError?.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, description: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    installed: Boolean,
    download: DownloadState?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSelect, enabled = installed) {
                Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = "선택")
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                download != null && download.error == null -> IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "취소") }
                installed -> IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "삭제") }
                else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "다운로드") }
            }
        }
        if (download != null) DownloadProgress(download, onCancel, onDismissError)
    }
}

@Composable
private fun DownloadProgress(d: DownloadState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (d.error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${d.fileName}: ${d.error}", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
        return
    }
    val p = d.progress
    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        "${d.fileName} · ${d.bytes / 1_000_000}MB" + (if (d.total > 0) " / ${d.total / 1_000_000}MB" else ""),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun InstalledRow(file: File, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSelect) {
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = "선택")
        }
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            Text("${file.length() / 1_000_000}MB", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "삭제") }
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, text) ->
                FilterChip(selected = value == key, onClick = { onChange(key) }, label = { Text(text) })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
