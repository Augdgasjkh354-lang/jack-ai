package com.jack.ai

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
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
import com.jack.ai.data.Diagram
import com.jack.ai.data.DiagramWriter
import com.jack.ai.data.TextEdit
import com.jack.ai.data.Workspace
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
        conv?.let { cleanup(it.messages); if (it.projectId == null) deleteWorkspace(it.id) }
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
        viewModelScope.launch(Dispatchers.IO) {
            Workspace.root(getApplication<Application>()).listFiles()?.filterNot { it.name.startsWith("p_") }?.forEach { it.deleteRecursively() }
        }
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
        deleteWorkspace("p_$id")
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
    fun addFiles(uris: List<Uri>) = importAll(uris) { Attachments.importFile(getApplication<Application>(), it, keepOriginal = true) }

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
        att?.let { deleteImages(listOf(it)); deleteStaged(listOf(it)) }
    }

    private fun deleteStaged(list: List<Attachment>) {
        val dirs = list.filter { it.kind == "file" && it.path.contains("/staging/") }.mapNotNull { File(it.path).parentFile }
        if (dirs.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { dirs.forEach { runCatching { it.deleteRecursively() } } }
    }

    /** 发送时把附件放进对话文件夹：文件挪过去，图片复制一份。 */
    private fun moveToWorkspace(conv: Conversation, atts: List<Attachment>): List<Attachment> {
        val app = getApplication<Application>()
        val dir = Workspace.dir(app, Workspace.key(conv))
        return atts.map { a ->
            runCatching {
                when {
                    a.kind == "file" && a.path.contains("/staging/") -> {
                        val src = File(a.path)
                        val dst = Workspace.uniqueFile(dir, a.name)
                        if (!src.renameTo(dst)) { src.copyTo(dst); src.delete() }
                        src.parentFile?.delete()
                        a.copy(path = dst.absolutePath)
                    }
                    a.kind == "image" && a.path.isNotEmpty() -> {
                        val name = a.name.substringBeforeLast('.').ifBlank { "图片" } + ".jpg"
                        File(a.path).copyTo(Workspace.uniqueFile(dir, name))
                        a
                    }
                    else -> a
                }
            }.getOrDefault(a)
        }
    }

    /** 删除消息时，顺带删掉它们的图片和生成的文件。 */
    private fun cleanup(msgs: List<ChatMessage>) {
        deleteImages(msgs.flatMap { it.attachments })
        val app = getApplication<Application>()
        // 对话文件夹里的文件跟着对话走，不随单条消息删除
        val paths = msgs.flatMap { m -> m.files.map { it.path } }.filterNot { Workspace.isInside(app, it) }
        if (paths.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
    }

    private fun deleteWorkspace(key: String) {
        val dir = Workspace.dir(getApplication<Application>(), key)
        viewModelScope.launch(Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
    }

    // ---------- 对话文件夹（给界面用） ----------

    fun workspaceFiles(conv: Conversation): List<File> = Workspace.files(Workspace.dir(getApplication<Application>(), Workspace.key(conv)))

    fun addWorkspaceFiles(conv: Conversation, uris: List<Uri>, onDone: () -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dir = Workspace.dir(app, Workspace.key(conv))
                uris.forEach { uri ->
                    runCatching {
                        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "文件"
                        val f = Workspace.uniqueFile(dir, name)
                        app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    }.onFailure { e -> _state.update { it.copy(toast = "添加失败：${e.message}") } }
                }
            }
            onDone()
        }
    }

    fun deleteWorkspaceFile(file: File, onDone: () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { Workspace.delete(file) }; onDone() }
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
        val moved = moveToWorkspace(conv, atts)
        updateConv(id) { c ->
            c.copy(
                title = if (c.messages.isEmpty()) title else c.title,
                messages = c.messages + ChatMessage(role = "user", content = trimmed, attachments = moved),
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
        val app = getApplication<Application>()
        val wsDir = Workspace.dir(app, Workspace.key(conv))
        val wsCache = File(wsDir, ".cache")
        val toolSet = buildJsonArray {
            add(SkillTools.LIST_FILES); add(SkillTools.READ_FILE); add(SkillTools.WRITE_FILE); add(SkillTools.EDIT_FILE)
            if (skills.isNotEmpty()) add(SkillTools.USE_SKILL)
            if (web) { add(SkillTools.WEB_SEARCH); add(SkillTools.READ_WEBPAGE) }
            if (SkillIds.WORD in skillIds) add(SkillTools.CREATE_WORD)
            if (SkillIds.PPT in skillIds) add(SkillTools.CREATE_PPT)
            if (SkillIds.EXCEL in skillIds) add(SkillTools.CREATE_EXCEL)
            if (SkillIds.PDF in skillIds) add(SkillTools.CREATE_PDF)
            if (SkillIds.CHATS in skillIds) add(SkillTools.SEARCH_CHATS)
            if (SkillIds.CALC in skillIds) { add(SkillTools.RUN_JS); add(SkillTools.CREATE_CHART); add(SkillTools.CREATE_DIAGRAM) }
            if (SkillIds.LIBRARY in skillIds) { add(SkillTools.SEARCH_LIBRARY); add(SkillTools.LIST_LIBRARY) }
            if (SkillIds.LEARN in skillIds) add(SkillTools.SAVE_FLASHCARDS)
            if (SkillIds.ROUTINE in skillIds) {
                add(SkillTools.CREATE_REMINDER); add(SkillTools.CREATE_TASK)
                add(SkillTools.LIST_ROUTINES); add(SkillTools.DELETE_ROUTINE)
                add(SkillTools.SET_ALARM); add(SkillTools.CANCEL_ALARM)
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
            /** 把生成或修改过的文件挂到这条回答上，显示成卡片。 */
            fun attach(file: File) {
                val gen = GeneratedFile(file.name, file.absolutePath, mimeOf(file.name), file.length())
                patchMessage(convId, reply.id) { m -> m.copy(files = m.files.filterNot { it.path == gen.path } + gen) }
            }
            suspend fun create(tool: String, args: JsonObject, base: String, ext: String, label: String): String =
                runTool("生成 $label：$base.$ext") {
                    val file = withContext(Dispatchers.IO) { Workspace.uniqueFile(wsDir, "$base.$ext") }
                    try {
                        renderFile(tool, args, file, wsCache)
                    } catch (e: Exception) {
                        file.delete(); throw e
                    }
                    withContext(Dispatchers.IO) { Workspace.saveSource(file, tool, args) }
                    attach(file)
                    val shown = if (ext == "png") "图已生成并显示在聊天里" else "已生成文件「${file.name}」，用户可以在聊天里打开或分享"
                    "$shown。文件保存在对话文件夹里，以后要修改就用 edit_file 改它的底稿，不要重新生成。" to
                        SearchRecord("生成 $label：${file.name}")
                }
            suspend fun makeChart(args: JsonObject) =
                create("create_chart", args, safeFileName(args["title"].text()).ifBlank { "图表" }, "png", "图表")
            suspend fun makeDiagram(args: JsonObject): String {
                val kind = if (args["type"].text() == "flowchart") "流程图" else "思维导图"
                return create("create_diagram", args, safeFileName(args["title"].text()).ifBlank { kind }, "png", kind)
            }
            suspend fun makeFile(kind: String, args: JsonObject): String {
                val base = safeFileName(args["filename"].text()).ifBlank { "未命名" }
                val (ext, label) = when (kind) {
                    "create_word" -> "docx" to "Word"
                    "create_ppt" -> "pptx" to "PPT"
                    "create_pdf" -> "pdf" to "PDF"
                    else -> "xlsx" to "Excel"
                }
                return create(kind, args, base, ext, label)
            }
            fun wsFile(name: String): File = Workspace.find(wsDir, name) ?: throw TextEdit.EditException(
                "对话文件夹里没有「$name」。" + Workspace.files(wsDir).let { l ->
                    if (l.isEmpty()) "文件夹是空的。" else "现有文件：" + l.joinToString("、") { it.name }
                })
            suspend fun listFiles() = runTool("查看文件夹") {
                val list = withContext(Dispatchers.IO) { Workspace.files(wsDir).map { "${it.name}｜${sizeText(it.length())}｜${Workspace.editKind(it)}" } }
                (if (list.isEmpty()) "对话文件夹是空的。" else list.joinToString("\n")) to SearchRecord("查看文件夹")
            }
            suspend fun readFile(args: JsonObject): String {
                val name = args["name"].text()
                return runTool("查看 $name") {
                    val file = wsFile(name)
                    val text = withContext(Dispatchers.IO) { Workspace.readText(file) }
                    val lines = text.lines()
                    val start = (args["start_line"].text().toIntOrNull() ?: 1).coerceIn(1, lines.size.coerceAtLeast(1))
                    val max = (args["max_lines"].text().toIntOrNull() ?: 400).coerceIn(1, 2000)
                    val part = lines.drop(start - 1).take(max)
                    var body = part.joinToString("\n")
                    if (body.length > 40_000) body = body.take(40_000) + "\n…（太长，后面截断了，用 start_line 接着读）"
                    val end = start + part.size - 1
                    val head = "「${file.name}」共 ${lines.size} 行" +
                        (if (Workspace.hasSource(file)) "，下面是你生成它时的底稿，edit_file 改的就是这份底稿" else "") +
                        (if (start > 1 || end < lines.size) "，这里是第 $start–$end 行" else "") + "：\n"
                    head + body to SearchRecord("查看 ${file.name}")
                }
            }
            suspend fun writeFile(args: JsonObject): String {
                val name = Workspace.safeName(args["name"].text())
                return runTool("写入 $name") {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in setOf("docx", "pptx", "xlsx", "pdf", "png", "jpg", "jpeg")) {
                        throw TextEdit.EditException("write_file 只能写文字文件。Word/PPT/Excel/PDF 用对应的生成工具，已有的用 edit_file 改")
                    }
                    val file = File(wsDir, name)
                    withContext(Dispatchers.IO) {
                        wsDir.mkdirs()
                        file.writeText(args["content"].text())
                    }
                    attach(file)
                    "已写入「${file.name}」（${file.length()} 字节）。" to SearchRecord("写入 ${file.name}")
                }
            }
            suspend fun editFile(args: JsonObject): String {
                val name = args["name"].text()
                return runTool("修改 $name") {
                    val file = wsFile(name)
                    val old = args["old_text"].text()
                    val new = args["new_text"].text()
                    val all = args["replace_all"].text() == "true"
                    val src = withContext(Dispatchers.IO) { Workspace.source(file) }
                    val n = if (src != null) {
                        val (tool, a) = src
                        val (edited, count) = TextEdit.replace(Workspace.sourceText(tool, a), old, new, all)
                        val newArgs = Workspace.argsFromText(tool, a, edited)
                        renderFile(tool, newArgs, file, wsCache)
                        withContext(Dispatchers.IO) { Workspace.saveSource(file, tool, newArgs) }
                        count
                    } else withContext(Dispatchers.IO) {
                        when {
                            Workspace.ext(file) == "docx" -> {
                                val (bytes, count) = TextEdit.replaceInDocx(file.readBytes(), old, new, all)
                                file.writeBytes(bytes); count
                            }
                            Workspace.isText(file) -> {
                                val (text, count) = TextEdit.replace(file.readText(), old, new, all)
                                file.writeText(text); count
                            }
                            else -> throw TextEdit.EditException("「${file.name}」这种文件没法直接修改")
                        }
                    }
                    attach(file)
                    "已修改「${file.name}」，替换了 $n 处，其他内容没动。" to SearchRecord("修改 ${file.name}")
                }
            }
            suspend fun setAlarm(args: JsonObject): String {
                val time = args["time"].text()
                return runTool("设闹钟 $time") {
                    val m = TIME.find(time) ?: throw IllegalArgumentException("时间要写成 HH:mm")
                    val h = m.groupValues[1].toInt()
                    val min = m.groupValues[2].toInt()
                    if (h !in 0..23 || min !in 0..59) throw IllegalArgumentException("时间不对")
                    val days = (args["days"] as? JsonArray).orEmpty().mapNotNull { it.text().toIntOrNull() }.filter { it in 1..7 }.distinct()
                    val label = args["label"].text()
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, min)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    // 1=周一 … 7=周日 → Calendar 的 2…7、1
                    if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it % 7 + 1 }))
                    startClock(intent)
                    val hm = String.format(Locale.US, "%02d:%02d", h, min)
                    val repeat = if (days.isEmpty()) "一次性" else "每周" + days.sorted().joinToString("、") { "一二三四五六日"[it - 1].toString() } + "重复"
                    "已在手机时钟里设好 $hm 的闹钟（$repeat${if (label.isNotBlank()) "，标签「$label」" else ""}）。" to
                        SearchRecord("设闹钟 $hm")
                }
            }
            suspend fun cancelAlarm(args: JsonObject): String {
                val time = args["time"].text()
                val label = args["label"].text()
                return runTool("关闹钟 ${time.ifBlank { label }}") {
                    val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val m = TIME.find(time)
                    when {
                        m != null -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                            .putExtra(AlarmClock.EXTRA_HOUR, m.groupValues[1].toInt())
                            .putExtra(AlarmClock.EXTRA_MINUTES, m.groupValues[2].toInt())
                        label.isNotBlank() -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        else -> throw IllegalArgumentException("要给出闹钟的时间或标签")
                    }
                    startClock(intent)
                    "已让时钟 App 关掉这个闹钟。有的手机会弹出确认页面，需要用户点一下。" to SearchRecord("关闹钟 ${time.ifBlank { label }}")
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
                            tc.name == "create_diagram" && SkillIds.CALC in skillIds -> makeDiagram(args)
                            tc.name == "list_files" -> listFiles()
                            tc.name == "read_file" -> readFile(args)
                            tc.name == "write_file" -> writeFile(args)
                            tc.name == "edit_file" -> editFile(args)
                            tc.name == "set_alarm" && SkillIds.ROUTINE in skillIds -> setAlarm(args)
                            tc.name == "cancel_alarm" && SkillIds.ROUTINE in skillIds -> cancelAlarm(args)
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
            files.forEach { f ->
                // 放进文件夹后可能改了名（重名时加了编号），以文件夹里的名字为准
                val name = if (f.path.isNotEmpty() && !f.path.contains("/staging/")) File(f.path).name else f.name
                if (f.text.isBlank()) append("【文件：$name】（已放进对话文件夹，内容没法直接显示，需要时用 read_file 读）\n\n")
                else append("【文件：$name】\n${f.text}\n【文件结束】\n\n")
            }
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
        if ("edit_file" in names) {
            // 文件列表不放进系统提示：列表一变，整段对话的缓存就失效了
            parts += "【对话文件夹】本对话有一个文件夹，用户发来的文件和你生成的文件都存在里面，不确定有哪些时用 list_files 看。" +
                "用户要改已有的文件时，用 edit_file 只替换要改的那一段，不要整份重新生成；不确定原文时先用 read_file 看。" +
                "用户发来的文件内容已经在消息里的，不用再读。"
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

    /** 按工具名和参数生成文件。先写到临时文件，成功了再替换，失败不会弄坏原文件。 */
    private suspend fun renderFile(tool: String, args: JsonObject, file: File, cache: File) {
        val slides = if (tool == "create_ppt") withImages(parseSlides(args), args, cache) else null
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".tmp-" + file.name)
            try {
                tmp.outputStream().buffered().use { out ->
                    when (tool) {
                        "create_word" -> OfficeWriter.writeDocx(args["markdown"].text(), out)
                        "create_ppt" -> OfficeWriter.writePptx(slides!!, args["theme"].text().ifBlank { "blue" }, out)
                        "create_pdf" -> PdfWriter.write(args["markdown"].text(), out)
                        "create_excel" -> OfficeWriter.writeXlsx(parseSheets(args), out)
                        "create_chart" -> renderChart(args, out)
                        "create_diagram" -> renderDiagram(args, out)
                        else -> throw IllegalArgumentException("不认识的文件类型")
                    }
                }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) throw IOException("保存文件失败")
                }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun renderChart(args: JsonObject, out: java.io.OutputStream) {
        val labels = (args["labels"] as? JsonArray).orEmpty().map { it.text() }
        val series = (args["series"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val values = (o["values"] as? JsonArray).orEmpty().map { it.text().toDoubleOrNull() ?: 0.0 }
            ChartWriter.Series(o["name"].text(), values)
        }
        ChartWriter.render(args["type"].text(), args["title"].text().ifBlank { "图表" }, args["unit"].text(), labels, series, out)
    }

    private fun renderDiagram(args: JsonObject, out: java.io.OutputStream) {
        val title = args["title"].text().trim()
        if (args["type"].text() == "flowchart") {
            val nodes = (args["nodes"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o["id"].text().trim()
                if (id.isEmpty()) null else Diagram.FNode(id, o["label"].text().ifBlank { id }, o["shape"].text().ifBlank { "box" })
            }
            val edges = (args["edges"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val from = o["from"].text().trim()
                val to = o["to"].text().trim()
                if (from.isEmpty() || to.isEmpty()) null else Diagram.FEdge(from, to, o["label"].text())
            }
            if (nodes.isEmpty() && edges.isEmpty()) throw IllegalArgumentException("流程图没有节点")
            val layout = Diagram.flow(nodes, edges, args["direction"].text() == "LR", DiagramWriter.measure)
            DiagramWriter.render(layout, title, mindmap = false, out = out)
        } else {
            val outline = args["outline"].text()
            if (outline.isBlank()) throw IllegalArgumentException("思维导图需要 outline")
            val root = Diagram.parseOutline(outline, title)
            val layout = Diagram.tree(root, DiagramWriter.measure)
            DiagramWriter.render(layout, if (root.label == title) "" else title, mindmap = true, out = out)
        }
    }

    private suspend fun startClock(intent: Intent) = withContext(Dispatchers.Main) {
        try {
            getApplication<Application>().startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("手机上没找到能设闹钟的时钟 App")
        } catch (e: SecurityException) {
            throw IllegalStateException("没有设闹钟的权限")
        }
    }

    /** PPT 配图存一份，修改 PPT 重新生成时不用再搜一遍。 */
    private fun imageCacheFile(cache: File, query: String) =
        File(cache, "img-" + query.trim().lowercase().hashCode().toUInt() + ".jpg")

    private suspend fun cachedImage(cache: File, query: String): Triple<ByteArray, Int, Int>? = withContext(Dispatchers.IO) {
        val f = imageCacheFile(cache, query)
        if (!f.exists()) return@withContext null
        val bytes = f.readBytes()
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) null else Triple(bytes, o.outWidth, o.outHeight)
    }

    private suspend fun saveImage(cache: File, query: String, img: Triple<ByteArray, Int, Int>) = withContext(Dispatchers.IO) {
        runCatching { cache.mkdirs(); imageCacheFile(cache, query).writeBytes(img.first) }
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
    private suspend fun withImages(slides: List<OfficeWriter.Slide>, args: JsonObject, cache: File): List<OfficeWriter.Slide> {
        val key = _state.value.settings.tavilyKey
        if (key.isBlank()) return slides
        val queries = (args["slides"] as? JsonArray).orEmpty().map { (it as? JsonObject)?.get("image_query").text() }
        var count = 0
        return slides.mapIndexed { i, s ->
            val q = queries.getOrNull(i).orEmpty()
            if (q.isBlank() || s.layout != "content" || count >= 6) return@mapIndexed s
            val img = runCatching { cachedImage(cache, q) ?: fetchImage(key, q)?.also { saveImage(cache, q, it) } }.getOrNull()
                ?: return@mapIndexed s
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

    companion object {
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

        val TIME = Regex("""(\d{1,2})[:：](\d{2})""")

        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "docx" -> MIME_DOCX
            "pptx" -> MIME_PPTX
            "xlsx" -> MIME_XLSX
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "md", "markdown" -> "text/markdown"
            else -> "text/plain"
        }

        fun sizeText(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
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
