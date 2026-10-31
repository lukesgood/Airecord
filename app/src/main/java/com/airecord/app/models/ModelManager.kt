package com.airecord.app.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class ModelKind(val dirName: String) { WHISPER("whisper"), LLM("llm") }

data class WhisperPreset(val fileName: String, val label: String, val sizeMb: Int, val note: String) {
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

data class DownloadState(
    val fileName: String,
    val kind: ModelKind,
    val bytes: Long = 0,
    val total: Long = -1,
    val error: String? = null,
) {
    val progress: Float? get() = if (total > 0) bytes.toFloat() / total else null
}

/** Downloads, imports and lists model files stored in app-private storage. */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {

    val whisperPresets = listOf(
        WhisperPreset("ggml-base.bin", "Base", 142, "빠름 · 정확도 보통"),
        WhisperPreset("ggml-small.bin", "Small", 466, "균형 · 한국어 권장 최소"),
        WhisperPreset("ggml-medium-q5_0.bin", "Medium (Q5)", 514, "정확 · 느림"),
        WhisperPreset("ggml-large-v3-turbo-q5_0.bin", "Large v3 Turbo (Q5)", 547, "가장 정확 · 고사양 기기 권장"),
    )

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Map<ModelKind, List<File>>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun dir(kind: ModelKind) = File(context.filesDir, "models/${kind.dirName}").apply { mkdirs() }

    fun file(kind: ModelKind, name: String?): File? =
        name?.let { File(dir(kind), it) }?.takeIf { it.isFile && it.length() > 0 }

    private fun scan(): Map<ModelKind, List<File>> = ModelKind.entries.associateWith { kind ->
        dir(kind).listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().sortedBy { it.name }
    }

    private fun refresh() { _installed.value = scan() }

    fun download(kind: ModelKind, url: String, fileName: String, bearerToken: String? = null, onDone: (File) -> Unit = {}) {
        if (jobs[fileName]?.isActive == true) return
        _downloads.update { it + (fileName to DownloadState(fileName, kind)) }
        jobs[fileName] = scope.launch(Dispatchers.IO) {
            val target = File(dir(kind), fileName)
            val part = File(dir(kind), "$fileName.part")
            try {
                var conn = open(url, bearerToken)
                // Follow cross-host redirects (HuggingFace -> CDN) manually.
                var hops = 0
                while (conn.responseCode in 300..399 && hops++ < 5) {
                    val next = URL(URL(url), conn.getHeaderField("Location")).toString()
                    conn.disconnect()
                    conn = open(next, if (URL(next).host == URL(url).host) bearerToken else null)
                }
                if (conn.responseCode == 401 || conn.responseCode == 403) {
                    error("접근 거부(${conn.responseCode}) — 라이선스 동의 또는 액세스 토큰이 필요한 모델입니다")
                }
                check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                _downloads.update { it + (fileName to DownloadState(fileName, kind, done, total)) }
                            }
                        }
                    }
                }
                conn.disconnect()
                check(part.renameTo(target)) { "파일 저장 실패" }
                _downloads.update { it - fileName }
                refresh()
                withContext(Dispatchers.Main) { onDone(target) }
            } catch (t: Throwable) {
                part.delete()
                if (t is kotlinx.coroutines.CancellationException) {
                    _downloads.update { it - fileName }
                } else {
                    _downloads.update { it + (fileName to DownloadState(fileName, kind, error = t.message ?: t.toString())) }
                }
            }
        }
    }

    private fun open(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
            connect()
        }

    fun cancelDownload(fileName: String) {
        jobs.remove(fileName)?.cancel()
        _downloads.update { it - fileName }
    }

    fun dismissError(fileName: String) = _downloads.update { it - fileName }

    /** Copies a user-picked file (e.g. a Gemma `.task` from Downloads) into app storage. */
    suspend fun import(kind: ModelKind, uri: Uri): File = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "model_${System.currentTimeMillis()}"
        val target = File(dir(kind), name)
        val part = File(dir(kind), "$name.part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "파일을 열 수 없습니다" }
            part.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        check(part.renameTo(target)) { "파일 저장 실패" }
        refresh()
        target
    }

    fun delete(kind: ModelKind, file: File) {
        if (file.parentFile == dir(kind)) file.delete()
        refresh()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace(Regex("[\\\\/]"), "_")
}
