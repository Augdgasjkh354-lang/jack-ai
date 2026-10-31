package com.jack.ai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.ai.api.ApiException
import com.jack.ai.chat.Agent
import com.jack.ai.chat.ContextSlim
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
import com.jack.ai.data.globalAllowed
import com.jack.ai.data.workMemoryAllowed
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
import com.jack.ai.data.readyMails
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Project
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.MODEL_FLASH
import com.jack.ai.data.endpoint
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    /** 要开始录音纪要（长按图标的「录音纪要」）。 */
    val recordRequest: Boolean = false,
    /** 从桌面快捷方式进来，要直接打开相机。 */
    val cameraRequest: Boolean = false,
    val routines: List<Routine> = emptyList(),
    val library: List<LibraryDoc> = emptyList(),
    val cards: List<Flashcard> = emptyList(),
    /** 工具执行前等用户确认（比如发邮件），界面弹窗。 */
    val confirm: ConfirmRequest? = null,
    /** 正在回答的对话（可以好几个同时答）。切去看别的对话不会打断它们。 */
    val streamingIds: Set<String> = emptySet(),
) {
    /** 有回答正在进行（不管是哪个对话）。 */
    val streaming: Boolean get() = streamingIds.isNotEmpty()
    /** 当前看着的这个对话正在回答。 */
    val streamingHere: Boolean get() = currentId != null && currentId in streamingIds
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
    private val web = com.jack.ai.api.WebClient(tavily, { store.addPaidCall(com.jack.ai.api.WebClient.USAGE_KEY, com.jack.ai.api.WebClient.PRICE) },
            onFree = { store.addPaidCall(it, 0.0) })
    private val core = AppCore(app, store, viewModelScope).also {
        it.stopConv = { id -> stop(id) }
        it.stopAndWait = { val all = jobs.values.toList(); all.forEach { j -> j.cancel() }; all.forEach { j -> j.join() } }
    }
    private val _state = core.state
    val state: StateFlow<UiState> = _state.asStateFlow()
    /** 每个对话正在进行的回答（几个对话可以同时答）。 */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** 每个 Work 对话连着自动接着做了几次（答成功一次、用户发新消息就清零）。 */
    private val autoResumes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val files = FilesFeature(core)
    private val projects = ProjectsFeature(core, files)
    private val knowledge = KnowledgeFeature(core)
    private val routines = RoutinesFeature(core)
    private val settingsFeature = SettingsFeature(core, client)
    private val backup = BackupFeature(core, knowledge)
    private val feedback = com.jack.ai.features.FeedbackFeature(core)
    private val compression = CompressFeature(core)
    private val snapshots = com.jack.ai.chat.MemorySnapshots()

    init {
        com.jack.ai.data.KaviSelf.ensure(getApplication())
        _state.value = UiState(
            conversations = store.loadConversations().sortedByDescending { it.updatedAt },
            projects = store.loadProjects().sortedByDescending { it.updatedAt },
            settings = store.loadSettings(),
            memories = store.loadMemories(),
            dreams = store.loadDreams(),
        )
        Dreamer.schedule(app, _state.value.settings.dreamEnabled)
        com.jack.ai.data.Reader.schedule(app, _state.value.settings.readingEnabled)
        // 桌面图标和设置对不上（比如从备份恢复了别的选择）：照设置改过来
        runCatching { com.jack.ai.ui.AppIcon.ensure(app, _state.value.settings.appIcon) }
        com.jack.ai.data.Proactive.schedule(app, _state.value.settings.proactive)
        // 上次开着按 App 分流、进程被收掉了：授权还在就接着开
        if (_state.value.settings.proxyAppsOn && _state.value.settings.proxyHardened && !com.jack.ai.vpn.KaviVpnService.running && com.jack.ai.vpn.TProxyService.available &&
            runCatching { com.jack.ai.vpn.KaviVpnService.consent(app) == null }.getOrDefault(false)) com.jack.ai.vpn.KaviVpnService.start(app)
        _state.update { it.copy(routines = store.loadRoutines(), library = store.loadLibrary(), cards = store.loadCards()) }
        Routines.scheduleAll(app)
        runCatching { com.jack.ai.data.Updater.schedule(app, _state.value.settings) }
        // 选了本地模型：开 App 后稍等一会在后台加载好，聊天时不用现等
        com.jack.ai.chat.LocalBrain.warmUp(app, _state.value.settings, delayMs = 5_000)
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
        // 不打断别的对话里正在写的回答：它在后台写完，回去就能看到
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
        _state.update { it.copy(currentId = id, pending = emptyList()) }
        _state.value.current?.let { applyMode(it.work) }
    }

    fun delete(id: String) {
        if (busy(id)) stop(id)
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
        jobs.values.forEach { it.cancel() }
        val all = _state.value.conversations.flatMap { it.messages }
        _state.update { it.copy(conversations = emptyList(), currentId = null) }
        files.cleanup(all)
        viewModelScope.launch(Dispatchers.IO) {
            Workspace.root(getApplication<Application>()).listFiles()?.filterNot { it.name.startsWith("p_") }?.forEach { it.deleteRecursively() }
        }
        persist()
    }

    // ---------- 发消息 ----------

    /** 回答进行中用户插的话（按对话分开），等下一轮工具做完时交给 AI。 */
    private val interjections = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()
    private fun interjectionsOf(convId: String) = interjections.getOrPut(convId) { java.util.concurrent.ConcurrentLinkedQueue() }
    /** 正在进行的回答：对话 id → 回答消息 id。 */
    private val running = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 返回 true 表示已发出，输入框可以清空。 */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val atts = _state.value.pending
        val here = _state.value.currentId
        val replyId = here?.let { running[it] }
        // 这个对话正在回答：发的话当插话，下一步就会看到
        if (here != null && busy(here) && replyId != null && trimmed.isNotEmpty()) {
            interjectionsOf(here) += trimmed
            patchMessage(here, replyId) { it.copy(searches = it.searches + SearchRecord("💬 $trimmed")) }
            return true
        }
        if ((trimmed.isEmpty() && atts.isEmpty()) || (here != null && busy(here)) || _state.value.importing) return false
        // Kavi 结束了这个对话：不能再发，新开一个
        if (_state.value.current?.closedReason?.isNotEmpty() == true) {
            _state.update { it.copy(toast = "这个对话已经结束了，开一个新对话吧") }
            return false
        }
        if (tooMany(here)) return false
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
        // 以前在第一条消息时记下最近几场对话；现在每轮都带着经历流里的「刚才」（Timeline），旧对话里记下的照旧
        val recap = conv.recap
        updateConv(id) { c ->
            c.copy(
                title = if (c.messages.isEmpty()) title else c.title,
                messages = c.messages + ChatMessage(role = "user", content = trimmed, attachments = moved),
                recap = recap,
            )
        }
        _state.update { it.copy(pending = emptyList()) }
        autoResumes.remove(id)
        startReply(id)
        return true
    }

    /** 重新生成最后一条回答。 */
    fun regenerate() {
        val conv = _state.value.current ?: return
        if (busy(conv.id) || tooMany(conv.id)) return
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
        if (busy(conv.id) || tooMany(conv.id)) return
        val m = conv.messages.lastOrNull()?.takeIf { it.role == "assistant" && it.truncated && it.content.isNotBlank() } ?: return
        val settings = _state.value.settings
        val ep0 = settings.endpoint(m.model.ifBlank { settings.model })?.takeIf { it.apiKey.isNotBlank() }
            ?: settings.endpoint()?.takeIf { it.apiKey.isNotBlank() }
        if (ep0 == null) { _state.update { it.copy(toast = "这个模型用不了了，请先选一个能用的模型") }; return }
        val convId = conv.id
        patchMessage(convId, m.id) { it.copy(truncated = false) }
        launchReply(convId) { done ->
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
                done()
                persist()
                WorkService.finish(getApplication(), convId, conv.title, "接着写完了", conv.work)
            }
        }
    }

    /**
     * 在这个对话里开一个回答：先等这个对话上一个回答收尾，标上「正在回答」，再跑 block。
     * block 在自己的收尾里调 done() 撤掉标记（自动压缩要在撤掉之后才做得了）；没调的话结束时也会撤。
     * 别的对话的回答不受影响，各答各的。
     */
    private fun launchReply(convId: String, block: suspend (done: () -> Unit) -> Unit) {
        val previous = jobs[convId]
        val j = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            previous?.join()
            _state.update { it.copy(streamingIds = it.streamingIds + convId) }
            var cleared = false
            val done = { if (!cleared) { cleared = true; _state.update { it.copy(streamingIds = it.streamingIds - convId) } } }
            try {
                block(done)
            } finally {
                done()
                jobs.remove(convId, coroutineContext[Job])
            }
        }
        jobs[convId] = j
        j.start()
    }

    /** 对话超过这么多条消息，每轮附加说明里带一小段「我是谁」（防越聊越淡）。 */
    private val ANCHOR_AFTER = 24

    /** 删除消息后能撤销的时间。 */
    private val UNDO_MS = 8_000L

    /** 同时在答的回答最多几个：再多花费容易失控，手机也吃不消。 */
    private fun tooMany(convId: String?): Boolean {
        val others = jobs.filter { (id, j) -> id != convId && j.isActive }.size
        if (others < MAX_PARALLEL) return false
        _state.update { it.copy(toast = "已经有 $MAX_PARALLEL 个回答在同时进行，等其中一个写完再发，或者去那边点停止") }
        return true
    }

    /** 刚删掉、还能撤销的消息：图片和生成的文件先留着，过了撤销时间才真删。 */
    private class Deleted(val convId: String, val keptLastId: String?, val removed: List<ChatMessage>,
                          val summary: String, val summaryUntilId: String?) {
        var cleanup: Job? = null
    }
    private var deleted: Deleted? = null

    /** 删除某条消息以及它之后的所有消息，返回删了几条（0 是没删）。[UNDO_MS] 内可以 [undoDelete]。 */
    fun deleteFrom(messageId: String): Int {
        val conv = _state.value.current ?: return 0
        if (busy(conv.id)) stop(conv.id)
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return 0
        val removed = conv.messages.drop(idx)
        // 上一次删的不等了，现在就清掉
        deleted?.let { d -> d.cleanup?.cancel(); files.cleanup(d.removed) }
        deleted = Deleted(conv.id, conv.messages.getOrNull(idx - 1)?.id, removed, conv.summary, conv.summaryUntilId).also { d ->
            d.cleanup = viewModelScope.launch {
                kotlinx.coroutines.delay(UNDO_MS)
                if (deleted === d) deleted = null
                files.cleanup(d.removed)
            }
        }
        val boundary = conv.summaryUntilId?.let { id -> conv.messages.indexOfFirst { it.id == id } } ?: -1
        updateConv(conv.id) {
            it.copy(
                messages = it.messages.take(idx),
                summary = if (boundary >= idx) "" else it.summary,
                summaryUntilId = if (boundary >= idx) null else it.summaryUntilId,
            )
        }
        persist()
        return removed.size
    }

    /** 把刚删的消息放回去。对话在那之后又有了新消息、或者正在回答，就放不回去了。 */
    fun undoDelete(): Boolean {
        val d = deleted ?: return false
        val cur = _state.value.conversations.firstOrNull { it.id == d.convId }
        if (cur == null || busy(cur.id) || cur.messages.lastOrNull()?.id != d.keptLastId) return false
        deleted = null
        d.cleanup?.cancel()
        updateConv(d.convId) { it.copy(messages = it.messages + d.removed, summary = d.summary, summaryUntilId = d.summaryUntilId) }
        persist()
        return true
    }

    /** 改写一条自己发的消息：它之后的对话全部丢掉，用新内容重新回答。 */
    fun editMessage(messageId: String, text: String) {
        val conv = _state.value.current ?: return
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0 || conv.messages[idx].role != "user") return
        val old = conv.messages[idx]
        val trimmed = text.trim()
        if (trimmed.isEmpty() && old.attachments.isEmpty()) return
        if (tooMany(conv.id)) return
        // 这个对话正在回答时先停下，等旧的收尾（它会撤掉「正在回答」、保存）再开始；别的对话的回答不动
        val running = jobs[conv.id]
        stop(conv.id)
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
                    messages = c.messages.take(i) + old.copy(content = trimmed, createdAt = System.currentTimeMillis(), contextPrefix = null),
                    summary = if (boundary >= i) "" else c.summary,
                    summaryUntilId = if (boundary >= i) null else c.summaryUntilId,
                )
            }
            startReply(conv.id)
        }
    }

    /**
     * 停下某个对话（默认是眼前这个）正在进行的回答，别的对话的不动。job 留在表里：这个对话的下一次回答
     * 会先等它收尾完（撤掉「正在回答」、保存），这样点了停止马上再发也不会乱。
     */
    fun stop(convId: String? = _state.value.currentId) {
        convId?.let { jobs[it]?.cancel() }
    }

    /** 这个对话有回答正在进行（停止后立刻就不算了，不用等收尾）。 */
    private fun busy(convId: String) = jobs[convId]?.isActive == true

    /** 让 V4.1 Flash 复核最后一条回答，并给出改进版。 */
    /** Work 的回答是不是被打断了：出错、没写出结论、或清单还有没做完的。 */
    fun canContinue(m: ChatMessage): Boolean =
        m.role == "assistant" && (m.error != null || m.content.isBlank() || m.plan.any { it.status != "done" })

    /** Work 一时出错（网络、限流、服务端 5xx）才自动接着做；Key 不对、余额不足这类接了也没用。 */
    private fun transient(e: Exception): Boolean = e is IOException || (e is ApiException && (e.code == 408 || e.code == 429 || e.code >= 500))

    /** 断点续做：带上清单，让 AI 从没做完的那一步接着做。 */
    fun continueWork(convId: String? = _state.value.currentId) {
        val conv = _state.value.conversations.firstOrNull { it.id == convId } ?: return
        if (busy(conv.id) || tooMany(conv.id)) return
        val last = conv.messages.lastOrNull { it.role == "assistant" } ?: return
        val planMsg = conv.messages.lastOrNull { it.plan.isNotEmpty() }
        val plan = planMsg?.plan.orEmpty()
        val extra = buildString {
            append("刚才的任务被打断了，请接着做完。已经做完的不要重做，文件夹里已有的文件先看一眼再决定。")
            if (plan.isNotEmpty()) {
                append("\n当时的清单：\n")
                plan.forEach { append(if (it.status == "done") "- [完成] " else "- [没做完] ").append(it.text).append("\n") }
                planMsg?.criteria?.takeIf { it.isNotEmpty() }?.let { c ->
                    append("验收标准：\n")
                    c.forEach { append(if (it.status == "met") "- [已满足] " else "- [未核对] ").append(it.text).append("\n") }
                }
                append("先用 update_plan 写出最新的清单和验收标准，再从没做完的那步开始。")
            }
            last.error?.let { append("\n上次停下的原因：$it") }
        }
        startReply(conv.id, extraUser = extra)
    }

    fun reviewLast() {
        val conv = _state.value.current ?: return
        if (busy(conv.id) || tooMany(conv.id)) return
        if (conv.messages.lastOrNull()?.let { it.role == "assistant" && it.content.isNotBlank() } != true) return
        // 有 DeepSeek Key 时用 V4.1 Flash 复核（比现在的 Pro 强，也便宜），否则用当前模型
        val model = if (_state.value.settings.apiKey.isNotBlank()) MODEL_FLASH else _state.value.settings.model
        startReply(conv.id, modelOverride = model, extraUser = REVIEW_PROMPT)
    }

    private fun startReply(convId: String, modelOverride: String? = null, extraUser: String? = null) {
        val settings = _state.value.settings
        val conv = _state.value.conversations.firstOrNull { it.id == convId } ?: return
        // Kavi 结束了的对话：重新回答、接着做、改消息都不再开始新的回答
        if (conv.closedReason.isNotEmpty()) { _state.update { it.copy(toast = "这个对话已经结束了，开一个新对话吧") }; return }
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
        // 记忆按对话拍快照，聊天中途不变，保住前缀缓存（见 MemorySnapshots）
        val snap = snapshots.get(conv.id, conv.summaryUntilId, System.currentTimeMillis()) {
            val (chat, proj) = knowledge.memoryTexts(conv)
            com.jack.ai.chat.MemorySnapshots.Snap(
                memories = if (settings.memoryEnabled) store.loadMemories() else emptyList(),
                chatMemory = chat, projectMemory = proj,
                journal = Dreamer.recentJournals(_state.value.dreams),
                story = com.jack.ai.data.SelfStory.bio(getApplication()).text,
                lately = com.jack.ai.data.FreeTime.latelyNote(getApplication()),
                workMemory = if (conv.work) knowledge.workMemoryText() else "",
                summaryUntilId = conv.summaryUntilId, usedAt = 0,
            )
        }
        val memories = if (settings.memoryEnabled) snap.memories else emptyList()
        // 工具按需出现的技能：这个对话以前打开过的，加上这条消息里 @ 到的，开场就带着它们的工具
        val asked = lastUser?.content.orEmpty()
        val unlockedStart = conv.skillTools + skills.filter { it.id in com.jack.ai.data.SkillIds.ON_DEMAND && "@${it.name}" in asked }.map { it.id }
        val toolSet = PromptBuilder.toolSet(settings, skills, conv, project, unlockedStart)

        var promptInput = PromptBuilder.Input(settings, conv, project, memories, skills, vision = images, sendReasoning = thinkingOn,
            appVersion = reply.appVersion,
            extraUser = extraUser, chatMemory = snap.chatMemory, projectMemory = snap.projectMemory,
            journal = snap.journal, story = snap.story, lately = snap.lately, workMemory = snap.workMemory)
        fun buildHistory(withTools: Boolean, flat: Boolean = false): MutableList<JsonObject> =
            PromptBuilder.history(promptInput, if (withTools) toolSet else null, flat)

        // 这个对话上一次回答（被停止的）先收尾，免得它的收尾把这次的状态改乱；别的对话的回答照常进行
        launchReply(convId) { done ->
            // DeepSeek 看图：图片先传一次，之后只发编号；传不上的照旧内联
            if (ep.deepseek && images) {
                val paths = com.jack.ai.data.Compression.liveMessages(conv).flatMap { m -> m.attachments.filter { it.kind == "image" }.map { it.path } }
                if (paths.isNotEmpty()) promptInput = promptInput.copy(fileIds = runCatching { dsFiles.ensure(ep, paths) }.getOrDefault(emptyMap()))
            }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { com.jack.ai.data.FreePool.probe(getApplication(), settings) }
            }
            promptInput = promptInput.copy(contextNote = withContext(Dispatchers.IO) {
                // Work 里才能分头做：有免费模型时说一声
                (if (conv.work) com.jack.ai.data.freeCandidates(com.jack.ai.data.FreePool.load(getApplication()), settings, 0).size.takeIf { it > 0 }
                    ?.let { "【Free sub-agents】$it free models available for delegate_tasks (no cost).\n" }.orEmpty() else "") +
                runCatching { com.jack.ai.tools.ContextNote.build(getApplication(), store, conv, skills.map { it.id }.toSet()) }.getOrDefault("") +
                    // Kavi 的连续性：最近的经历、上次见用户是什么时候（每轮变，放这里不影响缓存）
                    runCatching { com.jack.ai.data.Timeline.noteFor(getApplication(), settings, _state.value.conversations, _state.value.projects, conv) }.getOrDefault("") +
                    // 深处的记忆：和这句话相关的浮上来（不常用的不再每轮都带着）
                    (if (conv.globalAllowed(project)) runCatching { com.jack.ai.data.DeepRecall.note(getApplication(), settings, lastUser?.content.orEmpty()) }.getOrDefault("") else "") +
                    // 很长的对话里重新锚定一下「我是谁」：越聊越长时开头的设定会被冲淡
                    (if (conv.messages.size >= ANCHOR_AFTER) com.jack.ai.data.Persona.fill("【Still you】" + com.jack.ai.data.KaviSelf.text(editable = false) +
                        "\nThis conversation has run long: keep your own voice and the principles you started with.\n", settings) else "")
            })
            // 历史里的附加说明必须和第一次发出时一致，才能保住后续请求的前缀缓存。
            if (extraUser == null && lastUser != null && lastUser.contextPrefix == null) {
                val prefix = PromptBuilder.timeNote(promptInput.now) + PromptBuilder.updateNote(conv, reply.appVersion) +
                    promptInput.contextNote + PromptBuilder.mentionNote(lastUser.content, skills, settings.readyMails)
                patchMessage(convId, lastUser.id) { it.copy(contextPrefix = prefix) }
                promptInput = promptInput.copy(conv = conv.copy(messages = conv.messages.map { if (it.id == lastUser.id) it.copy(contextPrefix = prefix) else it }))
                persist()
            }
            var filesDropped = false
            // Work：开前台服务，切到后台也接着干；花费到上限先问一声
            val work = conv.work
            val workTitle = conv.title
            // 不管 Chat 还是 Work，回答期间都开前台服务，切到后台也接着跑
            WorkService.start(getApplication(), convId, workTitle, work)
            /** 几个对话同时在答时，弹窗上写明是哪个对话在问。 */
            suspend fun ask(title: String, body: String, action: String) =
                core.confirm(title, (if (_state.value.streamingIds.size > 1) "对话「$workTitle」：\n" else "") + body, action)
            var spent = 0.0
            val limit = settings.workCostLimit
            var nextLimit = limit
            val session = ToolSession(toolHost, settings, conv, project, skills, object : ToolUi {
                override fun started(label: String) {
                    patchMessage(convId, reply.id) { it.copy(searches = it.searches + SearchRecord(label, pending = true, at = System.currentTimeMillis())) }
                    WorkService.progress(getApplication(), convId, workTitle, label, work)
                }

                override fun plan(items: List<com.jack.ai.data.PlanItem>, criteria: List<com.jack.ai.data.PlanItem>) {
                    patchMessage(convId, reply.id) { it.copy(plan = items, criteria = criteria) }
                    val doing = items.firstOrNull { it.status == "doing" }?.text
                    WorkService.progress(getApplication(), convId, workTitle,
                        "${items.count { it.status == "done" || it.status == "skipped" }}/${items.size}" + (doing?.let { " · $it" } ?: ""), work)
                }

                override fun notice(text: String) = patchMessage(convId, reply.id) { it.copy(notices = it.notices + text) }

                override fun endConversation(reason: String) = updateConv(convId) { it.copy(closedReason = reason) }

                override fun progress(text: String) = WorkService.progress(getApplication(), convId, workTitle, text, work)

                override fun finished(label: String, record: SearchRecord) = patchMessage(convId, reply.id) { m ->
                    m.copy(searches = m.searches.map { if (it.query == label && it.pending) record.copy(at = it.at) else it })
                }

                override suspend fun confirm(title: String, body: String, action: String) = ask(title, body, action)

                /** 把生成或修改过的文件挂到这条回答上，显示成卡片。 */
                override fun attach(file: File) {
                    val gen = GeneratedFile(file.name, file.absolutePath, ToolSession.mimeOf(file.name), file.length())
                    patchMessage(convId, reply.id) { m -> m.copy(files = m.files.filterNot { it.path == gen.path } + gen) }
                }
            })
            session.canSee = ep.vision
            session.modelName = com.jack.ai.data.modelLabel(model)
            session.unlocked.addAll(unlockedStart)
            // 开工前确认清单：只在新任务里问（接着做、复核时不问）
            if (work && extraUser == null) session.planConfirm = { steps, criteria ->
                if (!MainActivity.visible) com.jack.ai.data.Routines.notify(getApplication(), "work-ask-$convId",
                    "需要你确认：$workTitle", "Kavi 列好了清单，等你点开始", openChat = convId)
                ask(
                    "开工前确认清单",
                    steps.mapIndexed { i, s -> "${i + 1}. ${s.text}" }.joinToString("\n") +
                        (if (criteria.isEmpty()) "" else "\n\n验收标准：\n" + criteria.joinToString("\n") { "· ${it.text}" }) +
                        "\n\n不满意就点取消，告诉 Kavi 要怎么改。",
                    "开始",
                )
            }
            // 思考·自动：规则看着不难的，再让本地模型看一眼（选了本地模型才有，最多等 1.5 秒，不花钱）
            val level = if (settings.thinking == ThinkLevel.AUTO && modelOverride == null && thinking == ThinkLevel.LOW &&
                com.jack.ai.chat.LocalBrain.isHard(getApplication(), settings, lastUser?.content.orEmpty()) == true) ThinkLevel.HIGH else thinking
            val agent = Agent({ msgs, t -> client.stream(ep, msgs, level, settings.temperature, t) }, sendReasoning = thinkingOn, splitNotes = true)
            // Work 的收尾关卡：清单没做完不让收尾；开着「交付前复核」再另起一次请求按验收标准查一遍
            val gate = com.jack.ai.tools.WorkGate(session, review = if (work && settings.workReview) {
                val reviewEp = settings.backgroundEndpoint() ?: ep
                val request = conv.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
                com.jack.ai.tools.WorkGate.reviewer(session, DeepSeekClient { e, p, h, c -> core.recordSpend(convId, e, p, h, c) }, reviewEp, request, reply.createdAt)
            } else null)
            val myInterjections = interjectionsOf(convId).apply { clear() }
            running[convId] = reply.id
            val reasoning = agent.reasoning
            val content = agent.content
            var lastFlush = 0L
            // 思考摘要：后台模型把一段段思考压成小标题（见 ThinkSummary）
            val summaries = java.util.concurrent.CopyOnWriteArrayList<com.jack.ai.data.ThinkStep>()
            fun steps() = (agent.notes + summaries).sortedBy { it.at }
            // 摘要在另一个线程里读：存一份快照
            val seenReasoning = java.util.concurrent.atomic.AtomicReference("")
            val answering = java.util.concurrent.atomic.AtomicBoolean(false)
            fun flush() {
                val r = reasoning.toString()
                val c = content.toString()
                val t = agent.trail.toList()
                seenReasoning.set(r)
                answering.set(c.isNotBlank())
                val st = steps()
                patchMessage(convId, reply.id) { it.copy(reasoning = r, content = c, trail = t, steps = st) }
            }
            val summaryEp = settings.backgroundEndpoint()
            val summarizer = if (!settings.thinkSummary || summaryEp == null) null else viewModelScope.launch(Dispatchers.IO) {
                var done = 0
                while (summaries.size < com.jack.ai.chat.ThinkSummary.MAX) {
                    delay(com.jack.ai.chat.ThinkSummary.POLL_MS)
                    val r = seenReasoning.get()
                    if (!com.jack.ai.chat.ThinkSummary.due(r.length - done, answering.get())) continue
                    val chunk = r.substring(done)
                    done = r.length
                    val previous = summaries.map { it.text }
                    val line = runCatching {
                        client.complete(summaryEp, listOf(
                            buildJsonObject { put("role", "system"); put("content", com.jack.ai.chat.ThinkSummary.SYSTEM) },
                            buildJsonObject { put("role", "user"); put("content", com.jack.ai.chat.ThinkSummary.input(previous, chunk)) },
                        ), maxTokens = 60)
                    }.getOrNull()?.let { com.jack.ai.chat.ThinkSummary.clean(it, previous) } ?: continue
                    summaries += com.jack.ai.data.ThinkStep("summary", line)
                    val st = steps()
                    patchMessage(convId, reply.id) { it.copy(steps = st) }
                }
            }
            // 交付清单：先记下对话文件夹现在的样子，答完一比就知道交了哪些文件
            val wsDir = com.jack.ai.data.Workspace.dir(getApplication(), com.jack.ai.data.Workspace.key(conv))
            val before = if (work) withContext(Dispatchers.IO) { runCatching { com.jack.ai.data.Deliverables.snapshot(wsDir) }.getOrNull() } else null
            // 出错停下时记下是哪种错，能自动接着做的（网络断了、服务一时忙）就接着做
            var failure: Exception? = null
            try {
                // 拼历史要读图、转码，放到后台线程，免得界面卡
                val history = withContext(Dispatchers.Default) { buildHistory(toolSet != null) }
                agent.run(
                    messages = history,
                    tools = toolSet,
                    maxRounds = { session.maxRounds },
                    maxToolCalls = { session.maxRounds },
                    execute = { name, args ->
                        // 用户已经点了“先停下”就不再问：同一轮里剩下的工具调用直接拦掉
                        if (work && limit > 0 && spent >= nextLimit && session.maxRounds != 0) {
                            if (!MainActivity.visible) com.jack.ai.data.Routines.notify(getApplication(), "work-ask-$convId",
                                "需要你确认：$workTitle", "这次任务已经花了 ¥%.2f，打开看看要不要继续".format(spent), openChat = convId)
                            val ok = ask(
                                "这次任务已经花了 ¥%.2f".format(spent),
                                "超过了设定的 ¥%.2f。继续的话，再多花 ¥%.2f 会再问你。".format(nextLimit, limit),
                                "继续",
                            )
                            if (ok) nextLimit = spent + limit
                            else session.maxRounds = 0
                        }
                        if (session.maxRounds == 0) session.stopNote ?: "用户让你先停下（花费到了上限）。不要再调用工具，直接说明目前做到哪一步、还差什么。"
                        else session.execute(name, args) + gate.reminder(name)
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
                    interjections = { generateSequence { myInterjections.poll() }.toList() },
                    images = { session.takeImages() },
                    // use_skill 打开了新技能：下一轮把它的工具加上
                    retool = { PromptBuilder.toolSet(settings, skills, conv, project, session.unlocked.toSet()) },
                    beforeFinish = { answer -> gate.beforeFinish(answer) },
                    slim = if (work) { msgs -> ContextSlim.slim(msgs) { com.jack.ai.data.Spill.save(wsDir, it) } } else null,
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
                                        "Web search results below. Use them in your answer and mark sources as [n](link):\n\n$results"))
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
                failure = e
                patchMessage(convId, reply.id) { it.copy(error = e.message) }
            } catch (e: IOException) {
                flush()
                failure = e
                patchMessage(convId, reply.id) { it.copy(error = "网络连接失败：${e.message ?: "请检查网络"}" + com.jack.ai.api.Net.hint()) }
            } catch (e: Exception) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = "出错了：${e.message}") }
            } finally {
                summarizer?.cancel()
                // 停止或出错时，把还在“搜索中”的标记清掉
                patchMessage(convId, reply.id) { m ->
                    m.copy(content = m.content.trimEnd(), searches = m.searches.map { if (it.pending) it.copy(pending = false) else it })
                }
                running.remove(convId, reply.id)
                if (before != null) {
                    val delivered = withContext(NonCancellable + Dispatchers.IO) { runCatching { com.jack.ai.data.Deliverables.diff(before, wsDir) }.getOrDefault(emptyList()) }
                    if (delivered.isNotEmpty()) patchMessage(convId, reply.id) { it.copy(delivered = delivered) }
                }
                // 占着浏览器的话放出来，等着用的回答接着用
                session.releaseBrowser()
                // 这次打开的技能记进对话：之后每轮都带着它的工具，工具列表不再变，缓存照样命中
                if (session.unlocked.any { it !in conv.skillTools }) {
                    val opened = session.unlocked.toSet()
                    updateConvQuiet(convId) { it.copy(skillTools = it.skillTools + opened) }
                }
                done()
                persist()
                // 这段聊天停下一阵后，在后台写进 Kavi 的经历流
                com.jack.ai.data.Timeline.scheduleSummary(getApplication(), convId)
                // 插的话没来得及用上（回答已经收尾了）：等这次结束后当成新消息发出去
                val leftover = generateSequence { myInterjections.poll() }.toList()
                val me = jobs[convId]
                if (leftover.isNotEmpty()) viewModelScope.launch {
                    me?.join()
                    if (_state.value.currentId == convId) send(leftover.joinToString("\n"))
                }
                // Work 一时出错停下（网络断了、服务忙）：等一会儿自动接着做，连着最多 AUTO_RESUME_MAX 次；用户停下、花费到上限、复核的不接
                if (failure == null) autoResumes.remove(convId)
                val resumes = autoResumes[convId] ?: 0
                if (work && leftover.isEmpty() && extraUser != REVIEW_PROMPT && session.maxRounds != 0 &&
                    failure?.let(::transient) == true && resumes < AUTO_RESUME_MAX) {
                    autoResumes[convId] = resumes + 1
                    val wait = AUTO_RESUME_DELAY_S * (resumes + 1)
                    patchMessage(convId, reply.id) { it.copy(error = (it.error ?: "出错了") + "\n${wait} 秒后自动接着做（第 ${resumes + 1}/$AUTO_RESUME_MAX 次）") }
                    viewModelScope.launch {
                        me?.join()
                        delay(wait * 1000L)
                        // 这期间用户自己发了消息、点了接着做，或者对话被删了，就不再接
                        val cur = _state.value.conversations.firstOrNull { it.id == convId }
                        if (!busy(convId) && cur?.messages?.lastOrNull()?.id == reply.id) continueWork(convId)
                    }
                }
                run {
                    val m = _state.value.conversations.firstOrNull { it.id == convId }?.messages?.firstOrNull { it.id == reply.id }
                    val result = m?.error ?: m?.content?.takeLast(200)?.trim().orEmpty().ifBlank { "已停止" }
                    WorkService.finish(getApplication(), convId, workTitle, result, work)
                }
                compression.maybeAutoCompress(convId)
                if (failure == null) maybeReviewMemory(convId)
            }
        }
    }

    private val reviewing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 每聊几轮在后台回看一下，有值得长期记住的就记下（见 MemoryReview）。Chat 记进全局记忆，Work 记进工作记忆；
     * 关了记忆、关了「参与做梦」、隐私项目、Kavi 自己的后台对话不回看。出错就算了，下次再看。
     */
    private fun maybeReviewMemory(convId: String) {
        val st = _state.value
        val conv = st.conversations.firstOrNull { it.id == convId } ?: return
        val settings = st.settings
        val project = st.project(conv.projectId)
        val work = conv.workMemoryAllowed(project)
        val chat = !conv.work && conv.globalAllowed(project)
        if (!settings.memoryEnabled || !conv.inDream || conv.ownLife || !(work || chat)) return
        if (conv.id == com.jack.ai.data.INBOX_ID || conv.id == com.jack.ai.data.Reader.CONV_ID) return
        if (!com.jack.ai.data.MemoryReview.due(conv) || !reviewing.add(convId)) return
        val ep = settings.backgroundEndpoint() ?: run { reviewing.remove(convId); return }
        val unseen = com.jack.ai.data.MemoryReview.unseen(conv)
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val P = com.jack.ai.data.Persona
                val workFile = com.jack.ai.data.ChatMemory.workFile(app)
                fun current(): List<String> =
                    if (work) com.jack.ai.data.ChatMemory.items(com.jack.ai.data.ChatMemory.read(workFile)) else store.loadMemories().map { it.text }
                val budget = if (work) com.jack.ai.data.MemoryOps.FILE_BUDGET else com.jack.ai.data.MemoryOps.HOT_BUDGET
                val before = current()
                val input = "【Current ${if (work) "work memory" else "memories"}】(~${com.jack.ai.data.MemoryOps.tokensOf(before)} of $budget tokens)\n" +
                    before.ifEmpty { listOf("(none)") }.joinToString("\n") { "- $it" } +
                    "\n\n【The latest exchanges】\n" + com.jack.ai.data.MemoryReview.transcript(unseen, P.ref(settings) ?: "User")
                val raw = client.complete(ep, listOf(
                    buildJsonObject { put("role", "system"); put("content", P.fill(com.jack.ai.data.MemoryReview.instructions(work) + P.nameLine(settings).let { if (it.isEmpty()) "" else "\n\n$it" }, settings)) },
                    buildJsonObject { put("role", "user"); put("content", input) },
                ), maxTokens = 800, jsonMode = true)
                val ops = com.jack.ai.data.MemoryReview.parse(raw)
                // 在现在的记忆上改（这期间 Kavi 或用户可能刚存过）
                val now = current()
                val (next, done) = com.jack.ai.data.MemoryReview.apply(now, ops, budget)
                if (done.isNotEmpty()) {
                    if (work) com.jack.ai.data.ChatMemory.write(workFile, next)
                    else {
                        val live = store.loadMemories()
                        com.jack.ai.data.MemoryOps.snapshot(app, live)
                        val byText = live.associateBy { it.text }
                        val updated = next.map { byText[it] ?: com.jack.ai.data.MemoryItem(text = it, source = "聊天时回看：${conv.title}") }
                        store.saveMemories(updated)
                        _state.update { it.copy(memories = updated) }
                    }
                    val lastAnswer = conv.messages.lastOrNull { it.role == "assistant" }?.id
                    if (lastAnswer != null) patchMessage(convId, lastAnswer) { it.copy(notices = it.notices + com.jack.ai.data.MemoryReview.notice(done, work)) }
                }
                updateConvQuiet(convId) { it.copy(memReviewedAt = unseen.maxOf { m -> m.createdAt }) }
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 网络、格式出错：不记，下次再回看
            } finally {
                reviewing.remove(convId)
            }
        }
    }

    // ---------- 分享进来、备份、用量 ----------

    fun receiveText(text: String) = _state.update { it.copy(incomingText = text) }
    fun consumeIncomingText() = _state.update { it.copy(incomingText = null) }
    fun requestVoice() = _state.update { it.copy(voiceRequest = true) }
    fun consumeVoiceRequest() = _state.update { it.copy(voiceRequest = false) }
    fun requestRecord() = _state.update { it.copy(recordRequest = true) }
    fun consumeRecordRequest() = _state.update { it.copy(recordRequest = false) }

    /** 录音纪要录完：录音挂在当前对话上发出去，Kavi 转文字、出纪要。这个对话正在回答的话等它答完（别的对话不用等）。 */
    fun recordingDone(file: java.io.File, seconds: Long) {
        val here = _state.value.currentId
        viewModelScope.launch {
            here?.let { jobs[it] }?.join()
            val att = Attachment(kind = "file", name = file.name, path = file.absolutePath)
            _state.update { it.copy(pending = it.pending + att) }
            send(com.jack.ai.data.Minutes.recording(file.name, seconds))
        }
    }

    /** 分享菜单的「用 Kavi 总结」：链接或视频、录音文件，开个新对话直接发出去（正在进行的回答不停，各答各的）。 */
    fun summarizeShare(text: String?, uris: List<Uri>) {
        viewModelScope.launch {
            newChat()
            val app = getApplication<Application>()
            val atts = uris.mapNotNull { uri ->
                try {
                    com.jack.ai.data.Attachments.importFile(app, uri, keepOriginal = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    core.toast("文件读不了：${e.message ?: "读取失败"}")
                    null
                }
            }
            if (atts.isEmpty() && text.isNullOrBlank()) return@launch
            _state.update { it.copy(pending = atts) }
            send(com.jack.ai.data.Minutes.summary(text, atts.isNotEmpty()))
        }
    }
    fun requestCamera() = _state.update { it.copy(cameraRequest = true) }
    fun consumeCameraRequest() = _state.update { it.copy(cameraRequest = false) }

    /** 别的 App 分享过来的内容：开一个新对话，文字放进输入框，图片和文件作为附件。 */
    fun handleShare(text: String?, uris: List<Uri>, mime: String?) {
        // 只是填进新对话的输入框、不发出去：正在写的回答不用停，让它在后台写完
        newChat()
        if (!text.isNullOrBlank()) receiveText(text)
        if (uris.isNotEmpty()) {
            if (mime?.startsWith("image/") == true) addImages(uris) else addFiles(uris)
        }
    }

    /**
     * 分享菜单的「用 Kavi 下载」：开个新对话直接发出去，Kavi 认出链接，把视频或文件存到手机「下载/Kavi」。
     * 分享的文字常带着一大段口令（「复制打开抖音……」），原样交给它，它自己找链接。
     */
    fun downloadShare(text: String) {
        viewModelScope.launch {
            // 新开对话发出去，正在进行的回答不停，各答各的
            newChat()
            send(com.jack.ai.data.Downloads.shareRequest(text))
        }
    }

    /**
     * 万能收件箱：分享进来的东西直接发到「收件箱」对话，AI 自动判断类型、提炼重点、归档。
     * 和「问 Kavi」不同，不等用户再按发送。
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
            // 收件箱自己正在回答的先收尾，再发（别的对话的回答不用等）
            jobs[com.jack.ai.data.INBOX_ID]?.join()
            _state.update { it.copy(pending = atts) }
            send(text.orEmpty())
        }
    }

    suspend fun loadUsage(): List<UsageDay> = withContext(Dispatchers.IO) { store.loadUsage() }
    suspend fun loadToolStats(): List<com.jack.ai.data.ToolStat> = withContext(Dispatchers.IO) { store.loadToolStats() }

    suspend fun loadFlows(): List<com.jack.ai.data.BrowserFlow> = withContext(Dispatchers.IO) { store.loadFlows() }
    suspend fun saveFlows(list: List<com.jack.ai.data.BrowserFlow>) = withContext(Dispatchers.IO) { store.saveFlows(list) }
    suspend fun loadSiteNotes(): List<com.jack.ai.data.SiteNote> = withContext(Dispatchers.IO) { store.loadSiteNotes() }
    suspend fun saveSiteNotes(list: List<com.jack.ai.data.SiteNote>) = withContext(Dispatchers.IO) { store.saveSiteNotes(list) }

    private fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    companion object {
        /** 最多几个对话同时在回答。 */
        const val MAX_PARALLEL = 3
        /** Work 一时出错后自动接着做：连着最多几次，第 n 次等 n × 这么多秒。 */
        const val AUTO_RESUME_MAX = 3
        const val AUTO_RESUME_DELAY_S = 15

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
        override fun updateSettings(transform: (Settings) -> Settings) = this@ChatViewModel.updateSettings(transform)
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
    /** 这个对话里用 use_skill 打开过的技能名（工具按需出现的那些）。 */
    fun openedSkillNames(conv: Conversation?): List<String> {
        val ids = conv?.skillTools.orEmpty()
        return _state.value.settings.activeSkills().filter { it.id in ids }.map { it.name }
    }

    fun contextBreakdown(conv: Conversation?): List<Pair<String, Int>> {
        val st = _state.value
        val c = conv ?: Conversation()
        val memories = if (st.settings.memoryEnabled) st.memories else emptyList()
        val (chatMemory, projectMemory) = knowledge.memoryTexts(c)
        return PromptBuilder.contextBreakdown(st.settings, c, st.project(c.projectId), memories, st.settings.activeSkills(),
            chatMemory, projectMemory, Dreamer.recentJournals(st.dreams), com.jack.ai.data.FreeTime.latelyNote(getApplication()),
            com.jack.ai.data.SelfStory.bio(getApplication()).text, if (c.work) knowledge.workMemoryText() else "")
    }

    private fun updateConv(id: String, transform: (Conversation) -> Conversation) = core.updateConv(id, transform)
    private fun patchMessage(convId: String, msgId: String, transform: (ChatMessage) -> ChatMessage) = core.patchMessage(convId, msgId, transform)
    private fun updateConvQuiet(id: String, transform: (Conversation) -> Conversation) = core.updateConvQuiet(id, transform)
    /** 让 Kavi 现在就去过一次自由时间（不管开没开，预算照算）。 */
    fun readNow() {
        com.jack.ai.data.Reader.runNow(getApplication())
        _state.update { it.copy(toast = "Kavi 去过自由时间了，做完记在「记忆与梦」页") }
    }

    /** 回 Kavi 的心愿：approved 给预算 / declined 先不了 / bought 买了。 */
    fun answerWish(id: String, status: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<android.app.Application>()
            com.jack.ai.data.FreeTime.save(app, com.jack.ai.data.FreeTime.answer(com.jack.ai.data.FreeTime.load(app), id, status))
        }
    }

    /** 从事实库里删掉一条（连同向量）。 */
    fun deleteFact(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<android.app.Application>()
            com.jack.ai.data.Facts.save(app, com.jack.ai.data.Facts.load(app).filterNot { it.id == id })
            com.jack.ai.data.Facts.removeVector(app, id)
        }
    }

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
    fun importBackup(uri: Uri) { snapshots.clear(); backup.importBackup(uri) }

    // 设置和模型服务
    fun updateSettings(transform: (Settings) -> Settings) = settingsFeature.updateSettings(transform)
    suspend fun fetchModels(baseUrl: String, apiKey: String, workspaceId: String = "") = settingsFeature.fetchModels(baseUrl, apiKey, workspaceId)
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
    fun reloadLibrary() = knowledge.reloadLibrary()
    // 用户自己在界面上改记忆：快照作废，下一轮就用改过的（Kavi 用工具存的不算，等下一张快照）
    fun addMemory(text: String) { snapshots.clear(); knowledge.addMemory(text) }
    fun updateMemory(id: String, text: String) { snapshots.clear(); knowledge.updateMemory(id, text) }
    fun deleteMemory(id: String) { snapshots.clear(); knowledge.deleteMemory(id) }
    fun clearMemories() { snapshots.clear(); knowledge.clearMemories() }
    fun promoteMemory(id: String) { snapshots.clear(); knowledge.promoteMemory(id) }
    fun demoteMemory(id: String) { snapshots.clear(); knowledge.demoteMemory(id) }
    fun markDreamsSeen() = knowledge.markDreamsSeen()
    fun dreamNow() = knowledge.dreamNow()
    fun editJournal(id: String, text: String) { snapshots.clear(); knowledge.editJournal(id, text) }
    fun deleteDream(id: String) { snapshots.clear(); knowledge.deleteDream(id) }

    // 对话记忆和项目记忆（存在文件夹里的 Markdown 文件）
    fun chatMemory(conv: Conversation) = knowledge.chatMemory(conv)
    fun setChatMemory(conv: Conversation, items: List<String>) { snapshots.clear(); knowledge.setChatMemory(conv, items) }
    fun moveToGlobal(conv: Conversation, item: String) { snapshots.clear(); knowledge.moveToGlobal(conv, item) }
    fun projectMemory(projectId: String) = knowledge.projectMemory(projectId)
    fun workMemory() = knowledge.workMemory()
    fun setWorkMemory(items: List<String>) { snapshots.clear(); knowledge.setWorkMemory(items) }
    fun setProjectMemory(projectId: String, items: List<String>) { snapshots.clear(); knowledge.setProjectMemory(projectId, items) }
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
