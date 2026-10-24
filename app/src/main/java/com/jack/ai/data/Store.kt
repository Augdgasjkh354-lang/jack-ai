package com.jack.ai.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

private val LOCK = Any()
private const val PREV_INTERVAL = 60 * 60 * 1000L

/** 聊天记录和项目存成手机里的 JSON 文件，设置存在 SharedPreferences。 */
class Store(context: Context) {
    companion object {
        /** 这次启动时从副本恢复（或清空）过的文件，「文件名:prev」或「文件名:empty」。 */
        val recovered = java.util.concurrent.ConcurrentLinkedQueue<String>()
    }

    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        com.jack.ai.api.Net.init(app)
    }

    private fun <T> load(name: String, ser: KSerializer<T>): List<T> = synchronized(LOCK) { loadUnlocked(name, ser) }

    /**
     * 读一个 JSON 列表。文件坏了不闪退、也不悄悄清空：把坏文件改名留着（.corrupt-时间），
     * 换用最近一份副本（.prev）；连副本都没有才返回空列表。恢复过的文件记进 [recovered]，界面会提示。
     */
    private fun <T> loadUnlocked(name: String, ser: KSerializer<T>): List<T> {
        val f = File(dir, name)
        val decoder = ListSerializer(ser)
        val text = try {
            // AtomicFile 读的时候会顺带处理上次没写完的情况
            String(AtomicFile(f).readFully(), Charsets.UTF_8)
        } catch (e: java.io.FileNotFoundException) {
            return emptyList()
        }
        return try {
            json.decodeFromString(decoder, text)
        } catch (e: Exception) {
            f.renameTo(File(dir, "$name.corrupt-${System.currentTimeMillis()}"))
            val prev = File(dir, "$name.prev")
            val restored = runCatching { json.decodeFromString(decoder, prev.readText()) }.getOrNull()
            if (restored != null) prev.copyTo(f, overwrite = true)
            recovered += if (restored != null) "$name:prev" else "$name:empty"
            restored ?: emptyList()
        }
    }

    /**
     * 原子写入（写一半断电也不会坏）。写之前把旧文件复制成 .prev 当副本，
     * 每个文件最多一小时复制一次，免得每次保存都多读写一遍。
     */
    private fun atomicWrite(name: String, content: String) {
        val target = File(dir, name)
        val prev = File(dir, "$name.prev")
        if (target.exists() && System.currentTimeMillis() - prev.lastModified() > PREV_INTERVAL) {
            runCatching { target.copyTo(prev, overwrite = true) }
        }
        val af = AtomicFile(target)
        val stream = af.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            af.finishWrite(stream)
        } catch (e: Exception) {
            af.failWrite(stream)
            throw e
        }
    }

    private fun <T> save(name: String, ser: KSerializer<T>, list: List<T>) = synchronized(LOCK) {
        atomicWrite(name, json.encodeToString(ListSerializer(ser), list))
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

    fun loadSiteNotes() = load("browser_site_notes.json", SiteNote.serializer())
    fun saveSiteNotes(list: List<SiteNote>) = save("browser_site_notes.json", SiteNote.serializer(), list)
    fun loadHistory() = load("browser_history.json", HistoryEntry.serializer())
    fun saveHistory(list: List<HistoryEntry>) = save("browser_history.json", HistoryEntry.serializer(), list)
    fun loadPrices() = load("price_log.json", PricePoint.serializer())
    fun savePrices(list: List<PricePoint>) = save("price_log.json", PricePoint.serializer(), list)
    fun loadFlows() = load("browser_flows.json", BrowserFlow.serializer())
    fun saveFlows(list: List<BrowserFlow>) = save("browser_flows.json", BrowserFlow.serializer(), list)

    fun loadRoutines() = load("routines.json", Routine.serializer())
    fun saveRoutines(list: List<Routine>) = save("routines.json", Routine.serializer(), list)

    fun addInbox(item: InboxItem) = synchronized(LOCK) {
        val list = loadUnlocked("inbox.json", InboxItem.serializer()) + item
        atomicWrite("inbox.json", json.encodeToString(ListSerializer(InboxItem.serializer()), list))
        Unit
    }

    /** 取出并清空收件箱。 */
    fun takeInbox(): List<InboxItem> = synchronized(LOCK) {
        val list = loadUnlocked("inbox.json", InboxItem.serializer())
        if (list.isNotEmpty()) File(dir, "inbox.json").delete()
        list
    }

    /** 攒着等 Kavi 看的微信消息。同一个人十分钟内的同一句话只留一条（微信会重发同一条通知）。返回是不是新的。 */
    fun addWeChat(m: WeChatMsg): Boolean = synchronized(LOCK) {
        val list = loadUnlocked("wechat_pending.json", WeChatMsg.serializer())
        if (list.any { it.from == m.from && it.text == m.text && m.at - it.at < 10 * 60_000L }) return false
        // 太久没处理（比如一直没网）也不无限攒：留最新的 300 条
        atomicWrite("wechat_pending.json", json.encodeToString(ListSerializer(WeChatMsg.serializer()), (list + m).takeLast(300)))
        true
    }

    /** 取出并清空攒着的微信消息。 */
    fun takeWeChat(): List<WeChatMsg> = synchronized(LOCK) {
        val list = loadUnlocked("wechat_pending.json", WeChatMsg.serializer())
        if (list.isNotEmpty()) File(dir, "wechat_pending.json").delete()
        list
    }

    /** 这批没处理成，放回去下次再看（排在新来的前面）。 */
    fun putBackWeChat(batch: List<WeChatMsg>) = synchronized(LOCK) {
        val list = batch + loadUnlocked("wechat_pending.json", WeChatMsg.serializer())
        atomicWrite("wechat_pending.json", json.encodeToString(ListSerializer(WeChatMsg.serializer()), list.takeLast(300)))
    }

    fun loadUsage() = load("usage.json", UsageDay.serializer())

    fun loadToolStats() = load("tool_stats.json", ToolStat.serializer())

    /** 发过信的地址（小写）。读过外部内容后发给不在这里的地址，完全授权下也要确认。 */
    fun knownRecipients(): Set<String> = load("recipients.json", String.serializer()).toSet()

    fun addRecipients(list: Collection<String>) = synchronized(LOCK) {
        val all = (loadUnlocked("recipients.json", String.serializer()) + list.map { it.lowercase() }).distinct()
        atomicWrite("recipients.json", json.encodeToString(ListSerializer(String.serializer()), all.takeLast(2000)))
    }

    fun loadFeedback() = load("feedback.json", Feedback.serializer())

    fun addFeedback(f: Feedback) = synchronized(LOCK) {
        val list = loadUnlocked("feedback.json", Feedback.serializer()) + f
        atomicWrite("feedback.json", json.encodeToString(ListSerializer(Feedback.serializer()), list.takeLast(100)))
    }

    fun clearFeedback() = synchronized(LOCK) { File(dir, "feedback.json").delete(); Unit }

    /** 记一次工具调用。 */
    fun addToolStat(name: String, failed: Boolean) = synchronized(LOCK) {
        val list = loadUnlocked("tool_stats.json", ToolStat.serializer()).toMutableList()
        val i = list.indexOfFirst { it.name == name }
        val old = if (i >= 0) list[i] else ToolStat(name)
        val new = old.copy(calls = old.calls + 1, failures = old.failures + if (failed) 1 else 0)
        if (i >= 0) list[i] = new else list += new
        atomicWrite("tool_stats.json", json.encodeToString(ListSerializer(ToolStat.serializer()), list))
    }

    /**
     * 记一笔用量（按天、按模型累加），并按请求发生的这一刻的价格算钱。
     * cacheHit 为 null 表示服务没返回缓存命中数。返回这次花了多少元，不知道价格时返回 null。
     */
    fun addUsage(ep: Endpoint, prompt: Int, cacheHit: Int?, completion: Int): Double? = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val price = Pricing.priceAt(ep, now)
        val hit = cacheHit ?: 0
        val cost = price?.let { Pricing.cost(it, prompt, hit, completion) }
        val list = loadUnlocked("usage.json", UsageDay.serializer()).toMutableList()
        val i = list.indexOfFirst { it.date == date && it.model == ep.key }
        val old = if (i >= 0) list[i] else UsageDay(date, ep.key, costed = true)
        // 旧版本记的这一天先换算成当场计价的格式，再往上加
        val base = if (old.costed) old else old.copy(cost = old.costYuan() ?: 0.0, costed = true, hitKnown = old.cacheHit > 0)
        val new = base.copy(
            prompt = base.prompt + prompt, cacheHit = base.cacheHit + hit,
            completion = base.completion + completion, requests = base.requests + 1,
            cost = base.cost + (cost ?: 0.0),
            unpriced = base.unpriced + if (cost == null) 1 else 0,
            hitKnown = base.hitKnown || cacheHit != null,
        )
        if (i >= 0) list[i] = new else list += new
        atomicWrite("usage.json", json.encodeToString(ListSerializer(UsageDay.serializer()), list.takeLast(800)))
        checkBudget(list, date.take(7))
        cost
    }

    /** 记一次按次收费的调用（比如 DeepSeek 联网），算进当天统计。 */
    fun addPaidCall(key: String, price: Double) = synchronized(LOCK) {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val list = loadUnlocked("usage.json", UsageDay.serializer()).toMutableList()
        val i = list.indexOfFirst { it.date == date && it.model == key }
        val old = if (i >= 0) list[i] else UsageDay(date, key, costed = true)
        val new = old.copy(requests = old.requests + 1, cost = old.cost + price, costed = true)
        if (i >= 0) list[i] = new else list += new
        atomicWrite("usage.json", json.encodeToString(ListSerializer(UsageDay.serializer()), list.takeLast(800)))
        checkBudget(list, date.take(7))
    }

    /** 本月花费到预算的 80% 和 100% 时各提醒一次。 */
    private fun checkBudget(list: List<UsageDay>, month: String) {
        val budget = prefs.getFloat("monthlyBudget", 0f).toDouble()
        if (budget <= 0) return
        val spent = list.filter { it.date.startsWith(month) }.sumOf { it.costYuan() ?: 0.0 }
        val level = when {
            spent >= budget -> 100
            spent >= budget * 0.8 -> 80
            else -> return
        }
        val sent = prefs.getString("budgetAlert", "") ?: ""
        val key = "$month:$level"
        if (sent == key || (level == 80 && sent == "$month:100")) return
        prefs.edit().putString("budgetAlert", key).apply()
        val text = String.format(java.util.Locale.US, "本月已花 ¥%.2f，预算 ¥%.2f", spent, budget)
        Routines.notify(app, "budget", if (level == 100) "本月预算已用完" else "本月预算已用 80%", text)
    }

    /** 备份用：导出全部设置（不含两个 Key）。 */
    fun exportPrefs(): String {
        val obj = kotlinx.serialization.json.buildJsonObject {
            prefs.all.forEach { (k, v) ->
                // 代理的订阅地址和节点里有密码，也不进备份
                if (k == "apiKey" || k == "tavilyKey" || k == "exaKey" || k == "proxySub" || k == "proxyNodes" || k == "notionToken" || k == "githubToken") return@forEach
                // 其他服务的 Key 也不进备份
                val v = if (k == "providers" && v is String) runCatching {
                    val list = json.decodeFromString(ListSerializer(Provider.serializer()), v).map { it.copy(apiKey = "") }
                    json.encodeToString(ListSerializer(Provider.serializer()), list)
                }.getOrDefault("[]")
                // 邮箱授权码也不进备份
                else if (k == "mail" && v is String) runCatching {
                    json.encodeToString(MailAccount.serializer(), json.decodeFromString(MailAccount.serializer(), v).copy(password = ""))
                }.getOrDefault("")
                // 连接器的 Key 也不进备份
                else if (k == "mcpServers" && v is String) runCatching {
                    val list = json.decodeFromString(ListSerializer(McpServer.serializer()), v).map { it.copy(key = "") }
                    json.encodeToString(ListSerializer(McpServer.serializer()), list)
                }.getOrDefault("[]")
                else if (k == "mails" && v is String) runCatching {
                    val list = json.decodeFromString(ListSerializer(MailAccount.serializer()), v).map { it.copy(password = "") }
                    json.encodeToString(ListSerializer(MailAccount.serializer()), list)
                }.getOrDefault("[]") else v
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
        // Pro 还没发布、官方转给 Flash，却按 Pro 价记账：以前默认选的 Pro 一次性换成 Flash（之后用户再选 Pro 不动它）
        val proToFlash = !prefs.getBoolean("proToFlash", false)
        if (proToFlash) prefs.edit().putBoolean("proToFlash", true).apply()
        val model = when (oldModel) {
            "deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash" -> MODEL_FLASH
            MODEL_PRO -> if (proToFlash) MODEL_FLASH else oldModel
            else -> oldModel
        }
        val thinking = prefs.getString("thinking", null)
            ?.let { runCatching { ThinkLevel.valueOf(it) }.getOrNull() }
            ?: if (oldModel == "deepseek-chat") ThinkLevel.OFF else ThinkLevel.AUTO
        return Settings(
            apiKey = prefs.getString("apiKey", "") ?: "",
            tavilyKey = prefs.getString("tavilyKey", "") ?: "",
            exaKey = prefs.getString("exaKey", "") ?: "",
            webSearch = prefs.getBoolean("webSearch2", true),
            baseUrl = prefs.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            model = model,
            thinking = thinking,
            temperature = prefs.getFloat("temperature", 1.0f),
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "SYSTEM")!!) }
                .getOrDefault(ThemeMode.SYSTEM),
            presets = presets,
            workTemplates = (prefs.getString("workTemplates", null)?.let {
                runCatching { json.decodeFromString(ListSerializer(Preset.serializer()), it) }.getOrNull()
            } ?: DEFAULT_WORK_TEMPLATES).let { list ->
                // 自检模板总是用最新版（工具变了它也要跟着变）；用户删掉过也补回来
                list.filterNot { it.id == SELF_CHECK.id } + SELF_CHECK
            },
            activeWork = prefs.getBoolean("activeWork", false),
            chatModel = prefs.getString("chatModel", "") ?: "",
            chatThinking = prefs.getString("chatThinking", null)?.let { runCatching { ThinkLevel.valueOf(it) }.getOrNull() },
            workModel = (prefs.getString("workModel", MODEL_FLASH) ?: MODEL_FLASH).let { if (proToFlash && it == MODEL_PRO) MODEL_FLASH else it },
            workThinking = prefs.getString("workThinking", null)?.let { runCatching { ThinkLevel.valueOf(it) }.getOrNull() } ?: ThinkLevel.HIGH,
            // 默认值从 1 元改成了 5 元，换个键名，旧的默认值不沿用
            workCostLimit = prefs.getFloat("workCostLimit5", 5f).toDouble(),
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
            // 换成纯黑白风格时，以前的强调色一次性换成「黑白」（之后用户再选别的不动它）
            accent = if (!prefs.getBoolean("monoTheme", false)) { prefs.edit().putBoolean("monoTheme", true).apply(); "mono" }
                else prefs.getString("accent", "mono") ?: "mono",
            appLock = prefs.getBoolean("appLock", false),
            monthlyBudget = prefs.getFloat("monthlyBudget", 0f).toDouble(),
            fullAuthUntil = prefs.getLong("fullAuthUntil", 0L),
            mails = prefs.getString("mails", null)?.let { runCatching { json.decodeFromString(ListSerializer(MailAccount.serializer()), it) }.getOrNull() }
                // 旧版本只有一个邮箱
                ?: listOfNotNull(prefs.getString("mail", null)?.let { runCatching { json.decodeFromString(MailAccount.serializer(), it) }.getOrNull() }),
            providers = prefs.getString("providers", null)?.let {
                runCatching { json.decodeFromString(ListSerializer(Provider.serializer()), it) }.getOrNull()
            } ?: emptyList(),
            mcpServers = prefs.getString("mcpServers", null)?.let {
                runCatching { json.decodeFromString(ListSerializer(McpServer.serializer()), it) }.getOrNull()
            } ?: emptyList(),
            proxyEnabled = prefs.getBoolean("proxyEnabled", false),
            proxySub = prefs.getString("proxySub", "") ?: "",
            proxyNodes = prefs.getString("proxyNodes", null)?.let {
                runCatching { json.decodeFromString(ListSerializer(ProxyNode.serializer()), it) }.getOrNull()
            } ?: emptyList(),
            proxyNode = prefs.getString("proxyNode", "") ?: "",
            notionToken = prefs.getString("notionToken", "") ?: "",
            githubToken = prefs.getString("githubToken", "") ?: "",
            autoUpdate = prefs.getBoolean("autoUpdate", true),
            updateWifiOnly = prefs.getBoolean("updateWifiOnly", false),
            wechatWatch = prefs.getBoolean("wechatWatch", false),
            wechatSkip = prefs.getString("wechatSkip", "") ?: "",
            linksInApp = prefs.getBoolean("linksInApp", true),
        ).also { com.jack.ai.api.Net.apply(it) }
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("apiKey", s.apiKey)
            .putString("tavilyKey", s.tavilyKey)
            .putString("exaKey", s.exaKey)
            .putBoolean("webSearch2", s.webSearch)
            .putString("baseUrl", s.baseUrl)
            .putString("model", s.model)
            .putString("thinking", s.thinking.name)
            .putFloat("temperature", s.temperature)
            .putString("themeMode", s.themeMode.name)
            .putString("presets", json.encodeToString(ListSerializer(Preset.serializer()), s.presets))
            // 底层提示词固定用内置的，旧版本存过的清掉
            .remove("devPrompt2")
            .remove("workDevPrompt")
            .putString("workTemplates", json.encodeToString(ListSerializer(Preset.serializer()), s.workTemplates))
            .putBoolean("activeWork", s.activeWork)
            .putString("chatModel", s.chatModel)
            .putString("chatThinking", s.chatThinking?.name)
            .putString("workModel", s.workModel)
            .putString("workThinking", s.workThinking.name)
            .putFloat("workCostLimit5", s.workCostLimit.toFloat())
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
            .putString("providers", json.encodeToString(ListSerializer(Provider.serializer()), s.providers))
            .putFloat("monthlyBudget", s.monthlyBudget.toFloat())
            .putLong("fullAuthUntil", s.fullAuthUntil)
            .remove("mail")
            .putString("mails", json.encodeToString(ListSerializer(MailAccount.serializer()), s.mails))
            .putString("mcpServers", json.encodeToString(ListSerializer(McpServer.serializer()), s.mcpServers))
            .putBoolean("proxyEnabled", s.proxyEnabled)
            .putString("proxySub", s.proxySub)
            .putString("proxyNodes", json.encodeToString(ListSerializer(ProxyNode.serializer()), s.proxyNodes))
            .putString("proxyNode", s.proxyNode)
            .putString("notionToken", s.notionToken)
            .putString("githubToken", s.githubToken)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("updateWifiOnly", s.updateWifiOnly)
            .putBoolean("wechatWatch", s.wechatWatch)
            .putString("wechatSkip", s.wechatSkip)
            .putBoolean("linksInApp", s.linksInApp)
            .apply()
        com.jack.ai.api.Net.apply(s)
    }
}
