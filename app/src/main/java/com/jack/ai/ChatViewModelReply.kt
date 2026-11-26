package com.jack.ai

import com.jack.ai.api.DeepSeekClient
import com.jack.ai.data.spaceOf
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.Project
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Skill
import com.jack.ai.data.endpoint
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.globalAllowed
import com.jack.ai.tools.ToolSession
import com.jack.ai.tools.ToolUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

// startReply 里几块自成一体的部分，原样搬出来，主流程读起来短一些（见 docs/架构优化.md 第 5 批）。

/**
 * 这一轮附在最后一条用户消息前面的清单，和只发变了的段落时要记进对话的「已发内容」（没有就是 null）。
 * 清单分两块：固定的（文件、表格、任务……）只发变了的段落（见 ContextNote.delta）；每条消息现算的（经历、相关记忆、推荐技能）照发。
 */
internal suspend fun ChatViewModel.replyContextNote(
    convId: String,
    conv: Conversation,
    project: Project?,
    settings: Settings,
    skills: List<Skill>,
    lastUser: ChatMessage?,
    persistNote: Boolean,
): Pair<String, Map<String, String>?> {
    var noteSent: Map<String, String>? = null
    val note = withContext(Dispatchers.IO) {
        val stable =
            // 能分头做：有免费模型时说一声
            com.jack.ai.data.freeCandidates(com.jack.ai.data.FreePool.load(getApplication()), settings, 0).size.takeIf { it > 0 }
                ?.let { "【Free sub-agents】$it free models available for delegate_tasks (no cost).\n" }.orEmpty() +
            runCatching { com.jack.ai.tools.ContextNote.build(getApplication(), store, conv, skills.map { it.id }.toSet()) }.getOrDefault("") +
            // 很长的对话里重新锚定一下「我是谁」：越聊越长时开头的设定会被冲淡（隔一阵提一次，见 ContextNote.REPEAT_AFTER）
            (if (conv.messages.size >= ANCHOR_AFTER) com.jack.ai.data.Persona.fill("【Still you】" + com.jack.ai.data.KaviSelf.text().replace("\n【", "\n[") +
                "\nThis conversation has run long: keep your own voice and the principles you started with.\n", settings) else "")
        val shown = if (persistNote) com.jack.ai.tools.ContextNote.delta(stable, conv.noteSent, conv, lastUser!!.id).also { noteSent = it.second }.first
            else stable
        shown +
            // Kavi 的连续性：最近的经历、上次见用户是什么时候（每轮变，放这里不影响缓存）
            runCatching { com.jack.ai.data.Timeline.noteFor(getApplication(), settings, _state.value.conversations, _state.value.projects, conv) }.getOrDefault("") +
            // 深处的记忆：和这句话相关的浮上来（不常用的不再每轮都带着）
            (if (conv.globalAllowed(project)) runCatching { com.jack.ai.data.memory.DeepRecall.note(getApplication(), settings, lastUser?.content.orEmpty(), settings.spaceOf(conv)) }.getOrDefault("") else "") +
            // 按意思推荐一两个技能（技能多了模型容易挑错）
            runCatching { com.jack.ai.data.SkillMatcher.note(getApplication(), settings, lastUser?.content.orEmpty(), skills) }.getOrDefault("") +
            // 语音对话：回答会被读出来，说短一点、别排版
            com.jack.ai.data.Persona.fill(com.jack.ai.chat.VoiceMode.note(convId), settings)
    }
    return note to noteSent
}

/** 回答中途要用户确认。几个对话同时在答时，弹窗上写明是哪个对话在问。 */
internal suspend fun ChatViewModel.confirmDuringReply(
    convId: String,
    workTitle: String,
    title: String,
    body: String,
    action: String,
    always: com.jack.ai.data.AllowRule? = null,
): Boolean = kotlinx.coroutines.coroutineScope {
    // 弹窗只在聊天页里看得到：用户切到别的 App（或 Kavi 浏览器）时推一条通知，不然任务就一直悄悄等着
    val nid = "confirm-$convId"
    val nag = launch {
        while (MainActivity.visible) delay(1500)
        com.jack.ai.data.Routines.notify(getApplication(), nid, "Kavi 等你确认：$title", "「${workTitle.take(20)}」停在这一步，点开回答后接着做。\n" + body.take(200), openChat = convId)
    }
    try {
        core.confirm(title, (if (_state.value.streamingIds.size > 1) "对话「$workTitle」：\n" else "") + body, action, always)
    } finally {
        nag.cancel()
        runCatching { androidx.core.app.NotificationManagerCompat.from(getApplication<Application>()).cancel(nid.hashCode()) }
    }
}

/** 工具执行时怎么在这条回答上显示：进度、清单、卡片、确认弹窗、文件。 */
internal class ReplyToolUi(
    private val vm: ChatViewModel,
    private val convId: String,
    private val replyId: String,
    private val workTitle: String,
    private val ask: suspend (title: String, body: String, action: String, always: com.jack.ai.data.AllowRule?) -> Boolean,
) : ToolUi {
    /** Kavi 列了清单，算在做任务：通知上写「正在工作」「完成了」。 */
    var tasked = false
        private set

    override fun started(label: String) {
        vm.patchMessage(convId, replyId) { it.copy(searches = it.searches + SearchRecord(label, pending = true, at = System.currentTimeMillis())) }
        WorkService.progress(vm.getApplication(), convId, workTitle, label, tasked)
    }


    override fun notice(text: String) = vm.patchMessage(convId, replyId) { it.copy(notices = it.notices + text) }

    override fun ask(cards: List<com.jack.ai.data.AskCard>): Boolean {
        vm.patchMessage(convId, replyId) { it.copy(asks = cards) }
        return true
    }

    override fun draft(card: com.jack.ai.data.DraftCard): Boolean {
        // 同一个平台再出一版就换掉旧的（改稿时只留最新的）
        vm.patchMessage(convId, replyId) { m -> m.copy(drafts = m.drafts.filterNot { it.platform == card.platform } + card) }
        return true
    }

    override fun endConversation(reason: String) = vm.updateConv(convId) { it.copy(closedReason = reason) }

    private var progressAt = 0L
    override fun progress(text: String) {
        WorkService.progress(vm.getApplication(), convId, workTitle, text, tasked)
        // 聊天里正在做的那一步也显示进度（一秒最多改一次，免得一直重画、一直存盘）
        val now = System.currentTimeMillis()
        if (now - progressAt < 1000) return
        progressAt = now
        vm.patchMessage(convId, replyId) { m ->
            val i = m.searches.indexOfLast { it.pending }
            if (i < 0) m else m.copy(searches = m.searches.toMutableList().also { it[i] = it[i].copy(progress = text) })
        }
    }

    override fun finished(label: String, record: SearchRecord) = vm.patchMessage(convId, replyId) { m ->
        m.copy(searches = m.searches.map { if (it.query == label && it.pending) record.copy(at = it.at) else it })
    }

    override suspend fun confirm(title: String, body: String, action: String) = ask(title, body, action, null)
    override suspend fun confirmAlways(title: String, body: String, action: String, always: com.jack.ai.data.AllowRule) = ask(title, body, action, always)

    /** 把生成或修改过的文件挂到这条回答上，显示成卡片。 */
    override fun attach(file: File) {
        val gen = GeneratedFile(file.name, file.absolutePath, ToolSession.mimeOf(file.name), file.length())
        vm.patchMessage(convId, replyId) { m -> m.copy(files = m.files.filterNot { it.path == gen.path } + gen) }
    }
}

/**
 * 思考摘要：后台模型把一段段思考压成小标题（见 ThinkSummary），写进 [summaries]。没开或没有可用的模型时返回 null。
 * [seenReasoning]、[answering] 是回答那边存的快照（摘要在另一个线程里读）。
 */
internal fun ChatViewModel.launchThinkSummarizer(
    settings: Settings,
    convId: String,
    replyId: String,
    summaries: MutableList<com.jack.ai.data.ThinkStep>,
    seenReasoning: java.util.concurrent.atomic.AtomicReference<String>,
    answering: java.util.concurrent.atomic.AtomicBoolean,
    steps: () -> List<com.jack.ai.data.ThinkStep>,
): Job? {
    // GPT（ChatGPT 账号）自己的思考摘要就带小标题（**…**）：直接拿来用，不用再请后台模型写
    if (settings.endpoint()?.chatgpt == true) return if (!settings.thinkSummary) null else viewModelScope.launch(Dispatchers.IO) {
        while (summaries.size < com.jack.ai.chat.ThinkSummary.MAX) {
            delay(com.jack.ai.chat.ThinkSummary.POLL_MS)
            val previous = summaries.map { it.text }
            val fresh = com.jack.ai.chat.ThinkSummary.gptHeadings(seenReasoning.get()).filter { it !in previous }
            if (fresh.isEmpty()) continue
            fresh.take(com.jack.ai.chat.ThinkSummary.MAX - summaries.size).forEach { summaries += com.jack.ai.data.ThinkStep("summary", it) }
            val st = steps()
            patchMessage(convId, replyId) { it.copy(steps = st) }
        }
    }
    val summaryEp = settings.backgroundEndpoint()
    // 选了「思考摘要用本地生成模型」又下好了：用手机上的 Qwen 写，不调后台模型
    val localLlm = com.jack.ai.data.LocalModels.activeLlm(getApplication(), settings)
    return if (!settings.thinkSummary || (summaryEp == null && localLlm == null)) null else viewModelScope.launch(Dispatchers.IO) {
        var done = 0
        while (summaries.size < com.jack.ai.chat.ThinkSummary.MAX) {
            delay(com.jack.ai.chat.ThinkSummary.POLL_MS)
            val r = seenReasoning.get()
            if (!com.jack.ai.chat.ThinkSummary.due(r.length - done, answering.get())) continue
            val chunk = r.substring(done)
            done = r.length
            val previous = summaries.map { it.text }
            val line = runCatching {
                if (localLlm != null) com.jack.ai.api.LocalLlm.generate(
                    com.jack.ai.data.LocalModels.dir(getApplication(), localLlm), localLlm.llm!!,
                    com.jack.ai.chat.ThinkSummary.SYSTEM, com.jack.ai.chat.ThinkSummary.input(previous, chunk.takeLast(1200)), maxNew = 32, maxPrompt = 900)
                else DeepSeekClient(store.usage("思考摘要")).complete(summaryEp!!, listOf(
                    buildJsonObject { put("role", "system"); put("content", com.jack.ai.chat.ThinkSummary.SYSTEM) },
                    buildJsonObject { put("role", "user"); put("content", com.jack.ai.chat.ThinkSummary.input(previous, chunk)) },
                ), maxTokens = 60)
            }.getOrNull()?.let { com.jack.ai.chat.ThinkSummary.clean(it, previous) } ?: continue
            summaries += com.jack.ai.data.ThinkStep("summary", line)
            val st = steps()
            patchMessage(convId, replyId) { it.copy(steps = st) }
        }
    }
}

/**
 * 回答收尾后的后续：没来得及用上的插话、来晚的后台消息等这次结束后再发；一时出错停下的自动接着做。
 * [me] 是这次回答的任务，后续都等它结束再开始。
 */
internal fun ChatViewModel.replyFollowUps(
    convId: String,
    replyId: String,
    me: Job?,
    myInterjections: java.util.Queue<String>,
    myReports: java.util.Queue<String>,
    work: Boolean,
    extraUser: String?,
    toolsStopped: Boolean,
    underLimit: Boolean,
    failure: Exception?,
) {
    // 插的话没来得及用上（回答已经收尾了）：等这次结束后当成新消息发出去
    val leftover = generateSequence { myInterjections.poll() }.toList()
    // 后台下好的消息来晚了（回答已经收尾，没带给模型）：等这次结束后叫醒 Kavi 接着做
    val lateReports = generateSequence { myReports.poll() }.toList()
    if (lateReports.isNotEmpty()) viewModelScope.launch {
        me?.join()
        wake(convId, lateReports.joinToString("\n\n"))
    }
    if (leftover.isNotEmpty()) viewModelScope.launch {
        me?.join()
        resendLeftover(convId, leftover.joinToString("\n"))
    }
    // Work 一时出错停下（网络断了、服务忙）：等一会儿自动接着做，连着最多 AUTO_RESUME_MAX 次；用户停下、花费到上限、复核的不接
    if (failure == null) autoResumes.remove(convId)
    val resumes = autoResumes[convId] ?: 0
    if (work && goalOf(convId) == null && leftover.isEmpty() && extraUser != ChatViewModel.REVIEW_PROMPT && !toolsStopped && underLimit &&
        failure?.let(::transient) == true && resumes < ChatViewModel.AUTO_RESUME_MAX) {
        autoResumes[convId] = resumes + 1
        val wait = ChatViewModel.AUTO_RESUME_DELAY_S * (resumes + 1)
        patchMessage(convId, replyId) { it.copy(error = (it.error ?: "出错了") + "\n${wait} 秒后自动接着做（第 ${resumes + 1}/${ChatViewModel.AUTO_RESUME_MAX} 次）") }
        viewModelScope.launch {
            me?.join()
            delay(wait * 1000L)
            // 这期间用户自己发了消息、点了接着做，或者对话被删了，就不再接
            val cur = _state.value.conversations.firstOrNull { it.id == convId }
            if (!busy(convId) && cur?.messages?.lastOrNull()?.id == replyId) continueWork(convId)
        }
    }
}
