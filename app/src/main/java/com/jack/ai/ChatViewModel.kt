package com.jack.ai

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.ai.api.ApiException
import com.jack.ai.chat.Agent
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.tools.ToolHost
import com.jack.ai.tools.ToolSession
import com.jack.ai.tools.ToolUi
import com.jack.ai.tools.search
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.api.StreamEvent
import com.jack.ai.api.TavilyClient
import com.jack.ai.api.formatResultsForModel
import com.jack.ai.api.toSources
import com.jack.ai.data.Attachment
import com.jack.ai.data.Attachments
import com.jack.ai.data.Backup
import com.jack.ai.data.ChartWriter
import com.jack.ai.data.ChatExport
import com.jack.ai.data.CalendarTool
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
import com.jack.ai.data.PyRunner
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
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.endpoint
import com.jack.ai.data.Provider
import com.jack.ai.data.ProviderModel
import com.jack.ai.data.guessReasoning
import com.jack.ai.data.guessVision
import com.jack.ai.data.modelChoices
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
        refreshWidget()
    }

    private fun refreshWidget() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { AskWidget.refresh(app) }
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

    fun togglePin(id: String) {
        updateConvQuiet(id) { it.copy(pinned = !it.pinned) }
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

    // ---------- 导出 ----------

    val exporting = MutableStateFlow(false)

    /** 把当前对话导出成文件，准备好后交给界面分享。 */
    fun exportChat(format: ChatExport.Format, onReady: (File) -> Unit) {
        val conv = _state.value.current?.takeIf { it.messages.isNotEmpty() } ?: return
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ChatExport.export(getApplication<Application>(), conv, format) }
                onReady(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 长图太大时可能内存不够
                _state.update { it.copy(toast = if (e is OutOfMemoryError) "对话太长，生成长图时内存不够，换成 PDF 试试" else "导出失败：${e.message}") }
            } finally {
                exporting.value = false
            }
        }
    }

    // ---------- 设置 ----------

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = _state.value.settings
        _state.update { it.copy(settings = transform(it.settings)) }
        val after = _state.value.settings
        store.saveSettings(after)
        if (before.dreamEnabled != after.dreamEnabled) Dreamer.schedule(getApplication<Application>(), after.dreamEnabled)
    }

    /** 获取某个服务的模型列表，返回 (可用的地址, [模型 id 和价格])。 */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Pair<String, List<Pair<String, com.jack.ai.data.Price?>>> =
        client.listModels(baseUrl, apiKey)

    /** 查其他服务的余额；这个服务不支持查询时返回 null。 */
    suspend fun providerBalance(p: Provider): String? = try {
        client.providerBalance(p.baseUrl, p.apiKey)
    } catch (e: ApiException) {
        e.message ?: "查询失败"
    } catch (e: IOException) {
        "网络连接失败"
    }

    fun guessModel(id: String, price: com.jack.ai.data.Price? = null) =
        ProviderModel(id, vision = guessVision(id), reasoning = guessReasoning(id), price = price)

    fun saveProvider(p: Provider) {
        updateSettings { s ->
            val list = if (s.providers.any { it.id == p.id }) s.providers.map { if (it.id == p.id) p else it } else s.providers + p
            fixModel(s.copy(providers = list))
        }
    }

    fun deleteProvider(id: String) = updateSettings { s -> fixModel(s.copy(providers = s.providers.filterNot { it.id == id })) }

    /** 当前选的模型没了（服务删了、模型取消勾选），换成第一个能用的。 */
    private fun fixModel(s: Settings): Settings {
        val choices = s.modelChoices()
        return if (choices.any { it.key == s.model } || choices.isEmpty()) s else s.copy(model = choices.first().key)
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
        if (atts.any { it.kind == "image" } && _state.value.settings.endpoint()?.vision == false) {
            _state.update { it.copy(toast = "当前模型看不了图片，请点顶部的模型名换一个能看图的") }
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

    /** 改写一条自己发的消息：它之后的对话全部丢掉，用新内容重新回答。 */
    fun editMessage(messageId: String, text: String) {
        val conv = _state.value.current ?: return
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0 || conv.messages[idx].role != "user") return
        val old = conv.messages[idx]
        val trimmed = text.trim()
        if (trimmed.isEmpty() && old.attachments.isEmpty()) return
        // 正在回答时先停下，等旧的收尾（它会把 streaming 置回 false）再开始
        val running = job
        stop()
        viewModelScope.launch {
            running?.join()
            val cur = _state.value.conversations.firstOrNull { it.id == conv.id } ?: return@launch
            val i = cur.messages.indexOfFirst { it.id == messageId }
            if (i < 0) return@launch
            // 这条消息自己的附件留着，只清理后面的
            cleanup(cur.messages.drop(i + 1))
            updateConv(conv.id) { c ->
                c.copy(
                    title = if (i == 0) trimmed.ifEmpty { c.title }.take(20).replace('\n', ' ') else c.title,
                    messages = c.messages.take(i) + old.copy(content = trimmed, createdAt = System.currentTimeMillis()),
                )
            }
            startReply(conv.id)
        }
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
        // 有 DeepSeek Key 时用 V4 Pro 复核，否则用当前模型
        val model = if (_state.value.settings.apiKey.isNotBlank()) MODEL_PRO else _state.value.settings.model
        startReply(conv.id, modelOverride = model, extraUser = REVIEW_PROMPT)
    }

    private fun startReply(convId: String, modelOverride: String? = null, extraUser: String? = null) {
        val settings = _state.value.settings
        val conv = _state.value.conversations.first { it.id == convId }
        val project = _state.value.project(conv.projectId)
        val model = modelOverride ?: settings.model
        val thinking = if (modelOverride != null && settings.thinking == ThinkLevel.OFF) ThinkLevel.HIGH else settings.thinking
        val reply = ChatMessage(role = "assistant", model = model)
        updateConv(convId) { it.copy(messages = it.messages + reply) }

        val ep = settings.endpoint(model)
        if (ep == null || ep.apiKey.isBlank()) {
            patchMessage(convId, reply.id) {
                it.copy(error = if (ep == null) "这个模型已经不在列表里了，请点顶部的模型名重新选一个"
                else if (ep.deepseek) "还没有填写 DeepSeek API Key，请点左上角菜单 → 设置" else "这个服务还没有填写 API Key，请到设置里检查")
            }
            persist()
            return
        }
        val skills = settings.activeSkills()
        // 思考内容只有 DeepSeek 要求原样发回；其他服务不发，免得不认识这个字段报错
        val thinkingOn = ep.deepseek && thinking.effort != null
        val images = ep.vision
        val memories = if (settings.memoryEnabled) store.loadMemories() else emptyList()
        val toolSet = PromptBuilder.toolSet(settings, skills, conv, project)

        val promptInput = PromptBuilder.Input(settings, conv, project, memories, skills, vision = images, sendReasoning = thinkingOn, extraUser = extraUser)
        fun buildHistory(withTools: Boolean, flat: Boolean = false): MutableList<JsonObject> =
            PromptBuilder.history(promptInput, if (withTools) toolSet else null, flat)

        _state.update { it.copy(streaming = true) }
        job = viewModelScope.launch {
            val session = ToolSession(toolHost, settings, conv, project, skills, object : ToolUi {
                override fun started(label: String) =
                    patchMessage(convId, reply.id) { it.copy(searches = it.searches + SearchRecord(label, pending = true)) }

                override fun finished(label: String, record: SearchRecord) = patchMessage(convId, reply.id) { m ->
                    m.copy(searches = m.searches.map { if (it.query == label && it.pending) record else it })
                }

                /** 把生成或修改过的文件挂到这条回答上，显示成卡片。 */
                override fun attach(file: File) {
                    val gen = GeneratedFile(file.name, file.absolutePath, ToolSession.mimeOf(file.name), file.length())
                    patchMessage(convId, reply.id) { m -> m.copy(files = m.files.filterNot { it.path == gen.path } + gen) }
                }
            })
            val agent = Agent({ msgs, t -> client.stream(ep, msgs, thinking, settings.temperature, t) }, sendReasoning = thinkingOn)
            val reasoning = agent.reasoning
            val content = agent.content
            var lastFlush = 0L
            fun flush() {
                val r = reasoning.toString()
                val c = content.toString()
                val t = agent.trail.toList()
                patchMessage(convId, reply.id) { it.copy(reasoning = r, content = c, trail = t) }
            }
            try {
                agent.run(
                    messages = buildHistory(toolSet != null),
                    tools = toolSet,
                    maxRounds = { session.maxRounds },
                    execute = session::execute,
                    onText = {
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 60) { lastFlush = now; flush() }
                    },
                    onUsage = { ev ->
                        val total = ev.promptTokens + ev.completionTokens
                        updateConvQuiet(convId) { it.copy(lastPromptTokens = total) }
                        recordSpend(convId, ep, ev.promptTokens, ev.cacheHit, ev.completionTokens)
                    },
                    onRound = { flush() },
                    // 带工具的请求被拒（比如旧记录格式接口不认）：先去掉旧的工具调用过程再试，工具照样能用；
                    // 还不行就不用工具，改成“先搜再答”
                    recovery = Agent.Recovery { attempt ->
                        when {
                            toolSet == null -> null
                            attempt == 1 -> buildHistory(true, flat = true) to toolSet
                            attempt == 2 -> {
                                val q = conv.messages.last { it.role == "user" }.content.take(300)
                                val fresh = buildHistory(false)
                                if (session.web && q.isNotBlank()) {
                                    val results = session.search(q)
                                    fresh.add(1.coerceAtMost(fresh.size), msg("system",
                                        "以下是联网搜索结果，回答时参考并用 [编号](链接) 标注来源：\n\n$results"))
                                }
                                fresh to null
                            }
                            else -> null
                        }
                    },
                )
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
        val ep = settings.backgroundEndpoint() ?: return
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
                // 压缩的花费也算进这个对话
                val counted = DeepSeekClient { e, p, h, c -> recordSpend(id, e, p, h, c) }
                val summary = counted.complete(
                    ep,
                    Compression.buildRequest(conv.summary, msgs, imagesOk = ep.vision),
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
                val old = _state.value.settings
                val loaded = store.loadSettings()
                // 备份里没有 Key，沿用手机上现有的
                val s = loaded.copy(apiKey = old.apiKey, tavilyKey = old.tavilyKey, providers = loaded.providers.map { p ->
                    p.copy(apiKey = old.providers.firstOrNull { it.id == p.id }?.apiKey.orEmpty())
                })
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

    /** 项目大份资料的检索索引，按项目缓存；项目内容变了（updatedAt 变了）就重建。 */
    private val projectIndexes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, LibraryIndex>>()

    private fun projectIndex(p: Project): LibraryIndex {
        projectIndexes[p.id]?.let { (at, idx) -> if (at == p.updatedAt) return idx }
        val idx = LibraryIndex(p.files.filter { it.text.length > PromptBuilder.PROJECT_INLINE_CHARS }.map { it.name to it.text })
        projectIndexes[p.id] = p.updatedAt to idx
        return idx
    }

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
            AskWidget.refresh(getApplication<Application>())
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
        refreshWidget()
    }

    fun deleteRoutine(id: String) {
        Routines.cancel(getApplication<Application>(), id)
        val list = store.loadRoutines().filterNot { it.id == id }
        store.saveRoutines(list)
        _state.update { it.copy(routines = list) }
        refreshWidget()
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

    private fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun JsonElement?.text(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

    companion object {
        const val REVIEW_PROMPT = "请换一个更严格的视角，复核你上面那条回答：检查事实错误、计算错误、逻辑漏洞、" +
            "遗漏的重要信息和表达不清的地方，需要时可以联网核实。然后直接给出改进后的完整回答，" +
            "开头用一句话说明修正或补充了什么。如果原回答没有问题，就说“复核没有发现问题”，" +
            "并简要说明理由，不要重复全文。"

    }

    // ---------- 工具函数 ----------

    /** 工具改 App 数据时走这里。 */
    private val toolHost = object : ToolHost {
        override val app: Application get() = getApplication()
        override val store: Store get() = this@ChatViewModel.store
        override val tavily: TavilyClient get() = this@ChatViewModel.tavily
        override fun conversations() = _state.value.conversations
        override fun setMemories(list: List<MemoryItem>) = _state.update { it.copy(memories = list) }
        override fun editCards(transform: (List<Flashcard>) -> List<Flashcard>) = this@ChatViewModel.editCards(transform)
        override fun saveRoutine(r: Routine) = this@ChatViewModel.saveRoutine(r)
        override fun deleteRoutine(id: String) = this@ChatViewModel.deleteRoutine(id)
        override fun libraryIndex() = index()
        override fun projectIndex(p: Project) = this@ChatViewModel.projectIndex(p)
        override fun refreshWidget() = this@ChatViewModel.refreshWidget()
    }

    /** 一次请求的上下文大概由哪些部分组成（名字, 估算 token），统计页用。conv 为空时按新对话算。 */
    fun contextBreakdown(conv: Conversation?): List<Pair<String, Int>> {
        val st = _state.value
        val c = conv ?: Conversation()
        val memories = if (st.settings.memoryEnabled) st.memories else emptyList()
        return PromptBuilder.contextBreakdown(st.settings, c, st.project(c.projectId), memories, st.settings.activeSkills())
    }

    /** 记一笔用量：写进每日统计（当场算钱），再累加到对话上。 */
    private fun recordSpend(convId: String, ep: com.jack.ai.data.Endpoint, prompt: Int, hit: Int?, completion: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val cost = store.addUsage(ep, prompt, hit, completion)
            updateConvQuiet(convId) {
                it.copy(
                    spentTokens = it.spentTokens + prompt + completion,
                    requests = it.requests + 1,
                    spentPrompt = it.spentPrompt + prompt,
                    spentHit = it.spentHit + (hit ?: 0),
                    spentCompletion = it.spentCompletion + completion,
                    spentCost = it.spentCost + (cost ?: 0.0),
                    unpriced = it.unpriced || cost == null,
                )
            }
            persist()
        }
    }

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
