package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 聊天记录存成手机里的一个 JSON 文件，设置存在 SharedPreferences。 */
class Store(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "conversations.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun loadConversations(): List<Conversation> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(ListSerializer(Conversation.serializer()), file.readText())
    }.getOrDefault(emptyList())

    fun saveConversations(list: List<Conversation>) {
        val tmp = File(file.parentFile, "conversations.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Conversation.serializer()), list))
        tmp.renameTo(file)
    }

    fun loadSettings(): Settings {
        val presets = prefs.getString("presets", null)?.let {
            runCatching { json.decodeFromString(ListSerializer(Preset.serializer()), it) }.getOrNull()
        } ?: DEFAULT_PRESETS
        return Settings(
            apiKey = prefs.getString("apiKey", "") ?: "",
            baseUrl = prefs.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            model = prefs.getString("model", MODEL_CHAT) ?: MODEL_CHAT,
            temperature = prefs.getFloat("temperature", 1.0f),
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "SYSTEM")!!) }
                .getOrDefault(ThemeMode.SYSTEM),
            presets = presets,
        )
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("apiKey", s.apiKey)
            .putString("baseUrl", s.baseUrl)
            .putString("model", s.model)
            .putFloat("temperature", s.temperature)
            .putString("themeMode", s.themeMode.name)
            .putString("presets", json.encodeToString(ListSerializer(Preset.serializer()), s.presets))
            .apply()
    }
}
