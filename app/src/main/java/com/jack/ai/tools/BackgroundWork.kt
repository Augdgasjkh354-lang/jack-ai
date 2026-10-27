package com.jack.ai.tools

import android.app.Application
import android.content.Context
import com.jack.ai.AskWidget
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.api.TavilyClient
import com.jack.ai.api.WebClient
import com.jack.ai.chat.Agent
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.Conversation
import com.jack.ai.data.Flashcard
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.LibraryIndex
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Pricing
import com.jack.ai.data.Project
import com.jack.ai.data.Routine
import com.jack.ai.data.Routines
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Store
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.activeSkills
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.endpoint
import com.jack.ai.data.globalAllowed
import com.jack.ai.data.fullAuth
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App 没打开时（定时 Work）工具要改数据就直接读写存储。 */
class BackgroundToolHost(ctx: Context) : ToolHost {
    override val app: Application = ctx.applicationContext as Application
    override val store = Store(app)
    override val tavily = TavilyClient()
    override val web = WebClient(tavily, { store.addPaidCall(WebClient.USAGE_KEY, WebClient.PRICE) }, onFree = { store.addPaidCall(it, 0.0) })
    override fun conversations(): List<Conversation> = store.loadConversations()
    override fun setMemories(list: List<MemoryItem>) {}
    override fun editCards(transform: (List<Flashcard>) -> List<Flashcard>) = store.saveCards(transform(store.loadCards()))
    override fun saveRoutine(r: Routine) {
        val list = store.loadRoutines().let { l -> if (l.any { it.id == r.id }) l.map { if (it.id == r.id) r else it } else l + r }
        store.saveRoutines(list)
        Routines.schedule(app, r)
    }
    override fun deleteRoutine(id: String) {
        Routines.cancel(app, id)
        store.saveRoutines(store.loadRoutines().filterNot { it.id == id })
    }
    override fun libraryIndex() = LibraryIndex(store.loadLibrary().map { it.name to store.libraryText(it.id) })
    override fun projectIndex(p: Project) =
        LibraryIndex(p.files.filter { it.text.length > PromptBuilder.PROJECT_INLINE_CHARS }.map { it.name to it.text })
    override fun refreshWidget() = AskWidget.refresh(app)
    override fun saveNote(projectId: String?, title: String, content: String) {
        if (projectId == null) {
            val doc = com.jack.ai.data.LibraryDoc(name = "$title.md", chars = content.length)
            store.saveLibraryText(doc.id, content)
            store.saveLibrary(store.loadLibrary() + doc)
        } else {
            store.saveProjects(store.loadProjects().map { p ->
                if (p.id != projectId) p
                else p.copy(files = p.files + com.jack.ai.data.Attachment(kind = "file", name = "$title.md", text = content), updatedAt = System.currentTimeMillis())
            })
        }
    }
}

const val NO_KEY = "还没有填写 API Key，任务没法执行。到设置里填好以后，下次会按时再跑。"

/** 后台任务跑得怎么样：OK 产出了结果；FAILED 没产出；NEEDS_SETUP 没配置好（没有 API Key），根本没跑。 */
enum class TaskStatus { OK, FAILED, NEEDS_SETUP }

/** 定时 Work 的结果：文字、生成的文件，以及成没成（由代码置位，不看文字）。 */
data class TaskResult(val text: String, val files: List<GeneratedFile> = emptyList(), val status: TaskStatus = TaskStatus.OK, val spent: Double = 0.0)

/**
 * 到点在后台跑一个 Work 任务：和聊天里的 Work 一样能用全部开着的技能（邮箱、文件、联网……），
 * 文件存进这个任务对话的文件夹。用户不在线，需要确认的操作（发邮件等）一律不做。
 * 花费到 Work 上限就收尾。
 */
suspend fun runBackgroundWork(
    ctx: Context,
    r: Routine,
    log: (com.jack.ai.data.RunStep) -> Unit = {},
    /** 只给哪些工具：args 为 null 时问「给不给这个工具」，不为 null 时问「这次调用放不放行」。 */
    allow: (name: String, args: String?) -> Boolean = { _, _ -> true },
    /** 换掉默认的「后台定时任务」说明。 */
    brief: String? = null,
    /** 用哪个模型，不填用 Work 的模型。 */
    model: String? = null,
    /** 自由时间的钱：花到今天的预算时提醒一次、到上限就停，Kavi 能从积蓄里拿（见 FreeBudget）。不给就用 Work 的单次上限。 */
    budget: com.jack.ai.data.FreeBudget? = null,
    onProgress: (String) -> Unit = {},
): TaskResult {
    com.jack.ai.data.KaviSelf.ensure(ctx)
    val startedAt = System.currentTimeMillis()
    val host = BackgroundToolHost(ctx)
    val store = host.store
    val s = store.loadSettings()
    val ep = s.endpoint(model ?: s.workModel)?.takeIf { it.apiKey.isNotBlank() } ?: s.backgroundEndpoint()
        ?: return TaskResult(NO_KEY, status = TaskStatus.NEEDS_SETUP)
    val conv = store.loadConversations().firstOrNull { it.id == r.convId }?.copy(work = true)
        ?: Conversation(id = r.convId, title = "⏰ " + r.title, work = true)
    val skills = s.activeSkills()
    // 项目里设的任务：工具也要知道在哪个项目（隐私项目的隔离、项目资料的检索都靠它）
    val project = conv.projectId?.let { id -> store.loadProjects().firstOrNull { it.id == id } }
    // allow 筛过的工具列表；use_skill 打开了新技能时重算（见下面 retool）
    fun toolsFor(unlocked: Set<String>) = PromptBuilder.toolSet(s, skills, conv, project, unlocked)?.let { all ->
        kotlinx.serialization.json.JsonArray(all.filter { t ->
            val name = ((t as? kotlinx.serialization.json.JsonObject)?.get("function") as? kotlinx.serialization.json.JsonObject)?.get("name")
            allow((name as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(), null)
        })
    }
    val tools = toolsFor(conv.skillTools)
    // 隐私项目里的任务不带全局记忆和日记
    val memories = if (s.memoryEnabled && conv.globalAllowed(project)) store.loadMemories() else emptyList()
    val journal = store.loadDreams().firstOrNull { it.journal.isNotBlank() }?.journal.orEmpty()
    val now = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.ENGLISH).format(Date())
    val system = PromptBuilder.systemPrompt(s, conv, project, tools, memories, skills, journal = journal, lately = com.jack.ai.data.FreeTime.latelyNote(ctx)) +
        "\n\n" + com.jack.ai.data.Persona.fill(
            (brief ?: "【Background scheduled task】It is now $now. You're running a scheduled task {user} set up in advance: 「${r.title}」. {User} isn't online and can't be asked anything, so just get it done. ") +
                (if (budget != null && r.convId == com.jack.ai.data.Reader.CONV_ID && conv.projectId == null)
                    "Your workbench is yours to use, change or clear. You don't need to report anything or produce a result."
                else (if (s.fullAuth) "{User} has turned on full authorization: actions like sending email or deleting run without a confirmation prompt. Do only what the task explicitly asks for, and nothing you're unsure about. "
                    else "Actions that need {user}'s confirmation, like sending email or deleting, will be refused; don't try them. ") +
                    "Put everything {user} needs in your final result. That result is the message {user} will read later: short, easy to read, no preamble."),
            s,
        )
    val messages = mutableListOf(
        buildJsonObject { put("role", "system"); put("content", system) },
        buildJsonObject {
            put("role", "user")
            put("content", runCatching { ContextNote.build(ctx, store, conv, skills.map { it.id }.toSet()) }.getOrDefault("") + r.prompt.ifBlank { r.title })
        },
    )
    var spent = 0.0
    // 上次半路被打断了：从断点接着做（系统提示换成这次的，时间是新的）
    val checkpoint = TaskCheckpoint.file(ctx.filesDir, r.id)
    val cpKey = TaskCheckpoint.keyOf((brief ?: "") + r.prompt.ifBlank { r.title })
    var resumed = false
    TaskCheckpoint.load(checkpoint, cpKey)?.let { saved ->
        resumed = true
        val fresh = messages.first()
        messages.clear()
        messages += fresh
        messages += saved.messages.drop(1)
        messages += buildJsonObject { put("role", "user"); put("content", TaskCheckpoint.RESUME_NOTE) }
        spent = saved.spent
        onProgress("接着上次断掉的地方做")
    }
    val files = mutableListOf<GeneratedFile>()
    val session = ToolSession(host, s, conv, project, skills, object : ToolUi {
        override fun started(label: String) = onProgress(label)
        override fun finished(label: String, record: SearchRecord) {}
        override fun attach(file: File) {
            files.removeAll { it.path == file.absolutePath }
            files += GeneratedFile(file.name, file.absolutePath, ToolSession.mimeOf(file.name), file.length())
        }
        override suspend fun confirm(title: String, body: String, action: String) = false
    })
    session.interactive = false
    budget?.let { b -> b.spentNow = { spent }; session.freeBudget = b }
    session.unlocked.addAll(conv.skillTools)
    // 接着断点做：清单和验收标准从断点里找回来
    if (resumed) WorkGate.restorePlan(session, messages)
    // 接着上次做：上次用 use_skill 打开过的技能，工具要接着带上
    if (resumed) TaskCheckpoint.skillsUsed(messages).forEach { name ->
        findSkill(skills, name)?.id?.takeIf { it in com.jack.ai.data.SkillIds.ON_DEMAND }?.let { id ->
            session.unlocked.add(id)
            if (id == com.jack.ai.data.SkillIds.FLOWS) session.unlocked.add(com.jack.ai.data.SkillIds.BROWSER)
        }
    }
    val thinking = if (ep.reasoning && ep.deepseek) s.workThinking.resolve(r.prompt, work = true, hasFiles = false) else ThinkLevel.OFF
    val client = DeepSeekClient()
    val agent = Agent({ msgs, t -> client.stream(ep, msgs, thinking, s.temperature, t) }, sendReasoning = ep.deepseek && thinking.effort != null)
    // 定时的 Work 任务也过收尾关卡；监控、微信这类按固定格式回话的（带 brief）不过
    val gate = if (brief == null) WorkGate(session, review = if (s.workReview) {
        WorkGate.reviewer(session, DeepSeekClient(store::addUsage), s.backgroundEndpoint() ?: ep, r.prompt.ifBlank { r.title }, startedAt)
    } else null) else null
    try {
        agent.run(
            messages = messages,
            tools = if (resumed) toolsFor(session.unlocked.toSet()) else tools,
            maxRounds = { session.maxRounds },
            maxToolCalls = { session.maxRounds },
            execute = { name, args ->
                val limit = budget?.limit ?: s.workCostLimit
                if (limit > 0 && spent >= limit) {
                    session.maxRounds = 0
                    "花费到了上限，不要再调用工具，直接写出目前的结果，并说明还差什么。"
                } else if (!allow(name, args)) "这个工具这次不能用。"
                else session.perform(name, args).also { log(com.jack.ai.data.RunLog.stepOf(name, it.status, it.text)) }.text + (gate?.reminder(name) ?: "")
            },
            retool = { toolsFor(session.unlocked.toSet()) },
            onTools = { TaskCheckpoint.save(checkpoint, cpKey, messages, spent) },
            notices = {
                val b = budget
                if (b != null && b.extra > 0 && !b.noted && spent >= b.soft) { b.noted = true; listOf(b.note()) } else emptyList()
            },
            beforeFinish = gate?.let { g -> { answer -> g.beforeFinish(answer) } },
            slim = { msgs -> com.jack.ai.chat.ContextSlim.slim(msgs) },
            onUsage = { ev ->
                store.addUsage(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens)
                spent += Pricing.priceAt(ep, System.currentTimeMillis())
                    ?.let { Pricing.cost(it, ev.promptTokens, ev.cacheHit ?: 0, ev.completionTokens) } ?: 0.0
            },
        )
    } finally {
        // 占着浏览器的话放出来，聊天里等着用的回答接着用
        session.releaseBrowser()
    }
    TaskCheckpoint.clear(checkpoint)
    val text = agent.lastRound.trim().ifBlank { agent.content.toString().trim() }
    return if (text.isBlank()) TaskResult("（这次没有生成内容）", files, TaskStatus.FAILED, spent) else TaskResult(text, files, spent = spent)
}
