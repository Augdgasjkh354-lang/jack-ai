package com.jack.ai

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.ai.api.ApiException
import com.jack.ai.chat.PromptBuilder
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
        val skillIds = skills.map { it.id }.toSet()
        val web = SkillIds.WEB in skillIds
        // 思考内容只有 DeepSeek 要求原样发回；其他服务不发，免得不认识这个字段报错
        val thinkingOn = ep.deepseek && thinking.effort != null
        val images = ep.vision
        val compressed = conv.summary.isNotBlank()
        val memories = if (settings.memoryEnabled) store.loadMemories() else emptyList()
        val app = getApplication<Application>()
        val wsDir = Workspace.dir(app, Workspace.key(conv))
        val wsCache = File(wsDir, ".cache")
        val toolSet = PromptBuilder.toolSet(settings, skills, conv, project)

        val promptInput = PromptBuilder.Input(settings, conv, project, memories, skills, vision = images, sendReasoning = thinkingOn, extraUser = extraUser)
        fun buildHistory(withTools: Boolean, flat: Boolean = false): MutableList<JsonObject> =
            PromptBuilder.history(promptInput, if (withTools) toolSet else null, flat)

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
            suspend fun searchProject(query: String) = runTool("翻阅项目资料：$query") {
                val p = project ?: throw IllegalStateException("不在项目里")
                val hits = withContext(Dispatchers.Default) { projectIndex(p).search(query) }
                (if (hits.isEmpty()) "项目资料里没有找到和“$query”相关的内容。" else hits.joinToString("\n\n") {
                    "【《${it.doc}》第 ${it.part} 段】\n${it.text}"
                }) to SearchRecord("翻阅项目资料：$query")
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
            suspend fun runPython(code: String) = runTool("运行 Python") {
                val r = PyRunner.run(app, code, wsDir)
                r.changed.forEach { attach(it) }
                val files = if (r.changed.isEmpty()) "" else "\n\n新建或改动的文件（已显示在聊天里）：" + r.changed.joinToString("、") { it.name }
                (r.output.ifBlank { "（运行完成，没有输出）" } + files) to SearchRecord("运行 Python")
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
                    else Routine(kind = "task", title = args["title"].text(), prompt = args["prompt"].text(), hour = h, minute = m, repeat = repeat, date = date,
                        offPeak = args["off_peak"].text() == "true")
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
            suspend fun addEvent(args: JsonObject): String {
                val title = args["title"].text().ifBlank { "日程" }
                return runTool("添加日程：$title") {
                    val (begin, dateOnly) = CalendarTool.parse(args["start"].text())
                        ?: throw IllegalArgumentException("start 要写成 yyyy-MM-dd HH:mm 或 yyyy-MM-dd")
                    val allDay = args["all_day"].text() == "true" || dateOnly
                    val end = CalendarTool.parse(args["end"].text())?.first?.takeIf { it > begin }
                        ?: (begin + if (allDay) 86_400_000L else 3_600_000L)
                    val location = args["location"].text()
                    val notes = args["notes"].text()
                    val remind = args["remind_minutes"].text().toIntOrNull() ?: 15
                    val shown = SimpleDateFormat(if (allDay) "M月d日 EEE" else "M月d日 EEE HH:mm", Locale.CHINA).format(Date(begin))
                    val id = if (CalendarTool.canWrite(app)) withContext(Dispatchers.IO) {
                        CalendarTool.insert(app, title, begin, end, allDay, location, notes, remind.takeIf { it >= 0 })
                    } else null
                    if (id != null) {
                        refreshWidget()
                        "已写进手机日历：$shown「$title」${if (remind >= 0) "，提前 $remind 分钟提醒" else ""}。编号 $id。" to
                            SearchRecord("添加日程：$title")
                    } else {
                        startClock(CalendarTool.insertIntent(title, begin, end, allDay, location, notes), "手机上没找到日历 App")
                        "没有日历写入权限（或没有可写的日历），已打开日历 App 的新建页面并填好内容（$shown「$title」），需要用户点一下保存。" +
                            "想让以后自动写入，可以在左侧菜单「提醒和任务」里点「允许读写日历」。" to SearchRecord("打开日历：$title")
                    }
                }
            }
            suspend fun listEvents(args: JsonObject) = runTool("查看日程") {
                if (!CalendarTool.canRead(app)) {
                    "没有日历权限。请用户打开左侧菜单「提醒和任务」，点「允许读写日历」。" to SearchRecord("查看日程", error = "没有权限")
                } else {
                    val from = CalendarTool.dayStart(args["start_date"].text().ifBlank { null })
                    val days = (args["days"].text().toIntOrNull() ?: 1).coerceIn(1, 62)
                    val list = withContext(Dispatchers.IO) { CalendarTool.list(app, from, from + days * 86_400_000L) }
                    val range = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(from)) + if (days > 1) " 起 $days 天" else ""
                    (if (list.isEmpty()) "$range 日历里没有日程。" else "$range 的日程：\n" + list.joinToString("\n") { CalendarTool.describe(it) }) to
                        SearchRecord("查看日程：$range")
                }
            }
            suspend fun deleteEvent(id: String) = runTool("删除日程") {
                val n = id.trim().removePrefix("编号").trim().toLongOrNull() ?: throw IllegalArgumentException("编号不对")
                if (!CalendarTool.canWrite(app)) "没有日历权限，删不了。请用户在「提醒和任务」页面授权。" to SearchRecord("删除日程", error = "没有权限")
                else if (withContext(Dispatchers.IO) { CalendarTool.delete(app, n) }) {
                    refreshWidget()
                    "已删除日程 $n。" to SearchRecord("删除日程 $n")
                }
                else "没找到编号 $n 的日程。" to SearchRecord("删除日程", error = "没找到")
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
                        client.stream(ep, messages, thinking, settings.temperature, tools)
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
                                        updateConvQuiet(convId) { it.copy(lastPromptTokens = total) }
                                        recordSpend(convId, ep, ev.promptTokens, ev.cacheHit, ev.completionTokens)
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
                            "run_js", "run_python" -> "code"
                            "delete_routine", "delete_calendar_event" -> "id"
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
                            tc.name == "add_calendar_event" && SkillIds.CALENDAR in skillIds -> addEvent(args)
                            tc.name == "list_calendar_events" && SkillIds.CALENDAR in skillIds -> listEvents(args)
                            arg.isBlank() -> "参数缺失"
                            tc.name == "use_skill" -> useSkill(arg)
                            tc.name == "run_js" && SkillIds.CALC in skillIds -> runJs(arg)
                            tc.name == "run_python" && SkillIds.PYTHON in skillIds -> runPython(arg)
                            tc.name == "search_library" && SkillIds.LIBRARY in skillIds -> searchLibrary(arg)
                            tc.name == "delete_routine" && SkillIds.ROUTINE in skillIds -> removeRoutine(arg)
                            tc.name == "delete_calendar_event" && SkillIds.CALENDAR in skillIds -> deleteEvent(arg)
                            tc.name == "web_search" && web -> search(arg)
                            tc.name == "read_webpage" && web -> readPage(arg)
                            tc.name == "search_history" -> searchOld(arg)
                            tc.name == "search_project" -> searchProject(arg)
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

    private suspend fun startClock(intent: Intent, missing: String = "手机上没找到能设闹钟的时钟 App") = withContext(Dispatchers.Main) {
        try {
            getApplication<Application>().startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException(missing)
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
