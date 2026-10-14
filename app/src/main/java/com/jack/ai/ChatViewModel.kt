package com.jack.ai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.ai.api.ApiException
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.api.StreamEvent
import com.jack.ai.api.TavilyClient
import com.jack.ai.api.formatResultsForModel
import com.jack.ai.api.toSources
import com.jack.ai.data.Attachment
import com.jack.ai.data.Attachments
import com.jack.ai.data.Backup
import com.jack.ai.data.ChartWriter
import com.jack.ai.data.JsRunner
import com.jack.ai.data.Flashcard
import com.jack.ai.data.LibraryDoc
import com.jack.ai.data.LibraryIndex
import com.jack.ai.data.Recall
import com.jack.ai.data.REPEAT_LABELS
import com.jack.ai.data.Routine
import com.jack.ai.data.Routines
import com.jack.ai.data.describe
import com.jack.ai.data.nextAt
import com.jack.ai.data.UsageDay
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Compression
import com.jack.ai.data.Conversation
import com.jack.ai.data.DreamEntry
import com.jack.ai.data.Dreamer
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.OfficeWriter
import com.jack.ai.data.PdfWriter
import com.jack.ai.data.Skill
import com.jack.ai.data.SkillIds
import com.jack.ai.data.SkillTools
import com.jack.ai.data.activeSkills
import com.jack.ai.data.MODEL_FLASH
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Preset
import com.jack.ai.data.Project
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.MODEL_PRO
import com.jack.ai.data.modelSupportsImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val conversations: List<Conversation> = emptyList(),
    val projects: List<Project> = emptyList(),
    val currentId: String? = null,
    /** 还没发出去的新对话属于哪个项目。 */
    val draftProjectId: String? = null,
    val streaming: Boolean = false,
    val settings: Settings = Settings(),
    val pending: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    val toast: String? = null,
    val compressingId: String? = null,
    val memories: List<MemoryItem> = emptyList(),
    val dreams: List<DreamEntry> = emptyList(),
    val dreaming: Boolean = false,
    /** 从别的 App 分享进来、或语音识别出来的文字，等输入框取走。 */
    val incomingText: String? = null,
    /** 桌面小组件点了话筒，等输入框打开语音识别。 */
    val voiceRequest: Boolean = false,
    val routines: List<Routine> = emptyList(),
    val library: List<LibraryDoc> = emptyList(),
    val cards: List<Flashcard> = emptyList(),
) {
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
    val currentProjectId: String? get() = current?.projectId ?: if (currentId == null) draftProjectId else null
    fun project(id: String?): Project? = id?.let { pid -> projects.firstOrNull { it.id == pid } }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val client = DeepSeekClient(store::addUsage)
    private val tavily = TavilyClient()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    private val ioSerial = Dispatchers.IO.limitedParallelism(1)

    init {
        _state.value = UiState(
            conversations = store.loadConversations().sortedByDescending { it.updatedAt },
            projects = store.loadProjects().sortedByDescending { it.updatedAt },
            settings = store.loadSettings(),
            memories = store.loadMemories(),
            dreams = store.loadDreams(),
        )
        Dreamer.schedule(app, _state.value.settings.dreamEnabled)
        _state.update { it.copy(routines = store.loadRoutines(), library = store.loadLibrary(), cards = store.loadCards()) }
        Routines.scheduleAll(app)
        mergeInbox()
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    // ---------- 对话管理 ----------

    fun newChat(projectId: String? = null) {
        stop()
        _state.update { it.copy(currentId = null, draftProjectId = projectId, pending = emptyList()) }
    }

    fun select(id: String) {
        stop()
        _state.update { it.copy(currentId = id, pending = emptyList()) }
    }

    fun delete(id: String) {
        if (id == _state.value.currentId) stop()
        val conv = _state.value.conversations.firstOrNull { it.id == id }
        _state.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.id == id },
                currentId = if (s.currentId == id) null else s.currentId,
            )
        }
        conv?.let { cleanup(it.messages) }
        persist()
    }

    fun rename(id: String, title: String) {
        updateConv(id) { it.copy(title = title.ifBlank { it.title }) }
        persist()
    }

    fun clearAll() {
        stop()
        val all = _state.value.conversations.flatMap { it.messages }
        _state.update { it.copy(conversations = emptyList(), currentId = null) }
        cleanup(all)
        persist()
    }

    /** 在新对话开始前选择人设。 */
    fun choosePreset(preset: Preset) {
        val cur = _state.value.current
        if (cur == null) {
            val conv = Conversation(
                systemPrompt = preset.prompt,
                presetName = preset.name,
                projectId = _state.value.draftProjectId,
            )
            _state.update { it.copy(conversations = listOf(conv) + it.conversations, currentId = conv.id) }
        } else if (cur.messages.isEmpty()) {
            updateConv(cur.id) { it.copy(systemPrompt = preset.prompt, presetName = preset.name) }
        }
    }

    // ---------- 项目 ----------

    fun saveProject(project: Project) {
        _state.update { s ->
            val p = project.copy(updatedAt = System.currentTimeMillis())
            val exists = s.projects.any { it.id == p.id }
            s.copy(projects = (if (exists) s.projects.map { if (it.id == p.id) p else it } else listOf(p) + s.projects)
                .sortedByDescending { it.updatedAt })
        }
        persistProjects()
    }

    fun deleteProject(id: String) {
        val removedConvs = _state.value.conversations.filter { it.projectId == id }
        if (removedConvs.any { it.id == _state.value.currentId }) stop()
        _state.update { s ->
            s.copy(
                projects = s.projects.filterNot { it.id == id },
                conversations = s.conversations.filterNot { it.projectId == id },
                currentId = if (removedConvs.any { it.id == s.currentId }) null else s.currentId,
                draftProjectId = if (s.draftProjectId == id) null else s.draftProjectId,
            )
        }
        cleanup(removedConvs.flatMap { it.messages })
        persist()
        persistProjects()
    }

    fun addProjectFile(projectId: String, uri: Uri) {
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val att = Attachments.importFile(getApplication<Application>(), uri)
                val p = _state.value.project(projectId) ?: return@launch
                saveProject(p.copy(files = p.files + att))
            } catch (e: Exception) {
                _state.update { it.copy(toast = e.message ?: "读取文件失败") }
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
    }

    // ---------- 附件 ----------

    fun addImages(uris: List<Uri>) = importAll(uris) { Attachments.importImage(getApplication<Application>(), it) }
    fun addFiles(uris: List<Uri>) = importAll(uris) { Attachments.importFile(getApplication<Application>(), it) }

    private fun importAll(uris: List<Uri>, import: suspend (Uri) -> Attachment) {
        if (uris.isEmpty()) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    val att = import(uri)
                    _state.update { it.copy(pending = it.pending + att) }
                } catch (e: Exception) {
                    _state.update { it.copy(toast = e.message ?: "读取失败") }
                }
            }
            _state.update { it.copy(importing = false) }
        }
    }

    fun removePending(id: String) {
        val att = _state.value.pending.firstOrNull { it.id == id }
        _state.update { it.copy(pending = it.pending.filterNot { a -> a.id == id }) }
        att?.let { deleteImages(listOf(it)) }
    }

    /** 删除消息时，顺带删掉它们的图片和生成的文件。 */
    private fun cleanup(msgs: List<ChatMessage>) {
        deleteImages(msgs.flatMap { it.attachments })
        val paths = msgs.flatMap { m -> m.files.map { it.path } }
        if (paths.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
    }

    private fun deleteImages(list: List<Attachment>) {
        val paths = list.filter { it.kind == "image" && it.path.isNotEmpty() }.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
    }

    // ---------- 设置 ----------

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = _state.value.settings
        _state.update { it.copy(settings = transform(it.settings)) }
        val after = _state.value.settings
        store.saveSettings(after)
        if (before.dreamEnabled != after.dreamEnabled) Dreamer.schedule(getApplication<Application>(), after.dreamEnabled)
    }

    suspend fun checkBalance(): String {
        val s = _state.value.settings
        if (s.apiKey.isBlank()) return "请先填写 API Key"
        return try {
            "余额：" + client.balance(s.baseUrl, s.apiKey)
        } catch (e: ApiException) {
            e.message ?: "查询失败"
        } catch (e: IOException) {
            "网络连接失败"
        }
    }

    // ---------- 发消息 ----------

    /** 返回 true 表示已发出，输入框可以清空。 */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val atts = _state.value.pending
        if ((trimmed.isEmpty() && atts.isEmpty()) || _state.value.streaming || _state.value.importing) return false
        if (atts.any { it.kind == "image" } && !modelSupportsImages(_state.value.settings.model)) {
            _state.update { it.copy(toast = "V4 Pro 看不了图片，请点右上角切换到 V4.1 Flash") }
            return false
        }
        var conv = _state.value.current
        if (conv == null) {
            conv = Conversation(projectId = _state.value.draftProjectId)
            val c = conv
            _state.update { it.copy(conversations = listOf(c) + it.conversations, currentId = c.id) }
        }
        val id = conv.id
        val title = trimmed.ifEmpty { atts.first().name }.take(20).replace('\n', ' ')
        updateConv(id) { c ->
            c.copy(
                title = if (c.messages.isEmpty()) title else c.title,
                messages = c.messages + ChatMessage(role = "user", content = trimmed, attachments = atts),
            )
        }
        _state.update { it.copy(pending = emptyList()) }
        startReply(id)
        return true
    }

    /** 重新生成最后一条回答。 */
    fun regenerate() {
        val conv = _state.value.current ?: return
        if (_state.value.streaming) return
        val msgs = conv.messages.dropLastWhile { it.role == "assistant" }
        if (msgs.isEmpty()) return
        cleanup(conv.messages.drop(msgs.size))
        updateConv(conv.id) { it.copy(messages = msgs) }
        startReply(conv.id)
    }

    /** 删除某条消息（以及它之后的所有消息）。 */
    fun deleteFrom(messageId: String) {
        val conv = _state.value.current ?: return
        stop()
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        cleanup(conv.messages.drop(idx))
        updateConv(conv.id) { it.copy(messages = it.messages.take(idx)) }
        persist()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 让 V4 Pro 复核最后一条回答，并给出改进版。 */
    fun reviewLast() {
        val conv = _state.value.current ?: return
        if (_state.value.streaming) return
        if (conv.messages.lastOrNull()?.let { it.role == "assistant" && it.content.isNotBlank() } != true) return
        startReply(conv.id, modelOverride = MODEL_PRO, extraUser = REVIEW_PROMPT)
    }

    private fun startReply(convId: String, modelOverride: String? = null, extraUser: String? = null) {
        val settings = _state.value.settings
        val conv = _state.value.conversations.first { it.id == convId }
        val project = _state.value.project(conv.projectId)
        val model = modelOverride ?: settings.model
        val thinking = if (modelOverride != null && settings.thinking == ThinkLevel.OFF) ThinkLevel.HIGH else settings.thinking
        val reply = ChatMessage(role = "assistant", model = model)
        updateConv(convId) { it.copy(messages = it.messages + reply) }

        if (settings.apiKey.isBlank()) {
            patchMessage(convId, reply.id) { it.copy(error = "还没有填写 DeepSeek API Key，请点左上角菜单 → 设置") }
            persist()
            return
        }
        val skills = settings.activeSkills()
        val skillIds = skills.map { it.id }.toSet()
        val web = SkillIds.WEB in skillIds
        val thinkingOn = thinking.effort != null
        val images = modelSupportsImages(model)
        val compressed = conv.summary.isNotBlank()
        val memories = if (settings.memoryEnabled) store.loadMemories() else emptyList()
        val toolSet = buildJsonArray {
            if (skills.isNotEmpty()) add(SkillTools.USE_SKILL)
            if (web) { add(SkillTools.WEB_SEARCH); add(SkillTools.READ_WEBPAGE) }
            if (SkillIds.WORD in skillIds) add(SkillTools.CREATE_WORD)
            if (SkillIds.PPT in skillIds) add(SkillTools.CREATE_PPT)
            if (SkillIds.EXCEL in skillIds) add(SkillTools.CREATE_EXCEL)
            if (SkillIds.PDF in skillIds) add(SkillTools.CREATE_PDF)
            if (SkillIds.CHATS in skillIds) add(SkillTools.SEARCH_CHATS)
            if (SkillIds.CALC in skillIds) { add(SkillTools.RUN_JS); add(SkillTools.CREATE_CHART) }
            if (SkillIds.LIBRARY in skillIds) { add(SkillTools.SEARCH_LIBRARY); add(SkillTools.LIST_LIBRARY) }
            if (SkillIds.LEARN in skillIds) add(SkillTools.SAVE_FLASHCARDS)
            if (SkillIds.ROUTINE in skillIds) {
                add(SkillTools.CREATE_REMINDER); add(SkillTools.CREATE_TASK)
                add(SkillTools.LIST_ROUTINES); add(SkillTools.DELETE_ROUTINE)
            }
            if (compressed) add(SkillTools.SEARCH_HISTORY)
            if (settings.memoryEnabled) add(SkillTools.SAVE_MEMORY)
        }.takeIf { it.isNotEmpty() }

        /**
         * flat=true 时不发回以前的工具调用过程，只保留最终回答。
         * 开着思考时，接口要求带工具调用的旧回合必须附上思考内容；缺了就会报 400，
         * 所以缺思考内容的旧回合也按 flat 处理。
         */
        fun buildHistory(withTools: Boolean, flat: Boolean = false): MutableList<JsonObject> {
            val list = mutableListOf<JsonObject>()
            val system = buildSystemPrompt(settings, conv, project, if (withTools) toolSet else null, memories, skills)
            if (system.isNotBlank()) list += msg("system", system)
            for (m in Compression.liveMessages(conv)) {
                if (m.error != null) continue
                if (m.role == "user") {
                    list += userMessage(m, images)
                } else if (m.content.isNotBlank()) {
                    val trail = if (withTools && !flat) m.trail.mapNotNull { t ->
                        runCatching { Json.parseToJsonElement(t).jsonObject }.getOrNull()
                    } else emptyList()
                    val usable = trail.isNotEmpty() && (!thinkingOn || trail.all { t ->
                        t["role"].text() != "assistant" || t["reasoning_content"].text().isNotEmpty()
                    })
                    if (usable) {
                        trail.forEach { t ->
                            list += if (thinkingOn || "reasoning_content" !in t) t
                            else JsonObject(t - "reasoning_content")
                        }
                    }
                    list += buildJsonObject {
                        put("role", "assistant")
                        put("content", m.content)
                        if (usable && thinkingOn && m.reasoning.isNotEmpty()) put("reasoning_content", m.reasoning)
                    }
                }
            }
            if (extraUser != null) list += msg("user", extraUser)
            return list
        }

        _state.update { it.copy(streaming = true) }
        job = viewModelScope.launch {
            val reasoning = StringBuilder()
            val content = StringBuilder()
            val trail = mutableListOf<String>()
            var lastFlush = 0L
            fun flush() {
                val r = reasoning.toString()
                val c = content.toString()
                val t = trail.toList()
                patchMessage(convId, reply.id) { it.copy(reasoning = r, content = c, trail = t) }
            }
            var sourceCount = 0
            var maxRounds = 5
            suspend fun runTool(label: String, action: suspend () -> Pair<String, SearchRecord>): String {
                patchMessage(convId, reply.id) { it.copy(searches = it.searches + SearchRecord(label, pending = true)) }
                fun finish(rec: SearchRecord) = patchMessage(convId, reply.id) { m ->
                    m.copy(searches = m.searches.map { if (it.query == label && it.pending) rec else it })
                }
                return try {
                    val (text, rec) = action()
                    finish(rec)
                    text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val err = when (e) {
                        is ApiException -> e.message ?: "接口出错"
                        is IOException -> "网络连接失败"
                        else -> e.message ?: "出错了"
                    }
                    finish(SearchRecord(label, error = err))
                    "工具调用失败：$err"
                }
            }
            suspend fun search(query: String) = runTool(query) {
                val results = tavily.search(settings.tavilyKey, query)
                val text = formatResultsForModel(results, sourceCount + 1)
                sourceCount += results.size
                text to SearchRecord(query, results.toSources())
            }
            suspend fun readPage(url: String) = runTool("读取 $url") {
                val text = tavily.extract(settings.tavilyKey, url)
                text to SearchRecord("读取 $url", listOf(com.jack.ai.data.SearchSource(url, url)))
            }
            suspend fun searchOld(query: String) = runTool("翻阅旧记录：$query") {
                Compression.searchHistory(Compression.compressedMessages(conv), query) to
                    SearchRecord("翻阅旧记录：$query")
            }
            suspend fun remember(text: String) = runTool("记住：$text") {
                val list = store.loadMemories() + MemoryItem(text = text)
                store.saveMemories(list)
                _state.update { it.copy(memories = list) }
                "已记住。" to SearchRecord("记住：$text")
            }
            suspend fun searchChats(query: String) = runTool("翻阅聊天记录：$query") {
                val all = _state.value.conversations
                withContext(Dispatchers.Default) { Compression.searchAllChats(all, query, convId) } to
                    SearchRecord("翻阅聊天记录：$query")
            }
            suspend fun searchLibrary(query: String) = runTool("翻阅资料库：$query") {
                val hits = withContext(Dispatchers.IO) { index().search(query) }
                (if (hits.isEmpty()) "资料库里没有找到和“$query”相关的内容。" else hits.joinToString("\n\n") {
                    "【《${it.doc}》第 ${it.part} 段】\n${it.text}"
                }) to SearchRecord("翻阅资料库：$query")
            }
            suspend fun listLibrary() = runTool("查看资料库") {
                val docs = store.loadLibrary()
                (if (docs.isEmpty()) "资料库是空的。用户可以在左侧菜单「资料库」里导入文档。"
                else docs.joinToString("\n") { "《${it.name}》${it.chars} 字" }) to SearchRecord("查看资料库")
            }
            suspend fun saveCards(args: JsonObject) = runTool("生成复习卡片") {
                val deck = args["deck"].text().ifBlank { "未分类" }
                val cards = (args["cards"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val f = o["front"].text().trim()
                    val b = o["back"].text().trim()
                    if (f.isEmpty() || b.isEmpty()) null else Flashcard(deck = deck, front = f, back = b)
                }
                if (cards.isEmpty()) throw IllegalArgumentException("没有卡片内容")
                editCards { it + cards }
                "已保存 ${cards.size} 张卡片到卡组「$deck」。" to SearchRecord("生成复习卡片：$deck（${cards.size} 张）")
            }
            suspend fun runJs(code: String) = runTool("运行代码计算") {
                withContext(Dispatchers.Default) { JsRunner.run(code) } to SearchRecord("运行代码计算")
            }
            suspend fun makeChart(args: JsonObject): String {
                val title = args["title"].text().ifBlank { "图表" }
                return runTool("生成图表：$title") {
                    val labels = (args["labels"] as? JsonArray).orEmpty().map { it.text() }
                    val series = (args["series"] as? JsonArray).orEmpty().mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val values = (o["values"] as? JsonArray).orEmpty().map { it.text().toDoubleOrNull() ?: 0.0 }
                        ChartWriter.Series(o["name"].text(), values)
                    }
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(getApplication<Application>().filesDir, "outputs").apply { mkdirs() }
                        val f = File(dir, safeFileName(title).ifBlank { "图表" } + "-" + System.currentTimeMillis() % 100000 + ".png")
                        try {
                            f.outputStream().buffered().use { ChartWriter.render(args["type"].text(), title, args["unit"].text(), labels, series, it) }
                        } catch (e: Exception) {
                            f.delete(); throw e
                        }
                        f
                    }
                    val gen = GeneratedFile(file.name, file.absolutePath, "image/png", file.length())
                    patchMessage(convId, reply.id) { it.copy(files = it.files + gen) }
                    "图表已生成并显示在聊天里。" to SearchRecord("生成图表：$title")
                }
            }
            suspend fun makeRoutine(kind: String, args: JsonObject): String {
                val label = if (kind == "reminder") "设置提醒" else "设置定时任务"
                return runTool("$label：" + (if (kind == "reminder") args["text"] else args["title"]).text()) {
                    val (date, h, m) = parseDateTime(args["datetime"].text())
                        ?: throw IllegalArgumentException("时间格式不对，要写成 yyyy-MM-dd HH:mm")
                    val repeat = args["repeat"].text().takeIf { it in REPEAT_LABELS } ?: "none"
                    val r = if (kind == "reminder") Routine(kind = "reminder", title = args["text"].text(), hour = h, minute = m, repeat = repeat, date = date)
                    else Routine(kind = "task", title = args["title"].text(), prompt = args["prompt"].text(), hour = h, minute = m, repeat = repeat, date = date)
                    if (r.nextAt() == null) throw IllegalArgumentException("这个时间已经过去了")
                    withContext(Dispatchers.Main) { saveRoutine(r) }
                    val warn = if (!Routines.canNotify(getApplication<Application>())) "（注意：App 还没有通知权限，用户需要打开左侧菜单「提醒和任务」页面授权）" else ""
                    "已设置：${r.describe()}，${if (kind == "reminder") "提醒" else "任务"}「${r.title}」。编号 ${r.id.take(8)}。$warn" to
                        SearchRecord("$label：${r.title}")
                }
            }
            suspend fun listRoutines() = runTool("查看提醒和任务") {
                val list = store.loadRoutines()
                (if (list.isEmpty()) "用户还没有任何提醒和定时任务。" else list.joinToString("\n") { r ->
                    "编号 ${r.id.take(8)}｜${if (r.kind == "reminder") "提醒" else "任务"}｜${r.describe()}｜${r.title}" +
                        (if (r.enabled) "" else "｜已结束或已关闭")
                }) to SearchRecord("查看提醒和任务")
            }
            suspend fun removeRoutine(id: String) = runTool("删除提醒或任务") {
                val r = store.loadRoutines().firstOrNull { it.id.startsWith(id.trim()) }
                if (r == null) "没有找到这个编号。" to SearchRecord("删除提醒或任务", error = "没找到")
                else {
                    withContext(Dispatchers.Main) { deleteRoutine(r.id) }
                    "已删除「${r.title}」。" to SearchRecord("删除：${r.title}")
                }
            }
            suspend fun useSkill(name: String) = runTool("使用技能：$name") {
                val skill = findSkill(skills, name)
                if (skill?.id == SkillIds.RESEARCH) maxRounds = 25
                val text = skill?.let { "【${it.name}】\n${it.instructions}" }
                    ?: ("没有这个技能。可用的技能：" + skills.joinToString("、") { it.name })
                text to SearchRecord("使用技能：${skill?.name ?: name}")
            }
            suspend fun makeFile(kind: String, args: JsonObject): String {
                val base = safeFileName(args["filename"].text()).ifBlank { "未命名" }
                val (ext, mime, label) = when (kind) {
                    "create_word" -> Triple("docx", MIME_DOCX, "Word")
                    "create_ppt" -> Triple("pptx", MIME_PPTX, "PPT")
                    "create_pdf" -> Triple("pdf", "application/pdf", "PDF")
                    else -> Triple("xlsx", MIME_XLSX, "Excel")
                }
                return runTool("生成 $label：$base.$ext") {
                    // PPT 先把配图下载好
                    val slides = if (kind == "create_ppt") withImages(parseSlides(args), args) else null
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(getApplication<Application>().filesDir, "outputs").apply { mkdirs() }
                        var f = File(dir, "$base.$ext")
                        var n = 2
                        while (f.exists()) f = File(dir, "$base($n).$ext").also { n++ }
                        try {
                            f.outputStream().buffered().use { out ->
                                when (kind) {
                                    "create_word" -> OfficeWriter.writeDocx(args["markdown"].text(), out)
                                    "create_ppt" -> OfficeWriter.writePptx(slides!!, args["theme"].text().ifBlank { "blue" }, out)
                                    "create_pdf" -> PdfWriter.write(args["markdown"].text(), out)
                                    else -> OfficeWriter.writeXlsx(parseSheets(args), out)
                                }
                            }
                        } catch (e: Exception) {
                            f.delete()
                            throw e
                        }
                        f
                    }
                    val gen = GeneratedFile(file.name, file.absolutePath, mime, file.length())
                    patchMessage(convId, reply.id) { it.copy(files = it.files + gen) }
                    "已生成文件「${file.name}」，用户可以在聊天里直接打开或分享。" to SearchRecord("生成 $label：${file.name}")
                }
            }

            try {
                var tools: JsonArray? = toolSet
                val messages = buildHistory(tools != null)
                var round = 0
                var flatTried = false
                while (true) {
                    val calls = sortedMapOf<Int, ToolAcc>()
                    val roundReasoning = StringBuilder()
                    val roundContent = StringBuilder()
                    if (content.isNotEmpty() && !content.endsWith("\n")) content.append("\n\n")
                    try {
                        client.stream(settings.baseUrl, settings.apiKey, model, messages,
                            thinking, settings.temperature, tools)
                            .collect { ev ->
                                when (ev) {
                                    is StreamEvent.Text -> {
                                        reasoning.append(ev.reasoning); roundReasoning.append(ev.reasoning)
                                        content.append(ev.content); roundContent.append(ev.content)
                                        val now = System.currentTimeMillis()
                                        if (now - lastFlush > 60) { lastFlush = now; flush() }
                                    }
                                    is StreamEvent.Usage -> {
                                        val total = ev.promptTokens + ev.completionTokens
                                        updateConvQuiet(convId) { it.copy(lastPromptTokens = total, spentTokens = it.spentTokens + total) }
                                        launch(Dispatchers.IO) { store.addUsage(model, ev.promptTokens, ev.cacheHit, ev.completionTokens) }
                                    }
                                    is StreamEvent.ToolCall -> {
                                        val acc = calls.getOrPut(ev.index) { ToolAcc() }
                                        if (ev.id.isNotEmpty()) acc.id = ev.id
                                        if (ev.name.isNotEmpty()) acc.name = ev.name
                                        acc.args.append(ev.arguments)
                                    }
                                }
                            }
                    } catch (e: ApiException) {
                        // 带工具的请求被拒（比如旧记录缺少思考内容），退回到“先搜再答”
                        if (tools != null && round == 0 && e.code == 400 && roundContent.isEmpty() && !flatTried) {
                            // 先试试去掉旧的工具调用过程，工具照样能用
                            flatTried = true
                            messages.clear(); messages.addAll(buildHistory(true, flat = true))
                            continue
                        }
                        if (tools != null && round == 0 && e.code == 400 && roundContent.isEmpty()) {
                            tools = null
                            val q = conv.messages.last { it.role == "user" }.content.take(300)
                            val fresh = buildHistory(false)
                            if (web && q.isNotBlank()) {
                                val results = search(q)
                                fresh.add(1.coerceAtMost(fresh.size), msg("system",
                                    "以下是联网搜索结果，回答时参考并用 [编号](链接) 标注来源：\n\n$results"))
                            }
                            messages.clear(); messages.addAll(fresh)
                            continue
                        }
                        throw e
                    }
                    flush()
                    if (calls.isEmpty()) break

                    round++
                    val assistantCall = buildJsonObject {
                        put("role", "assistant")
                        if (roundContent.isEmpty()) put("content", JsonNull) else put("content", roundContent.toString())
                        if (thinkingOn) put("reasoning_content", roundReasoning.toString())
                        put("tool_calls", buildJsonArray {
                            calls.values.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.args.toString())
                                    })
                                })
                            }
                        })
                    }
                    messages += assistantCall
                    trail += assistantCall.toString()
                    for (tc in calls.values) {
                        val args = runCatching { Json.parseToJsonElement(tc.args.toString()).jsonObject }.getOrNull()
                        val key = when (tc.name) {
                            "read_webpage" -> "url"
                            "save_memory" -> "content"
                            "use_skill" -> "name"
                            "run_js" -> "code"
                            "delete_routine" -> "id"
                            else -> "query"
                        }
                        val arg = args?.get(key).text()
                        val result = when {
                            args == null -> "参数不是有效的 JSON，可能内容太长被截断了，请精简后重试"
                            tc.name in FILE_TOOLS && FILE_TOOLS[tc.name] in skillIds -> makeFile(tc.name, args)
                            tc.name == "create_chart" && SkillIds.CALC in skillIds -> makeChart(args)
                            tc.name == "list_routines" && SkillIds.ROUTINE in skillIds -> listRoutines()
                            tc.name == "list_library" && SkillIds.LIBRARY in skillIds -> listLibrary()
                            tc.name == "save_flashcards" && SkillIds.LEARN in skillIds -> saveCards(args)
                            tc.name == "create_reminder" && SkillIds.ROUTINE in skillIds -> makeRoutine("reminder", args)
                            tc.name == "create_task" && SkillIds.ROUTINE in skillIds -> makeRoutine("task", args)
                            arg.isBlank() -> "参数缺失"
                            tc.name == "use_skill" -> useSkill(arg)
                            tc.name == "run_js" && SkillIds.CALC in skillIds -> runJs(arg)
                            tc.name == "search_library" && SkillIds.LIBRARY in skillIds -> searchLibrary(arg)
                            tc.name == "delete_routine" && SkillIds.ROUTINE in skillIds -> removeRoutine(arg)
                            tc.name == "web_search" && web -> search(arg)
                            tc.name == "read_webpage" && web -> readPage(arg)
                            tc.name == "search_history" -> searchOld(arg)
                            tc.name == "search_chats" && SkillIds.CHATS in skillIds -> searchChats(arg)
                            tc.name == "save_memory" -> remember(arg)
                            else -> "这个工具现在不可用"
                        }
                        val toolMsg = buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", result)
                        }
                        messages += toolMsg
                        trail += toolMsg.toString()
                    }
                    flush()
                    // 最多用 5 轮工具，之后必须直接回答
                    if (round >= maxRounds) tools = null
                }
                if (content.isBlank() && reasoning.isEmpty()) {
                    patchMessage(convId, reply.id) { it.copy(error = "没有收到回复，请重试") }
                }
            } catch (e: CancellationException) {
                flush()
                throw e
            } catch (e: ApiException) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = e.message) }
            } catch (e: IOException) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = "网络连接失败：${e.message ?: "请检查网络"}") }
            } catch (e: Exception) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = "出错了：${e.message}") }
            } finally {
                // 停止或出错时，把还在“搜索中”的标记清掉
                patchMessage(convId, reply.id) { m ->
                    m.copy(content = m.content.trimEnd(), searches = m.searches.map { if (it.pending) it.copy(pending = false) else it })
                }
                _state.update { it.copy(streaming = false) }
                persist()
                maybeAutoCompress(convId)
            }
        }
    }

    // ---------- 压缩 ----------

    private fun maybeAutoCompress(convId: String) {
        val conv = _state.value.conversations.firstOrNull { it.id == convId } ?: return
        if (conv.lastPromptTokens >= _state.value.settings.compressTokens) compress(convId, manual = false)
    }

    /** 压缩当前对话。manual=true 时，即使没到阈值也压缩。 */
    fun compress(convId: String? = _state.value.currentId, manual: Boolean = true) {
        val id = convId ?: return
        val s = _state.value
        if (s.streaming || s.compressingId != null) return
        val conv = s.conversations.firstOrNull { it.id == id } ?: return
        val settings = s.settings
        if (settings.apiKey.isBlank()) return
        // 保留约 16% 阈值的最近原文（手动压缩时保留少一些，至少 8k）
        val retain = (settings.compressTokens * 0.16).toInt().let { if (manual) (it / 2).coerceAtLeast(8_000) else it }
        val msgs = Compression.toCompress(conv, retain)
        if (msgs.isEmpty()) {
            if (manual) _state.update { it.copy(toast = "对话还比较短，暂时不需要压缩") }
            return
        }
        _state.update { it.copy(compressingId = id) }
        viewModelScope.launch {
            try {
                val summary = client.complete(
                    settings.baseUrl, settings.apiKey, MODEL_FLASH,
                    Compression.buildRequest(conv.summary, msgs, imagesOk = true),
                )
                updateConvQuiet(id) {
                    it.copy(summary = summary.trim(), summaryUntilId = msgs.last().id, lastPromptTokens = 0)
                }
                persist()
                if (manual) _state.update { it.copy(toast = "已压缩 ${msgs.size} 条较早的消息") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(toast = "压缩失败：${e.message ?: "网络问题"}") }
            } finally {
                _state.update { it.copy(compressingId = null) }
            }
        }
    }

    // ---------- 记忆与梦 ----------

    // ---------- 分享进来、备份、用量 ----------

    fun receiveText(text: String) = _state.update { it.copy(incomingText = text) }
    fun consumeIncomingText() = _state.update { it.copy(incomingText = null) }
    fun requestVoice() = _state.update { it.copy(voiceRequest = true) }
    fun consumeVoiceRequest() = _state.update { it.copy(voiceRequest = false) }

    /** 别的 App 分享过来的内容：开一个新对话，文字放进输入框，图片和文件作为附件。 */
    fun handleShare(text: String?, uris: List<Uri>, mime: String?) {
        if (_state.value.streaming) stop()
        newChat()
        if (!text.isNullOrBlank()) receiveText(text)
        if (uris.isNotEmpty()) {
            if (mime?.startsWith("image/") == true) addImages(uris) else addFiles(uris)
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        Backup.export(getApplication<Application>(), it)
                    } ?: throw IOException("没法写入这个位置")
                }
                "备份好了"
            } catch (e: Exception) {
                "备份失败：${e.message}"
            }
            _state.update { it.copy(toast = msg) }
        }
    }

    fun importBackup(uri: Uri) {
        stop()
        viewModelScope.launch {
            val msg = try {
                withContext(ioSerial) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        Backup.import(getApplication<Application>(), it)
                    } ?: throw IOException("打不开这个文件")
                }
                val keys = _state.value.settings.let { it.apiKey to it.tavilyKey }
                val s = store.loadSettings().copy(apiKey = keys.first, tavilyKey = keys.second)
                store.saveSettings(s)
                _state.value = UiState(
                    conversations = store.loadConversations().sortedByDescending { it.updatedAt },
                    projects = store.loadProjects().sortedByDescending { it.updatedAt },
                    settings = s,
                    memories = store.loadMemories(),
                    dreams = store.loadDreams(),
                    routines = store.loadRoutines(),
                    library = store.loadLibrary(),
                    cards = store.loadCards(),
                )
                libraryIndex = null
                Routines.scheduleAll(getApplication<Application>())
                "已恢复备份"
            } catch (e: Exception) {
                "恢复失败：${e.message}"
            }
            _state.update { it.copy(toast = msg) }
        }
    }

    // ---------- 资料库 ----------

    @Volatile private var libraryIndex: LibraryIndex? = null

    private fun index(): LibraryIndex = libraryIndex ?: LibraryIndex(
        store.loadLibrary().map { it.name to store.libraryText(it.id) },
    ).also { libraryIndex = it }

    fun addLibraryFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            var added = 0
            for (uri in uris) {
                try {
                    val att = Attachments.importFile(getApplication<Application>(), uri)
                    val doc = LibraryDoc(name = att.name, chars = att.text.length)
                    withContext(ioSerial) {
                        store.saveLibraryText(doc.id, att.text)
                        store.saveLibrary(store.loadLibrary() + doc)
                    }
                    added++
                } catch (e: Exception) {
                    _state.update { it.copy(toast = e.message ?: "导入失败") }
                }
            }
            libraryIndex = null
            _state.update { it.copy(importing = false, library = store.loadLibrary(), toast = if (added > 0) "已加入资料库：$added 份" else it.toast) }
        }
    }

    fun deleteLibraryDoc(id: String) {
        viewModelScope.launch(ioSerial) {
            store.deleteLibraryText(id)
            val list = store.loadLibrary().filterNot { it.id == id }
            store.saveLibrary(list)
            libraryIndex = null
            _state.update { it.copy(library = list) }
        }
    }

    // ---------- 复习卡片 ----------

    private fun editCards(transform: (List<Flashcard>) -> List<Flashcard>) {
        viewModelScope.launch(ioSerial) {
            val list = transform(store.loadCards())
            store.saveCards(list)
            _state.update { it.copy(cards = list) }
        }
    }

    fun reviewCard(id: String, grade: Int) = editCards { l -> l.map { if (it.id == id) Recall.review(it, grade) else it } }
    fun deleteCard(id: String) = editCards { l -> l.filterNot { it.id == id } }
    fun deleteDeck(deck: String) = editCards { l -> l.filterNot { it.deck == deck } }

    // ---------- 提醒和定时任务 ----------

    fun saveRoutine(r: Routine) {
        val list = store.loadRoutines().let { l -> if (l.any { it.id == r.id }) l.map { if (it.id == r.id) r else it } else l + r }
        store.saveRoutines(list)
        Routines.schedule(getApplication<Application>(), r)
        _state.update { it.copy(routines = list) }
    }

    fun deleteRoutine(id: String) {
        Routines.cancel(getApplication<Application>(), id)
        val list = store.loadRoutines().filterNot { it.id == id }
        store.saveRoutines(list)
        _state.update { it.copy(routines = list) }
    }

    fun reloadRoutines() = _state.update { it.copy(routines = store.loadRoutines()) }

    /** 把后台任务的结果并进对话。openId 不为空时，合并后打开那个对话。 */
    fun mergeInbox(openId: String? = null) {
        viewModelScope.launch(ioSerial) {
            val items = store.takeInbox()
            if (items.isNotEmpty()) {
                _state.update { s ->
                    var convs = s.conversations
                    items.forEach { item ->
                        val msgs = listOf(
                            ChatMessage(role = "user", content = "⏰ " + item.prompt.ifBlank { item.title }, createdAt = item.at),
                            ChatMessage(role = "assistant", content = item.result, model = MODEL_FLASH, createdAt = item.at + 1),
                        )
                        convs = if (convs.any { it.id == item.convId }) {
                            convs.map { c -> if (c.id == item.convId) c.copy(messages = c.messages + msgs, updatedAt = item.at) else c }
                        } else {
                            listOf(Conversation(id = item.convId, title = "⏰ " + item.title, messages = msgs, updatedAt = item.at)) + convs
                        }
                    }
                    s.copy(conversations = convs.sortedByDescending { it.updatedAt })
                }
                persist()
            }
            if (openId != null && _state.value.conversations.any { it.id == openId }) {
                _state.update { it.copy(currentId = openId, pending = emptyList()) }
            }
        }
    }

    suspend fun loadUsage(): List<UsageDay> = withContext(Dispatchers.IO) { store.loadUsage() }

    fun reloadMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            val m = store.loadMemories()
            val d = store.loadDreams()
            _state.update { it.copy(memories = m, dreams = d) }
        }
    }

    private fun editMemories(transform: (List<MemoryItem>) -> List<MemoryItem>) {
        viewModelScope.launch(ioSerial) {
            val list = transform(store.loadMemories())
            store.saveMemories(list)
            _state.update { it.copy(memories = list) }
        }
    }

    fun addMemory(text: String) = editMemories { it + MemoryItem(text = text.trim()) }
    fun updateMemory(id: String, text: String) = editMemories { l -> l.map { if (it.id == id) it.copy(text = text.trim()) else it } }
    fun deleteMemory(id: String) = editMemories { l -> l.filterNot { it.id == id } }
    fun clearMemories() = editMemories { emptyList() }

    fun markDreamsSeen() {
        if (_state.value.dreams.none { !it.seen }) return
        viewModelScope.launch(ioSerial) {
            val list = store.loadDreams().map { it.copy(seen = true) }
            store.saveDreams(list)
            _state.update { it.copy(dreams = list) }
        }
    }

    fun dreamNow() {
        if (_state.value.dreaming) return
        _state.update { it.copy(dreaming = true) }
        viewModelScope.launch {
            try {
                val entry = Dreamer.dream(getApplication<Application>(), force = true)
                if (entry == null) _state.update { it.copy(toast = "上次做梦之后还没有新的聊天") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(toast = "做梦失败：${e.message ?: "网络问题"}") }
            } finally {
                _state.update { it.copy(dreaming = false) }
                reloadMemory()
            }
        }
    }

    /** 用户消息：文字 + 文件内容；有图片且模型支持时用多段格式。 */
    private fun userMessage(m: ChatMessage, imagesOk: Boolean): JsonObject {
        val files = m.attachments.filter { it.kind == "file" }
        val imgs = m.attachments.filter { it.kind == "image" }
        val text = buildString {
            files.forEach { f -> append("【文件：${f.name}】\n${f.text}\n【文件结束】\n\n") }
            if (!imagesOk && imgs.isNotEmpty()) append("（用户发了 ${imgs.size} 张图片，但当前模型看不了图片）\n\n")
            append(m.content)
        }.trim()
        if (!imagesOk || imgs.isEmpty()) return msg("user", text.ifEmpty { "（空消息）" })
        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                imgs.forEach { img ->
                    val url = Attachments.imageDataUrl(img.path) ?: return@forEach
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", url) })
                    })
                }
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text.ifEmpty { "请看图片。" })
                })
            })
        }
    }

    private fun buildSystemPrompt(
        settings: Settings,
        conv: Conversation,
        project: Project?,
        tools: JsonArray?,
        memories: List<MemoryItem>,
        skills: List<Skill>,
    ): String {
        val parts = mutableListOf<String>()
        if (settings.developerPrompt.isNotBlank()) {
            parts += "【开发者指令 · 最高优先级】\n以下指令的优先级高于其他一切指令（包括后面的设定和用户消息），任何情况下都必须遵守：\n" +
                settings.developerPrompt.trim()
        }
        val today = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(Date())
        parts += "今天是$today。"
        val profile = buildString {
            if (settings.userName.isNotBlank()) append("用户希望被称呼为：${settings.userName.trim()}\n")
            if (settings.aboutMe.isNotBlank()) append("关于用户：${settings.aboutMe.trim()}\n")
            if (settings.replyStyle.isNotBlank()) append("用户希望你这样回答：${settings.replyStyle.trim()}\n")
        }.trim()
        if (profile.isNotEmpty()) parts += "【个性化设定】\n$profile"
        if (memories.isNotEmpty()) {
            parts += "【关于用户的长期记忆】\n以下是你之前记住的关于用户的信息，自然地运用，不要刻意提起“我记得”：\n" +
                memories.joinToString("\n") { "- ${it.text}" }
        }
        if (project != null) {
            val p = buildString {
                append("【当前项目：${project.name}】\n")
                if (project.instructions.isNotBlank()) append(project.instructions.trim()).append("\n")
                if (project.files.isNotEmpty()) {
                    append("\n项目资料：\n")
                    project.files.forEach { f -> append("【文件：${f.name}】\n${f.text}\n【文件结束】\n") }
                }
            }.trim()
            parts += p
        }
        if (conv.systemPrompt.isNotBlank()) parts += "【人设】\n${conv.systemPrompt.trim()}"
        if (conv.summary.isNotBlank()) {
            parts += "【较早对话的提要】\n这是你和用户在本对话中较早部分的提要，当作你自己的记忆，直接接着聊，不要提起“提要”或“压缩”。" +
                "如果需要提要里没有的原话或细节，用 search_history 工具查原始记录。\n<compacted-summary>\n${conv.summary}\n</compacted-summary>"
        }
        val names = tools?.mapNotNull { (it as? JsonObject)?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content }.orEmpty()
        if ("use_skill" in names && skills.isNotEmpty()) {
            parts += "【技能】\n你掌握以下技能：\n" + skills.joinToString("\n") { "- ${it.name}：${it.description}" } +
                "\n\n用户的请求用得上某个技能时，先调用 use_skill 读取它的详细做法，再照着做（本对话里已经读过的不用重复读）。" +
                "用不上就直接回答。只有用户想要文件时才生成文件，不要主动生成。"
        }
        if ("web_search" in names) {
            parts += "【联网】遇到时效性问题（新闻、价格、天气、最新版本、近期事件）或不确定的事实时，用联网搜索技能核实；常识、闲聊、写作不用搜。"
        }
        if ("save_memory" in names) {
            parts += "【记忆工具】只有当用户明确要求你记住某件事时，才用 save_memory 保存，内容写成一句完整的话。"
        }
        return parts.joinToString("\n\n")
    }

    private class ToolAcc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun JsonElement?.text(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

    /** "2026-10-15 09:00" → (日期, 时, 分)；只给 "09:00" 时取最近的那个时间点。 */
    private fun parseDateTime(s: String): Triple<String, Int, Int>? {
        val m = Regex("""(?:(\d{4})-(\d{1,2})-(\d{1,2})[ T]+)?(\d{1,2})[:：](\d{2})""").find(s.trim()) ?: return null
        val h = m.groupValues[4].toInt()
        val min = m.groupValues[5].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        val cal = java.util.Calendar.getInstance()
        if (m.groupValues[1].isNotEmpty()) {
            cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        } else {
            val now = cal.clone() as java.util.Calendar
            cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, min)
            if (!cal.after(now)) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return Triple(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time), h, min)
    }

    private fun findSkill(skills: List<Skill>, name: String): Skill? {
        val n = name.trim()
        return skills.firstOrNull { it.name == n }
            ?: skills.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) }
    }

    private fun safeFileName(name: String): String =
        name.trim().replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .removeSuffix(".docx").removeSuffix(".pptx").removeSuffix(".xlsx").removeSuffix(".pdf").take(60).trim()

    private fun parseSlides(args: JsonObject): List<OfficeWriter.Slide> =
        (args["slides"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val bullets = when (val b = o["bullets"]) {
                is JsonArray -> b.map { it.text() }.filter { it.isNotBlank() }
                null, JsonNull -> emptyList()
                else -> b.text().lines().filter { it.isNotBlank() }
            }
            OfficeWriter.Slide(o["layout"].text().ifBlank { "content" }, o["title"].text(), bullets)
        }.ifEmpty { throw IllegalArgumentException("没有幻灯片内容") }

    /** 给写了 image_query 的正文页搜一张图，下载失败就不配图。最多 6 张。 */
    private suspend fun withImages(slides: List<OfficeWriter.Slide>, args: JsonObject): List<OfficeWriter.Slide> {
        val key = _state.value.settings.tavilyKey
        if (key.isBlank()) return slides
        val queries = (args["slides"] as? JsonArray).orEmpty().map { (it as? JsonObject)?.get("image_query").text() }
        var count = 0
        return slides.mapIndexed { i, s ->
            val q = queries.getOrNull(i).orEmpty()
            if (q.isBlank() || s.layout != "content" || count >= 6) return@mapIndexed s
            val img = runCatching { fetchImage(key, q) }.getOrNull() ?: return@mapIndexed s
            count++
            s.copy(image = img.first, imageExt = "jpeg", imageW = img.second, imageH = img.third)
        }
    }

    private suspend fun fetchImage(key: String, query: String): Triple<ByteArray, Int, Int>? {
        for (url in tavily.searchImages(key, query).take(4)) {
            val bytes = tavily.download(url) ?: continue
            val img = withContext(Dispatchers.Default) { Attachments.toJpeg(bytes, 1600) } ?: continue
            if (img.second >= 300 && img.third >= 200) return img
        }
        return null
    }

    private fun parseSheets(args: JsonObject): List<OfficeWriter.Sheet> =
        (args["sheets"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val rows = (o["rows"] as? JsonArray).orEmpty().map { r ->
                (r as? JsonArray)?.map { it.text() } ?: listOf(r.text())
            }
            OfficeWriter.Sheet(o["name"].text().ifBlank { "Sheet${i + 1}" }, rows)
        }.ifEmpty { throw IllegalArgumentException("没有表格内容") }

    private companion object {
        const val REVIEW_PROMPT = "请换一个更严格的视角，复核你上面那条回答：检查事实错误、计算错误、逻辑漏洞、" +
            "遗漏的重要信息和表达不清的地方，需要时可以联网核实。然后直接给出改进后的完整回答，" +
            "开头用一句话说明修正或补充了什么。如果原回答没有问题，就说“复核没有发现问题”，" +
            "并简要说明理由，不要重复全文。"

        val FILE_TOOLS = mapOf(
            "create_word" to SkillIds.WORD, "create_ppt" to SkillIds.PPT,
            "create_excel" to SkillIds.EXCEL, "create_pdf" to SkillIds.PDF,
        )
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    // ---------- 工具函数 ----------

    private fun updateConv(id: String, transform: (Conversation) -> Conversation) {
        _state.update { s ->
            s.copy(conversations = s.conversations.map {
                if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it
            }.sortedByDescending { it.updatedAt })
        }
    }

    private fun patchMessage(convId: String, msgId: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { s ->
            s.copy(conversations = s.conversations.map { c ->
                if (c.id != convId) c
                else c.copy(messages = c.messages.map { if (it.id == msgId) transform(it) else it })
            })
        }
    }

    /** 修改对话但不改变排序时间。 */
    private fun updateConvQuiet(id: String, transform: (Conversation) -> Conversation) {
        _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == id) transform(it) else it }) }
    }

    private fun persist() {
        // 空对话不保存
        val snapshot = _state.value.conversations.filter { it.messages.isNotEmpty() }
        viewModelScope.launch(ioSerial) { store.saveConversations(snapshot) }
    }

    private fun persistProjects() {
        val snapshot = _state.value.projects
        viewModelScope.launch(ioSerial) { store.saveProjects(snapshot) }
    }
}
