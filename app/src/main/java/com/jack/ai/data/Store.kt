package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val LOCK = Any()

/** 聊天记录和项目存成手机里的 JSON 文件，设置存在 SharedPreferences。 */
class Store(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun <T> load(name: String, ser: KSerializer<T>): List<T> = synchronized(LOCK) { loadUnlocked(name, ser) }

    private fun <T> loadUnlocked(name: String, ser: KSerializer<T>): List<T> = runCatching {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        json.decodeFromString(ListSerializer(ser), f.readText())
    }.getOrDefault(emptyList())

    private fun <T> save(name: String, ser: KSerializer<T>, list: List<T>) = synchronized(LOCK) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(ser), list))
        tmp.renameTo(File(dir, name))
    }

    fun loadConversations() = load("conversations.json", Conversation.serializer())
    fun saveConversations(list: List<Conversation>) = save("conversations.json", Conversation.serializer(), list)
    fun loadProjects() = load("projects.json", Project.serializer())
    fun saveProjects(list: List<Project>) = save("projects.json", Project.serializer(), list)

    fun loadMemories() = load("memories.json", MemoryItem.serializer())
    fun saveMemories(list: List<MemoryItem>) = save("memories.json", MemoryItem.serializer(), list)
    fun loadDreams() = load("dreams.json", DreamEntry.serializer())
    fun saveDreams(list: List<DreamEntry>) = save("dreams.json", DreamEntry.serializer(), list)

    var lastDreamAt: Long
        get() = prefs.getLong("lastDreamAt", 0L)
        set(v) { prefs.edit().putLong("lastDreamAt", v).apply() }

    fun loadSettings(): Settings {
        val presets = prefs.getString("presets", null)?.let {
            runCatching { json.decodeFromString(ListSerializer(Preset.serializer()), it) }.getOrNull()
        } ?: DEFAULT_PRESETS
        // 旧版用的 deepseek-chat / deepseek-reasoner 已经下线，自动换成新模型
        val oldModel = prefs.getString("model", MODEL_FLASH) ?: MODEL_FLASH
        val model = when (oldModel) {
            "deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash" -> MODEL_FLASH
            else -> oldModel
        }
        val thinking = prefs.getString("thinking", null)
            ?.let { runCatching { ThinkLevel.valueOf(it) }.getOrNull() }
            ?: if (oldModel == "deepseek-chat") ThinkLevel.OFF else ThinkLevel.HIGH
        return Settings(
            apiKey = prefs.getString("apiKey", "") ?: "",
            tavilyKey = prefs.getString("tavilyKey", "") ?: "",
            webSearch = prefs.getBoolean("webSearch2", true),
            baseUrl = prefs.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            model = model,
            thinking = thinking,
            temperature = prefs.getFloat("temperature", 1.0f),
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "SYSTEM")!!) }
                .getOrDefault(ThemeMode.SYSTEM),
            presets = presets,
            developerPrompt = prefs.getString("devPrompt2", null) ?: DEFAULT_DEV_PROMPT,
            compressTokens = prefs.getInt("compressTokens", 200_000),
            memoryEnabled = prefs.getBoolean("memoryEnabled", true),
            dreamEnabled = prefs.getBoolean("dreamEnabled", true),
            userName = prefs.getString("userName", "") ?: "",
            aboutMe = prefs.getString("aboutMe", "") ?: "",
            replyStyle = prefs.getString("replyStyle", "") ?: "",
        )
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("apiKey", s.apiKey)
            .putString("tavilyKey", s.tavilyKey)
            .putBoolean("webSearch2", s.webSearch)
            .putString("baseUrl", s.baseUrl)
            .putString("model", s.model)
            .putString("thinking", s.thinking.name)
            .putFloat("temperature", s.temperature)
            .putString("themeMode", s.themeMode.name)
            .putString("presets", json.encodeToString(ListSerializer(Preset.serializer()), s.presets))
            .putString("devPrompt2", s.developerPrompt)
            .putInt("compressTokens", s.compressTokens)
            .putBoolean("memoryEnabled", s.memoryEnabled)
            .putBoolean("dreamEnabled", s.dreamEnabled)
            .putString("userName", s.userName)
            .putString("aboutMe", s.aboutMe)
            .putString("replyStyle", s.replyStyle)
            .apply()
    }
}
