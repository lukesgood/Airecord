package com.airecord.app.data

import com.airecord.app.core.Wav
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Stores each meeting as `<root>/<id>/meeting.json` plus `<root>/<id>/audio.wav`.
 * Everything stays on the device.
 */
class MeetingRepository(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _meetings = MutableStateFlow<List<Meeting>>(emptyList())
    val meetings: StateFlow<List<Meeting>> = _meetings.asStateFlow()

    init {
        root.mkdirs()
        val loaded = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            runCatching { json.decodeFromString<Meeting>(File(dir, META).readText()) }.getOrNull()
        }
        // Work that was in progress when the process died can never finish: mark it.
        val recovered = loaded.map { m ->
            val fixed = recoverInterrupted(m)
            if (fixed != m) write(fixed)
            fixed
        }
        _meetings.value = recovered.sortedByDescending { it.createdAt }
    }

    fun dir(id: String) = File(root, id)
    fun audioFile(id: String) = File(dir(id), AUDIO)

    fun get(id: String): Meeting? = _meetings.value.firstOrNull { it.id == id }

    fun observe(id: String): Flow<Meeting?> =
        meetings.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    fun create(title: String, status: MeetingStatus, now: Long = System.currentTimeMillis()): Meeting =
        synchronized(lock) {
            val m = Meeting(id = UUID.randomUUID().toString(), title = title, createdAt = now, status = status)
            dir(m.id).mkdirs()
            write(m)
            _meetings.value = (listOf(m) + _meetings.value).sortedByDescending { it.createdAt }
            m
        }

    fun update(id: String, transform: (Meeting) -> Meeting): Meeting? = synchronized(lock) {
        val current = get(id) ?: return null
        val updated = transform(current)
        if (updated != current) {
            write(updated)
            _meetings.value = _meetings.value.map { if (it.id == id) updated else it }
        }
        updated
    }

    fun delete(id: String) = synchronized(lock) {
        dir(id).deleteRecursively()
        _meetings.value = _meetings.value.filterNot { it.id == id }
    }

    private fun write(m: Meeting) {
        val dir = dir(m.id).apply { mkdirs() }
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(json.encodeToString(Meeting.serializer(), m))
        if (!tmp.renameTo(File(dir, META))) {
            File(dir, META).writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun recoverInterrupted(m: Meeting): Meeting = when (m.status) {
        MeetingStatus.RECORDING -> {
            val audio = audioFile(m.id)
            Wav.fixHeader(audio)
            m.copy(status = MeetingStatus.RECORDED, durationMs = Wav.durationMs(audio))
        }
        MeetingStatus.IMPORTING, MeetingStatus.TRANSCRIBING, MeetingStatus.SUMMARIZING ->
            m.copy(status = MeetingStatus.ERROR, error = "처리가 중단되었습니다. 다시 시도하세요.")
        else -> m
    }

    companion object {
        private const val META = "meeting.json"
        private const val AUDIO = "audio.wav"
    }
}
