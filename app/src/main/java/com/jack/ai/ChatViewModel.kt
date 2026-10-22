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
import com.jack.ai.api.StreamEvent
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
    /** 新对话开始前选的模式：false = Chat，true = Work。 */
    val draftWork: Boolean = false,
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
    /** 从桌面快捷方式进来，要直接打开相机。 */
    val cameraRequest: Boolean = false,
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
    private val dsFiles = com.jack.ai.api.DeepSeekFiles(app.filesDir)
    private val web = com.jack.ai.api.WebClient(tavily) { store.addPaidCall(com.jack.ai.api.WebClient.USAGE_KEY, com.jack.ai.api.WebClient.PRICE) }
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
    private val feedback = com.jack.ai.features.FeedbackFeature(core)
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
        applyMode(_state.value.draftWork)
    }

    /** 新对话开始前切换 Chat / Work。 */
    fun setWork(on: Boolean) {
        _state.update { it.copy(draftWork = on) }
        _state.value.current?.takeIf { it.messages.isEmpty() }?.let { c -> updateConvQuiet(c.id) { it.copy(work = on) } }
        applyMode(on)
    }

    /**
     * Chat 和 Work 各记一套模型和思考强度：切模式时把当前的存进旧模式，换上新模式的。
     * 新模式的模型用不了（比如没填 DeepSeek Key）就保持现在的模型。
     */
    private fun applyMode(work: Boolean) {
        if (_state.value.settings.activeWork == work) return
        updateSettings { st ->
            val saved = if (st.activeWork) st.copy(workModel = st.model, workThinking = st.thinking)
                else st.copy(chatModel = st.model, chatThinking = st.thinking)
            val model = if (work) saved.workModel else saved.chatModel.ifBlank { st.model }
            val thinking = if (work) saved.workThinking else saved.chatThinking ?: st.thinking
            val usable = saved.endpoint(model)?.apiKey?.isNotBlank() == true
            saved.copy(activeWork = work, model = if (usable) model else st.model, thinking = thinking)
        }
    }

    fun select(id: String) {
        stop()
        _state.update { it.copy(currentId = id, pending = emptyList()) }
        _state.value.current?.let { applyMode(it.work) }
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

    // ---------- 发消息 ----------

    /** 返回 true 表示已发出，输入框可以清空。 */
    /** 回答进行中用户插的话，等下一轮工具做完时交给 AI。 */
    private val interjections = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** 正在进行的回答：(对话 id, 回答消息 id)。 */
    @Volatile private var running: Pair<String, String>? = null

    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val atts = _state.value.pending
        val run = running
        if (busy() && run != null && run.first == _state.value.currentId && trimmed.isNotEmpty()) {
            interjections += trimmed
            patchMessage(run.first, run.second) { it.copy(searches = it.searches + SearchRecord("💬 $trimmed")) }
            return true
        }
        if ((trimmed.isEmpty() && atts.isEmpty()) || busy() || _state.value.importing) return false
        if (atts.any { it.kind == "image" } && _state.value.settings.endpoint()?.vision == false) {
            _state.update { it.copy(toast = "当前模型看不了图片，请点顶部的模型名换一个能看图的") }
            return false
        }
        var conv = _state.value.current
        if (conv == null) {
            conv = Conversation(projectId = _state.value.draftProjectId, work = _state.value.draftWork)
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
    /**
     * 回答写到长度上限被截断时，从断的地方接着写，接在同一条消息后面。
     * DeepSeek 用“对话前缀续写”（beta 接口，模型从已有内容直接往下写）；其他服务让它“接着写，不要重复”。
     */
    fun continueWriting() {
        val conv = _state.value.current ?: return
        if (busy()) return
        val m = conv.messages.lastOrNull()?.takeIf { it.role == "assistant" && it.truncated && it.content.isNotBlank() } ?: return
        val settings = _state.value.settings
        val ep0 = settings.endpoint(m.model.ifBlank { settings.model })?.takeIf { it.apiKey.isNotBlank() }
            ?: settings.endpoint()?.takeIf { it.apiKey.isNotBlank() }
        if (ep0 == null) { _state.update { it.copy(toast = "这个模型用不了了，请先选一个能用的模型") }; return }
        val convId = conv.id
        patchMessage(convId, m.id) { it.copy(truncated = false) }
        val previous = job
        job = viewModelScope.launch {
            previous?.join()
            _state.update { it.copy(streaming = true) }
            WorkService.start(getApplication(), convId, conv.title, conv.work)
            try {
                val (chatMemory, projectMemory) = knowledge.memoryTexts(conv)
                val input = PromptBuilder.Input(settings, conv, _state.value.project(conv.projectId),
                    if (settings.memoryEnabled) store.loadMemories() else emptyList(), settings.activeSkills(),
                    vision = false, sendReasoning = false, chatMemory = chatMemory, projectMemory = projectMemory)
                val hist = withContext(Dispatchers.Default) { PromptBuilder.history(input, null, flat = true) }
                val ep = if (ep0.deepseek) {
                    // 最后一条是要接着写的回答，标成前缀
                    val i = hist.indexOfLast { it["role"]?.let { r -> (r as? kotlinx.serialization.json.JsonPrimitive)?.content } == "assistant" }
                    if (i >= 0) hist[i] = buildJsonObject { put("role", "assistant"); put("content", m.content); put("prefix", true) }
                    while (hist.size > i + 1 && i >= 0) hist.removeAt(hist.lastIndex)
                    ep0.copy(baseUrl = ep0.baseUrl.trimEnd('/').removeSuffix("/v1") + "/beta")
                } else {
                    hist += msg("user", "你上一条回答写到长度上限被截断了。从中断的地方直接接着写，不要重复已经写过的内容，不要加开场白。")
                    ep0
                }
                var cut = false
                client.stream(ep, hist, ThinkLevel.OFF, settings.temperature, null).collect { ev ->
                    when (ev) {
                        is StreamEvent.Text -> if (ev.content.isNotEmpty()) patchMessage(convId, m.id) { it.copy(content = it.content + ev.content) }
                        is StreamEvent.Usage -> core.recordSpend(convId, ep0, ev.promptTokens, ev.cacheHit, ev.completionTokens)
                        is StreamEvent.Finish -> cut = ev.reason == "length"
                        else -> {}
                    }
                }
                if (cut) patchMessage(convId, m.id) { it.copy(truncated = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                patchMessage(convId, m.id) { it.copy(truncated = true) }
                _state.update { it.copy(toast = "接着写失败：${e.message ?: "网络问题"}") }
            } finally {
                _state.update { it.copy(streaming = false) }
                persist()
                WorkService.finish(getApplication(), convId, conv.title, "接着写完了", conv.work)
            }
        }
    }

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

    /** 让 Pro 复核最后一条回答，并给出改进版。 */
    /** Work 的回答是不是被打断了：出错、没写出结论、或清单还有没做完的。 */
    fun canContinue(m: ChatMessage): Boolean =
        m.role == "assistant" && (m.error != null || m.content.isBlank() || m.plan.any { it.status != "done" })

    /** 断点续做：带上清单，让 AI 从没做完的那一步接着做。 */
    fun continueWork() {
        val conv = _state.value.current ?: return
        if (busy()) return
        val last = conv.messages.lastOrNull { it.role == "assistant" } ?: return
        val plan = conv.messages.lastOrNull { it.plan.isNotEmpty() }?.plan.orEmpty()
        val extra = buildString {
            append("刚才的任务被打断了，请接着做完。已经做完的不要重做，文件夹里已有的文件先看一眼再决定。")
            if (plan.isNotEmpty()) {
                append("\n当时的清单：\n")
                plan.forEach { append(if (it.status == "done") "- [完成] " else "- [没做完] ").append(it.text).append("\n") }
                append("先用 update_plan 写出最新的清单，再从没做完的那步开始。")
            }
            last.error?.let { append("\n上次停下的原因：$it") }
        }
        startReply(conv.id, extraUser = extra)
    }

    fun reviewLast() {
        val conv = _state.value.current ?: return
        if (busy()) return
        if (conv.messages.lastOrNull()?.let { it.role == "assistant" && it.content.isNotBlank() } != true) return
        // 有 DeepSeek Key 时用 Pro 复核（Pro 发布前官方转给 Flash），否则用当前模型
        val model = if (_state.value.settings.apiKey.isNotBlank()) MODEL_PRO else _state.value.settings.model
        startReply(conv.id, modelOverride = model, extraUser = REVIEW_PROMPT)
    }

    private fun startReply(convId: String, modelOverride: String? = null, extraUser: String? = null) {
        val settings = _state.value.settings
        val conv = _state.value.conversations.firstOrNull { it.id == convId } ?: return
        val project = _state.value.project(conv.projectId)
        val model = modelOverride ?: settings.model
        val lastUser = conv.messages.lastOrNull { it.role == "user" }
        val thinking = (if (modelOverride != null && settings.thinking == ThinkLevel.OFF) ThinkLevel.HIGH else settings.thinking)
            .resolve(lastUser?.content.orEmpty(), conv.work, lastUser?.attachments?.isNotEmpty() == true)
        val reply = ChatMessage(role = "assistant", model = model, appVersion = com.jack.ai.data.appVersion(getApplication()))
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
        var promptInput = PromptBuilder.Input(settings, conv, project, memories, skills, vision = images, sendReasoning = thinkingOn,
            appVersion = reply.appVersion,
            extraUser = extraUser, chatMemory = chatMemory, projectMemory = projectMemory,
            journal = _state.value.dreams.firstOrNull { it.journal.isNotBlank() }?.journal.orEmpty())
        fun buildHistory(withTools: Boolean, flat: Boolean = false): MutableList<JsonObject> =
            PromptBuilder.history(promptInput, if (withTools) toolSet else null, flat)

        val previous = job
        job = viewModelScope.launch {
            // 上一次回答（被停止的）先收尾，免得它的收尾把这次的状态改乱
            previous?.join()
            _state.update { it.copy(streaming = true) }
            // DeepSeek 看图：图片先传一次，之后只发编号；传不上的照旧内联
            if (ep.deepseek && images) {
                val paths = com.jack.ai.data.Compression.liveMessages(conv).flatMap { m -> m.attachments.filter { it.kind == "image" }.map { it.path } }
                if (paths.isNotEmpty()) promptInput = promptInput.copy(fileIds = runCatching { dsFiles.ensure(ep, paths) }.getOrDefault(emptyMap()))
            }
            promptInput = promptInput.copy(contextNote = withContext(Dispatchers.IO) {
                runCatching { com.jack.ai.tools.ContextNote.build(getApplication(), store, conv, skills.map { it.id }.toSet()) }.getOrDefault("")
            })
            var filesDropped = false
            // Work：开前台服务，切到后台也接着干；花费到上限先问一声
            val work = conv.work
            val workTitle = conv.title
            // 不管 Chat 还是 Work，回答期间都开前台服务，切到后台也接着跑
            WorkService.start(getApplication(), convId, workTitle, work)
            var spent = 0.0
            val limit = settings.workCostLimit
            var nextLimit = limit
            val session = ToolSession(toolHost, settings, conv, project, skills, object : ToolUi {
                override fun started(label: String) {
                    patchMessage(convId, reply.id) { it.copy(searches = it.searches + SearchRecord(label, pending = true)) }
                    WorkService.progress(getApplication(), convId, workTitle, label, work)
                }

                override fun plan(items: List<com.jack.ai.data.PlanItem>) {
                    patchMessage(convId, reply.id) { it.copy(plan = items) }
                    val doing = items.firstOrNull { it.status == "doing" }?.text
                    WorkService.progress(getApplication(), convId, workTitle,
                        "${items.count { it.status == "done" }}/${items.size}" + (doing?.let { " · $it" } ?: ""), work)
                }

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
            session.canSee = ep.vision
            val agent = Agent({ msgs, t -> client.stream(ep, msgs, thinking, settings.temperature, t) }, sendReasoning = thinkingOn)
            interjections.clear()
            running = convId to reply.id
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
                    execute = { name, args ->
                        // 用户已经点了“先停下”就不再问：同一轮里剩下的工具调用直接拦掉
                        if (work && limit > 0 && spent >= nextLimit && session.maxRounds != 0) {
                            if (!MainActivity.visible) com.jack.ai.data.Routines.notify(getApplication(), "work-ask-$convId",
                                "需要你确认：$workTitle", "这次任务已经花了 ¥%.2f，打开看看要不要继续".format(spent), openChat = convId)
                            val ok = core.confirm(
                                "这次任务已经花了 ¥%.2f".format(spent),
                                "超过了设定的 ¥%.2f。继续的话，再多花 ¥%.2f 会再问你。".format(nextLimit, limit),
                                "继续",
                            )
                            if (ok) nextLimit = spent + limit
                            else session.maxRounds = 0
                        }
                        if (session.maxRounds == 0) "用户让你先停下（花费到了上限）。不要再调用工具，直接说明目前做到哪一步、还差什么。"
                        else session.execute(name, args)
                    },
                    onText = {
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 60) { lastFlush = now; flush() }
                    },
                    onUsage = { ev ->
                        val total = ev.promptTokens + ev.completionTokens
                        updateConvQuiet(convId) { it.copy(lastPromptTokens = total) }
                        core.recordSpend(convId, ep, ev.promptTokens, ev.cacheHit, ev.completionTokens)
                        if (work) {
                            val c = com.jack.ai.data.Pricing.priceAt(ep, System.currentTimeMillis())
                                ?.let { com.jack.ai.data.Pricing.cost(it, ev.promptTokens, ev.cacheHit ?: 0, ev.completionTokens) } ?: 0.0
                            spent += c
                            val total = spent
                            patchMessage(convId, reply.id) { it.copy(cost = total) }
                        }
                    },
                    onRound = { flush() },
                    // 带工具的请求被拒（比如旧记录格式接口不认）：先去掉旧的工具调用过程再试，工具照样能用；
                    // 还不行就不用工具，改成“先搜再答”
                    interjections = { generateSequence { interjections.poll() }.toList() },
                    images = { session.takeImages() },
                    recovery = Agent.Recovery { tried ->
                        // 先怀疑图片编号失效（过期或被删）：清掉记录，改回内联图片再试一次
                        if (!filesDropped && promptInput.fileIds.isNotEmpty()) {
                            filesDropped = true
                            dsFiles.forget()
                            promptInput = promptInput.copy(fileIds = emptyMap())
                            return@Recovery withContext(Dispatchers.Default) { buildHistory(toolSet != null) } to toolSet
                        }
                        val attempt = if (filesDropped) tried - 1 else tried
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
                if (agent.truncated) {
                    flush()
                    patchMessage(convId, reply.id) { it.copy(truncated = true) }
                }
            } catch (e: CancellationException) {
                flush()
                throw e
            } catch (e: ApiException) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = e.message) }
            } catch (e: IOException) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = "网络连接失败：${e.message ?: "请检查网络"}" + com.jack.ai.api.Net.hint()) }
            } catch (e: Exception) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = "出错了：${e.message}") }
            } finally {
                // 停止或出错时，把还在“搜索中”的标记清掉
                patchMessage(convId, reply.id) { m ->
                    m.copy(content = m.content.trimEnd(), searches = m.searches.map { if (it.pending) it.copy(pending = false) else it })
                }
                running = null
                _state.update { it.copy(streaming = false) }
                persist()
                // 插的话没来得及用上（回答已经收尾了）：等这次结束后当成新消息发出去
                val leftover = generateSequence { interjections.poll() }.toList()
                if (leftover.isNotEmpty()) viewModelScope.launch {
                    job?.join()
                    if (_state.value.currentId == convId) send(leftover.joinToString("\n"))
                }
                run {
                    val m = _state.value.conversations.firstOrNull { it.id == convId }?.messages?.firstOrNull { it.id == reply.id }
                    val result = m?.error ?: m?.content?.takeLast(200)?.trim().orEmpty().ifBlank { "已停止" }
                    WorkService.finish(getApplication(), convId, workTitle, result, work)
                }
                compression.maybeAutoCompress(convId)
            }
        }
    }

    // ---------- 分享进来、备份、用量 ----------

    fun receiveText(text: String) = _state.update { it.copy(incomingText = text) }
    fun consumeIncomingText() = _state.update { it.copy(incomingText = null) }
    fun requestVoice() = _state.update { it.copy(voiceRequest = true) }
    fun consumeVoiceRequest() = _state.update { it.copy(voiceRequest = false) }
    fun requestCamera() = _state.update { it.copy(cameraRequest = true) }
    fun consumeCameraRequest() = _state.update { it.copy(cameraRequest = false) }

    /** 别的 App 分享过来的内容：开一个新对话，文字放进输入框，图片和文件作为附件。 */
    fun handleShare(text: String?, uris: List<Uri>, mime: String?) {
        if (busy()) stop()
        newChat()
        if (!text.isNullOrBlank()) receiveText(text)
        if (uris.isNotEmpty()) {
            if (mime?.startsWith("image/") == true) addImages(uris) else addFiles(uris)
        }
    }

    /**
     * 万能收件箱：分享进来的东西直接发到「收件箱」对话，AI 自动判断类型、提炼重点、归档。
     * 和「问 Jack」不同，不等用户再按发送。
     */
    fun inboxShare(text: String?, uris: List<Uri>, mime: String?) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val atts = uris.mapNotNull { uri ->
                try {
                    val image = mime?.startsWith("image/") == true ||
                        app.contentResolver.getType(uri)?.startsWith("image/") == true
                    if (image) com.jack.ai.data.Attachments.importImage(app, uri)
                    else com.jack.ai.data.Attachments.importFile(app, uri, keepOriginal = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    core.toast("有个文件读不了：${e.message ?: "读取失败"}")
                    null
                }
            }
            if (text.isNullOrBlank() && atts.isEmpty()) return@launch
            val existing = _state.value.conversations.firstOrNull { it.id == com.jack.ai.data.INBOX_ID }
            if (existing == null) {
                val c = Conversation(id = com.jack.ai.data.INBOX_ID, title = "📥 收件箱", systemPrompt = com.jack.ai.data.INBOX_PROMPT, presetName = "收件箱")
                _state.update { it.copy(conversations = listOf(c) + it.conversations) }
            } else if (existing.systemPrompt != com.jack.ai.data.INBOX_PROMPT) {
                // 收件箱的说明更新过，跟着换成最新的
                updateConvQuiet(existing.id) { it.copy(systemPrompt = com.jack.ai.data.INBOX_PROMPT) }
            }
            select(com.jack.ai.data.INBOX_ID)
            // 正在回答的先收尾，再发
            job?.join()
            _state.update { it.copy(pending = atts) }
            send(text.orEmpty())
        }
    }

    suspend fun loadUsage(): List<UsageDay> = withContext(Dispatchers.IO) { store.loadUsage() }
    suspend fun loadToolStats(): List<com.jack.ai.data.ToolStat> = withContext(Dispatchers.IO) { store.loadToolStats() }

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
        override val web get() = this@ChatViewModel.web
        override fun conversations() = _state.value.conversations
        override fun setMemories(list: List<MemoryItem>) = _state.update { it.copy(memories = list) }
        override fun editCards(transform: (List<Flashcard>) -> List<Flashcard>) = knowledge.editCards(transform)
        override fun saveRoutine(r: Routine) = routines.saveRoutine(r)
        override fun deleteRoutine(id: String) = routines.deleteRoutine(id)
        override fun libraryIndex() = knowledge.index()
        override fun projectIndex(p: Project) = projects.projectIndex(p)
        override fun refreshWidget() = core.refreshWidget()
        override fun saveNote(projectId: String?, title: String, content: String) {
            val p = projectId?.let { id -> _state.value.project(id) }
            if (p == null) knowledge.addNote(title, content)
            else {
                projects.saveProject(p.copy(files = p.files + Attachment(kind = "file", name = "$title.md", text = content)))
                // 当场写一次，后面核实时读得到（平时的保存是稍后合并着写的）
                store.saveProjects(_state.value.projects)
            }
        }
    }

    /** 一次请求的上下文大概由哪些部分组成（名字, 估算 token），统计页用。conv 为空时按新对话算。 */
    fun contextBreakdown(conv: Conversation?): List<Pair<String, Int>> {
        val st = _state.value
        val c = conv ?: Conversation()
        val memories = if (st.settings.memoryEnabled) st.memories else emptyList()
        val (chatMemory, projectMemory) = knowledge.memoryTexts(c)
        return PromptBuilder.contextBreakdown(st.settings, c, st.project(c.projectId), memories, st.settings.activeSkills(),
            chatMemory, projectMemory, st.dreams.firstOrNull { it.journal.isNotBlank() }?.journal.orEmpty())
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
    fun reportIssue(msgId: String, note: String) { _state.value.current?.let { feedback.report(it.id, msgId, note) } }
    suspend fun feedbackCount() = feedback.count()
    fun exportFeedback(onReady: (File) -> Unit) = feedback.export(onReady)
    fun clearFeedback() = feedback.clear()
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
    suspend fun saveConnector(server: com.jack.ai.data.McpServer) = settingsFeature.saveConnector(server)
    fun setConnector(server: com.jack.ai.data.McpServer) = settingsFeature.setConnector(server)
    fun deleteConnector(id: String) = settingsFeature.deleteConnector(id)
    suspend fun importProxy(input: String) = settingsFeature.importProxy(input)
    suspend fun refreshProxy() = settingsFeature.refreshProxy()
    suspend fun testProxy() = settingsFeature.testProxy()
    fun clearProxy() = settingsFeature.clearProxy()

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
    fun editJournal(id: String, text: String) = knowledge.editJournal(id, text)
    fun deleteDream(id: String) = knowledge.deleteDream(id)

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
    fun mergeInbox(openId: String? = null) = routines.mergeInbox(openId) { if (it != _state.value.currentId) select(it) }
}
