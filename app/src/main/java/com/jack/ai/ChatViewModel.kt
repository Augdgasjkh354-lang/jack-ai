package com.jack.ai

import android.app.Application
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
import com.jack.ai.features.AppCore
import com.jack.ai.features.BackupFeature
import com.jack.ai.features.CompressFeature
import com.jack.ai.features.FilesFeature
import com.jack.ai.features.KnowledgeFeature
import com.jack.ai.features.ProjectsFeature
import com.jack.ai.features.RoutinesFeature
import com.jack.ai.features.SettingsFeature
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.api.TavilyClient
import com.jack.ai.data.Attachment
import com.jack.ai.data.ChatExport
import com.jack.ai.data.Workspace
import com.jack.ai.data.Flashcard
import com.jack.ai.data.LibraryDoc
import com.jack.ai.data.Routine
import com.jack.ai.data.Routines
import com.jack.ai.data.UsageDay
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.DreamEntry
import com.jack.ai.data.Dreamer
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.activeSkills
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Preset
import com.jack.ai.data.Project
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.MODEL_PRO
import com.jack.ai.data.endpoint
import com.jack.ai.data.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** 一次「执行前确认」：标题、要确认的内容、确认按钮的字。 */
data class ConfirmRequest(val id: String, val title: String, val body: String, val action: String)

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
    /** 工具执行前等用户确认（比如发邮件），界面弹窗。 */
    val confirm: ConfirmRequest? = null,
) {
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
    val currentProjectId: String? get() = current?.projectId ?: if (currentId == null) draftProjectId else null
    fun project(id: String?): Project? = id?.let { pid -> projects.firstOrNull { it.id == pid } }
}

/**
 * 界面的总入口。具体活交给各个功能块（features/），回答循环在 chat/Agent，工具在 tools/。
 * 这里留下的是对话本身：新建、切换、删除、发消息、生成回答。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val client = DeepSeekClient(store::addUsage)
    private val tavily = TavilyClient()
    private val core = AppCore(app, store, viewModelScope).also {
        it.stop = { stop() }
        it.stopAndWait = { val j = job; stop(); j?.join() }
    }
    private val _state = core.state
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    private val files = FilesFeature(core)
    private val projects = ProjectsFeature(core, files)
    private val knowledge = KnowledgeFeature(core)
    private val routines = RoutinesFeature(core)
    private val settingsFeature = SettingsFeature(core, client)
    private val backup = BackupFeature(core, knowledge)
    private val compression = CompressFeature(core)

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
        core.refreshWidget()
        // 数据文件坏了被自动恢复过，告诉用户一声
        val fixed = generateSequence { Store.recovered.poll() }.toList()
        if (fixed.isNotEmpty()) {
            val lost = fixed.any { it.endsWith(":empty") }
            core.toast(if (lost) "有数据文件损坏且没有副本，已清空并保留坏文件；可以从备份恢复" else "有数据文件损坏，已自动换用最近的副本")
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    /** 用户在确认弹窗里点了确认或取消。 */
    fun answerConfirm(id: String, ok: Boolean) = core.answerConfirm(id, ok)

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
        conv?.let {
            files.cleanup(it.messages)
            // 普通对话整个文件夹删掉；项目里的对话只删它自己的记忆文件
            if (it.projectId == null) files.deleteWorkspace(it.id) else knowledge.deleteChatMemory(it)
        }
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
        files.cleanup(all)
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

    // ---------- 发消息 ----------

    /** 返回 true 表示已发出，输入框可以清空。 */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val atts = _state.value.pending
        if ((trimmed.isEmpty() && atts.isEmpty()) || busy() || _state.value.importing) return false
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
        val moved = files.moveToWorkspace(conv, atts)
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
        if (busy()) return
        val msgs = conv.messages.dropLastWhile { it.role == "assistant" }
        if (msgs.isEmpty()) return
        files.cleanup(conv.messages.drop(msgs.size))
        val boundary = conv.summaryUntilId?.let { id -> conv.messages.indexOfFirst { it.id == id } } ?: -1
        updateConv(conv.id) {
            it.copy(
                messages = msgs,
                summary = if (boundary >= msgs.size) "" else it.summary,
                summaryUntilId = if (boundary >= msgs.size) null else it.summaryUntilId,
            )
        }
        startReply(conv.id)
    }

    /** 删除某条消息（以及它之后的所有消息）。 */
    fun deleteFrom(messageId: String) {
        val conv = _state.value.current ?: return
        stop()
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        files.cleanup(conv.messages.drop(idx))
        val boundary = conv.summaryUntilId?.let { id -> conv.messages.indexOfFirst { it.id == id } } ?: -1
        updateConv(conv.id) {
            it.copy(
                messages = it.messages.take(idx),
                summary = if (boundary >= idx) "" else it.summary,
                summaryUntilId = if (boundary >= idx) null else it.summaryUntilId,
            )
        }
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
            files.cleanup(cur.messages.drop(i + 1))
            val boundary = cur.summaryUntilId?.let { id -> cur.messages.indexOfFirst { it.id == id } } ?: -1
            updateConv(conv.id) { c ->
                c.copy(
                    title = if (i == 0) trimmed.ifEmpty { c.title }.take(20).replace('\n', ' ') else c.title,
                    messages = c.messages.take(i) + old.copy(content = trimmed, createdAt = System.currentTimeMillis()),
                    summary = if (boundary >= i) "" else c.summary,
                    summaryUntilId = if (boundary >= i) null else c.summaryUntilId,
                )
            }
            startReply(conv.id)
        }
    }

    /**
     * 停下正在进行的回答。job 留着不清空：下一次回答会先等它收尾完（它会把 streaming 置回 false、保存），
     * 这样点了停止马上再发也不会乱。
     */
    fun stop() {
        job?.cancel()
    }

    /** 有回答正在进行（停止后立刻就不算了，不用等收尾）。 */
    private fun busy() = job?.isActive == true

    /** 让 V4 Pro 复核最后一条回答，并给出改进版。 */
    fun reviewLast() {
        val conv = _state.value.current ?: return
        if (busy()) return
        if (conv.messages.lastOrNull()?.let { it.role == "assistant" && it.content.isNotBlank() } != true) return
        // 有 DeepSeek Key 时用 V4 Pro 复核，否则用当前模型
        val model = if (_state.value.settings.apiKey.isNotBlank()) MODEL_PRO else _state.value.settings.model
        startReply(conv.id, modelOverride = model, extraUser = REVIEW_PROMPT)
    }

    private fun startReply(convId: String, modelOverride: String? = null, extraUser: String? = null) {
        val settings = _state.value.settings
        val conv = _state.value.conversations.firstOrNull { it.id == convId } ?: return
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

        val (chatMemory, projectMemory) = knowledge.memoryTexts(conv)
        val promptInput = PromptBuilder.Input(settings, conv, project, memories, skills, vision = images, sendReasoning = thinkingOn,
            extraUser = extraUser, chatMemory = chatMemory, projectMemory = projectMemory)
        fun buildHistory(withTools: Boolean, flat: Boolean = false): MutableList<JsonObject> =
            PromptBuilder.history(promptInput, if (withTools) toolSet else null, flat)

        val previous = job
        job = viewModelScope.launch {
            // 上一次回答（被停止的）先收尾，免得它的收尾把这次的状态改乱
            previous?.join()
            _state.update { it.copy(streaming = true) }
            val session = ToolSession(toolHost, settings, conv, project, skills, object : ToolUi {
                override fun started(label: String) =
                    patchMessage(convId, reply.id) { it.copy(searches = it.searches + SearchRecord(label, pending = true)) }

                override fun finished(label: String, record: SearchRecord) = patchMessage(convId, reply.id) { m ->
                    m.copy(searches = m.searches.map { if (it.query == label && it.pending) record else it })
                }

                override suspend fun confirm(title: String, body: String, action: String) = core.confirm(title, body, action)

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
                // 拼历史要读图、转码，放到后台线程，免得界面卡
                val history = withContext(Dispatchers.Default) { buildHistory(toolSet != null) }
                agent.run(
                    messages = history,
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
                        core.recordSpend(convId, ep, ev.promptTokens, ev.cacheHit, ev.completionTokens)
                    },
                    onRound = { flush() },
                    // 带工具的请求被拒（比如旧记录格式接口不认）：先去掉旧的工具调用过程再试，工具照样能用；
                    // 还不行就不用工具，改成“先搜再答”
                    recovery = Agent.Recovery { attempt ->
                        when {
                            toolSet == null -> null
                            attempt == 1 -> withContext(Dispatchers.Default) { buildHistory(true, flat = true) } to toolSet
                            attempt == 2 -> {
                                val q = conv.messages.lastOrNull { it.role == "user" }?.content.orEmpty().take(300)
                                val fresh = withContext(Dispatchers.Default) { buildHistory(false) }
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
                compression.maybeAutoCompress(convId)
            }
        }
    }

    // ---------- 分享进来、备份、用量 ----------

    fun receiveText(text: String) = _state.update { it.copy(incomingText = text) }
    fun consumeIncomingText() = _state.update { it.copy(incomingText = null) }
    fun requestVoice() = _state.update { it.copy(voiceRequest = true) }
    fun consumeVoiceRequest() = _state.update { it.copy(voiceRequest = false) }

    /** 别的 App 分享过来的内容：开一个新对话，文字放进输入框，图片和文件作为附件。 */
    fun handleShare(text: String?, uris: List<Uri>, mime: String?) {
        if (busy()) stop()
        newChat()
        if (!text.isNullOrBlank()) receiveText(text)
        if (uris.isNotEmpty()) {
            if (mime?.startsWith("image/") == true) addImages(uris) else addFiles(uris)
        }
    }

    suspend fun loadUsage(): List<UsageDay> = withContext(Dispatchers.IO) { store.loadUsage() }

    private fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
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
        override fun editCards(transform: (List<Flashcard>) -> List<Flashcard>) = knowledge.editCards(transform)
        override fun saveRoutine(r: Routine) = routines.saveRoutine(r)
        override fun deleteRoutine(id: String) = routines.deleteRoutine(id)
        override fun libraryIndex() = knowledge.index()
        override fun projectIndex(p: Project) = projects.projectIndex(p)
        override fun refreshWidget() = core.refreshWidget()
    }

    /** 一次请求的上下文大概由哪些部分组成（名字, 估算 token），统计页用。conv 为空时按新对话算。 */
    fun contextBreakdown(conv: Conversation?): List<Pair<String, Int>> {
        val st = _state.value
        val c = conv ?: Conversation()
        val memories = if (st.settings.memoryEnabled) st.memories else emptyList()
        val (chatMemory, projectMemory) = knowledge.memoryTexts(c)
        return PromptBuilder.contextBreakdown(st.settings, c, st.project(c.projectId), memories, st.settings.activeSkills(),
            chatMemory, projectMemory)
    }

    private fun updateConv(id: String, transform: (Conversation) -> Conversation) = core.updateConv(id, transform)
    private fun patchMessage(convId: String, msgId: String, transform: (ChatMessage) -> ChatMessage) = core.patchMessage(convId, msgId, transform)
    private fun updateConvQuiet(id: String, transform: (Conversation) -> Conversation) = core.updateConvQuiet(id, transform)
    private fun persist() = core.persist()

    // ---------- 交给各功能块（名字不变，界面不用改） ----------

    // 项目
    fun saveProject(project: Project) = projects.saveProject(project)
    fun deleteProject(id: String) = projects.deleteProject(id)
    fun addProjectFile(projectId: String, uri: Uri) = projects.addProjectFile(projectId, uri)

    // 附件和对话文件夹
    fun addImages(uris: List<Uri>) = files.addImages(uris)
    fun addFiles(uris: List<Uri>) = files.addFiles(uris)
    fun removePending(id: String) = files.removePending(id)
    fun workspaceFiles(conv: Conversation): List<File> = files.workspaceFiles(conv)
    fun addWorkspaceFiles(conv: Conversation, uris: List<Uri>, onDone: () -> Unit) = files.addWorkspaceFiles(conv, uris, onDone)
    fun deleteWorkspaceFile(file: File, onDone: () -> Unit) = files.deleteWorkspaceFile(file, onDone)

    // 导出、备份
    val exporting get() = backup.exporting
    fun exportChat(format: ChatExport.Format, onReady: (File) -> Unit) = backup.exportChat(format, onReady)
    fun exportBackup(uri: Uri) = backup.exportBackup(uri)
    fun importBackup(uri: Uri) = backup.importBackup(uri)

    // 设置和模型服务
    fun updateSettings(transform: (Settings) -> Settings) = settingsFeature.updateSettings(transform)
    suspend fun fetchModels(baseUrl: String, apiKey: String) = settingsFeature.fetchModels(baseUrl, apiKey)
    suspend fun providerBalance(p: Provider) = settingsFeature.providerBalance(p)
    fun guessModel(id: String, price: com.jack.ai.data.Price? = null) = settingsFeature.guessModel(id, price)
    fun saveProvider(p: Provider) = settingsFeature.saveProvider(p)
    fun deleteProvider(id: String) = settingsFeature.deleteProvider(id)
    suspend fun checkBalance() = settingsFeature.checkBalance()
    suspend fun testMail(acc: com.jack.ai.data.MailAccount) = settingsFeature.testMail(acc)

    // 压缩
    fun compress(convId: String? = _state.value.currentId, manual: Boolean = true) = compression.compress(convId, manual)

    // 资料库、复习卡片、记忆和梦
    fun addLibraryFiles(uris: List<Uri>) = knowledge.addLibraryFiles(uris)
    fun deleteLibraryDoc(id: String) = knowledge.deleteLibraryDoc(id)
    fun reviewCard(id: String, grade: Int) = knowledge.reviewCard(id, grade)
    fun deleteCard(id: String) = knowledge.deleteCard(id)
    fun deleteDeck(deck: String) = knowledge.deleteDeck(deck)
    fun reloadMemory() = knowledge.reloadMemory()
    fun addMemory(text: String) = knowledge.addMemory(text)
    fun updateMemory(id: String, text: String) = knowledge.updateMemory(id, text)
    fun deleteMemory(id: String) = knowledge.deleteMemory(id)
    fun clearMemories() = knowledge.clearMemories()
    fun markDreamsSeen() = knowledge.markDreamsSeen()
    fun dreamNow() = knowledge.dreamNow()

    // 对话记忆和项目记忆（存在文件夹里的 Markdown 文件）
    fun chatMemory(conv: Conversation) = knowledge.chatMemory(conv)
    fun setChatMemory(conv: Conversation, items: List<String>) = knowledge.setChatMemory(conv, items)
    fun moveToGlobal(conv: Conversation, item: String) = knowledge.moveToGlobal(conv, item)
    fun projectMemory(projectId: String) = knowledge.projectMemory(projectId)
    fun setProjectMemory(projectId: String, items: List<String>) = knowledge.setProjectMemory(projectId, items)
    fun setMemoryFlags(id: String, useGlobal: Boolean, inDream: Boolean) {
        updateConvQuiet(id) { it.copy(useGlobalMemory = useGlobal, inDream = inDream) }
        persist()
    }

    // 提醒和定时任务
    fun saveRoutine(r: Routine) = routines.saveRoutine(r)
    fun deleteRoutine(id: String) = routines.deleteRoutine(id)
    fun reloadRoutines() = routines.reloadRoutines()
    fun mergeInbox(openId: String? = null) = routines.mergeInbox(openId)
}
