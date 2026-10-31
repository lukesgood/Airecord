package com.airecord.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val whisperModel: String? = null,
    val llmModel: String? = null,
    /** Whisper language code, or "auto". */
    val language: String = "ko",
    /** Language the summary is written in: "ko" or "en". */
    val summaryLanguage: String = "ko",
    val threads: Int = defaultThreads(),
    val useGpu: Boolean = true,
    val llmMaxTokens: Int = 4096,
    val autoProcess: Boolean = true,
    /** Names / jargon passed to Whisper as an initial prompt to improve recognition. */
    val vocabularyHint: String = "",
) {
    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it > 4) it - 2 else it }
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) = synchronized(this) {
        val next = transform(_state.value)
        prefs.edit()
            .putString("whisperModel", next.whisperModel)
            .putString("llmModel", next.llmModel)
            .putString("language", next.language)
            .putString("summaryLanguage", next.summaryLanguage)
            .putInt("threads", next.threads)
            .putBoolean("useGpu", next.useGpu)
            .putInt("llmMaxTokens", next.llmMaxTokens)
            .putBoolean("autoProcess", next.autoProcess)
            .putString("vocabularyHint", next.vocabularyHint)
            .apply()
        _state.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            whisperModel = prefs.getString("whisperModel", d.whisperModel),
            llmModel = prefs.getString("llmModel", d.llmModel),
            language = prefs.getString("language", d.language) ?: d.language,
            summaryLanguage = prefs.getString("summaryLanguage", d.summaryLanguage) ?: d.summaryLanguage,
            threads = prefs.getInt("threads", d.threads),
            useGpu = prefs.getBoolean("useGpu", d.useGpu),
            llmMaxTokens = prefs.getInt("llmMaxTokens", d.llmMaxTokens),
            autoProcess = prefs.getBoolean("autoProcess", d.autoProcess),
            vocabularyHint = prefs.getString("vocabularyHint", d.vocabularyHint) ?: d.vocabularyHint,
        )
    }
}
