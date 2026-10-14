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

    fun loadLibrary() = load("library.json", LibraryDoc.serializer())
    fun saveLibrary(list: List<LibraryDoc>) = save("library.json", LibraryDoc.serializer(), list)
    fun libraryText(id: String): String = runCatching { File(File(dir, "library"), "$id.txt").readText() }.getOrDefault("")
    fun saveLibraryText(id: String, text: String) {
        File(dir, "library").mkdirs()
        File(File(dir, "library"), "$id.txt").writeText(text)
    }
    fun deleteLibraryText(id: String) { File(File(dir, "library"), "$id.txt").delete() }

    fun loadCards() = load("flashcards.json", Flashcard.serializer())
    fun saveCards(list: List<Flashcard>) = save("flashcards.json", Flashcard.serializer(), list)

    fun loadRoutines() = load("routines.json", Routine.serializer())
    fun saveRoutines(list: List<Routine>) = save("routines.json", Routine.serializer(), list)

    fun addInbox(item: InboxItem) = synchronized(LOCK) {
        val list = loadUnlocked("inbox.json", InboxItem.serializer()) + item
        val tmp = File(dir, "inbox.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(InboxItem.serializer()), list))
        tmp.renameTo(File(dir, "inbox.json"))
        Unit
    }

    /** 取出并清空收件箱。 */
    fun takeInbox(): List<InboxItem> = synchronized(LOCK) {
        val list = loadUnlocked("inbox.json", InboxItem.serializer())
        if (list.isNotEmpty()) File(dir, "inbox.json").delete()
        list
    }

    fun loadUsage() = load("usage.json", UsageDay.serializer())

    /** 记一笔用量（按天、按模型累加）。 */
    fun addUsage(model: String, prompt: Int, cacheHit: Int, completion: Int) = synchronized(LOCK) {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val list = loadUnlocked("usage.json", UsageDay.serializer()).toMutableList()
        val i = list.indexOfFirst { it.date == date && it.model == model }
        val old = if (i >= 0) list[i] else UsageDay(date, model)
        val new = old.copy(
            prompt = old.prompt + prompt, cacheHit = old.cacheHit + cacheHit,
            completion = old.completion + completion, requests = old.requests + 1,
        )
        if (i >= 0) list[i] = new else list += new
        val tmp = File(dir, "usage.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(UsageDay.serializer()), list.takeLast(800)))
        tmp.renameTo(File(dir, "usage.json"))
        Unit
    }

    /** 备份用：导出全部设置（不含两个 Key）。 */
    fun exportPrefs(): String {
        val obj = kotlinx.serialization.json.buildJsonObject {
            prefs.all.forEach { (k, v) ->
                if (k == "apiKey" || k == "tavilyKey") return@forEach
                val e = when (v) {
                    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                    is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                    is String -> kotlinx.serialization.json.JsonPrimitive(v)
                    is Set<*> -> kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
                    else -> return@forEach
                }
                val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> "set" }
                put(k, kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive(t)); put("v", e) })
            }
        }
        return obj.toString()
    }

    fun importPrefs(text: String) {
        val obj = Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return
        val ed = prefs.edit()
        obj.forEach { (k, el) ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
            val t = (o["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val v = o["v"] ?: return@forEach
            val p = v as? kotlinx.serialization.json.JsonPrimitive
            when (t) {
                "b" -> p?.content?.toBooleanStrictOrNull()?.let { ed.putBoolean(k, it) }
                "i" -> p?.content?.toIntOrNull()?.let { ed.putInt(k, it) }
                "l" -> p?.content?.toLongOrNull()?.let { ed.putLong(k, it) }
                "f" -> p?.content?.toFloatOrNull()?.let { ed.putFloat(k, it) }
                "s" -> p?.content?.let { ed.putString(k, it) }
                "set" -> (v as? kotlinx.serialization.json.JsonArray)?.let { a ->
                    ed.putStringSet(k, a.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet())
                }
            }
        }
        ed.apply()
    }

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
            disabledSkills = prefs.getStringSet("disabledSkills", emptySet())?.toSet() ?: emptySet(),
            customSkills = prefs.getString("customSkills", null)?.let {
                runCatching { json.decodeFromString(ListSerializer(Skill.serializer()), it) }.getOrNull()
            } ?: emptyList(),
            fontScale = prefs.getFloat("fontScale", 1f),
            accent = prefs.getString("accent", "clay") ?: "clay",
            appLock = prefs.getBoolean("appLock", false),
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
            .putStringSet("disabledSkills", s.disabledSkills)
            .putString("customSkills", json.encodeToString(ListSerializer(Skill.serializer()), s.customSkills))
            .putFloat("fontScale", s.fontScale)
            .putString("accent", s.accent)
            .putBoolean("appLock", s.appLock)
            .apply()
    }
}
