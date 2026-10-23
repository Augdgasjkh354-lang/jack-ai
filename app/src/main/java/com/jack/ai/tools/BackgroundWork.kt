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
    override val web = WebClient(tavily) { store.addPaidCall(WebClient.USAGE_KEY, WebClient.PRICE) }
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
data class TaskResult(val text: String, val files: List<GeneratedFile> = emptyList(), val status: TaskStatus = TaskStatus.OK)

/**
 * 到点在后台跑一个 Work 任务：和聊天里的 Work 一样能用全部开着的技能（邮箱、文件、联网……），
 * 文件存进这个任务对话的文件夹。用户不在线，需要确认的操作（发邮件等）一律不做。
 * 花费到 Work 上限就收尾。
 */
suspend fun runBackgroundWork(ctx: Context, r: Routine, log: (com.jack.ai.data.RunStep) -> Unit = {}, onProgress: (String) -> Unit = {}): TaskResult {
    val host = BackgroundToolHost(ctx)
    val store = host.store
    val s = store.loadSettings()
    val ep = s.endpoint(s.workModel)?.takeIf { it.apiKey.isNotBlank() } ?: s.backgroundEndpoint()
        ?: return TaskResult(NO_KEY, status = TaskStatus.NEEDS_SETUP)
    val conv = store.loadConversations().firstOrNull { it.id == r.convId }?.copy(work = true)
        ?: Conversation(id = r.convId, title = "⏰ " + r.title, work = true)
    val skills = s.activeSkills()
    val tools = PromptBuilder.toolSet(s, skills, conv, null)
    // 隐私项目里的任务不带全局记忆和日记
    val project = conv.projectId?.let { id -> store.loadProjects().firstOrNull { it.id == id } }
    val memories = if (s.memoryEnabled && conv.globalAllowed(project)) store.loadMemories() else emptyList()
    val journal = store.loadDreams().firstOrNull { it.journal.isNotBlank() }?.journal.orEmpty()
    val now = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date())
    val system = PromptBuilder.systemPrompt(s, conv, project, tools, memories, skills, journal = journal) +
        "\n\n【后台定时任务】现在是$now。你在后台执行用户预先设定的定时任务「${r.title}」，用户此刻不在线，不能向他提问，直接把任务做完。" +
        (if (s.fullAuth) "用户开了完全授权，发邮件、删除这类操作不会弹窗确认、会直接执行，只做任务里明确要求的，拿不准的不要做。"
        else "发邮件、删除这类需要用户确认的操作会被拒绝，不要尝试。") +
        "需要交给用户的内容直接写在最后的结果里。结果就是用户稍后会看到的消息，简洁好读，不要开场白。"
    val messages = mutableListOf(
        buildJsonObject { put("role", "system"); put("content", system) },
        buildJsonObject {
            put("role", "user")
            put("content", runCatching { ContextNote.build(ctx, store, conv, skills.map { it.id }.toSet()) }.getOrDefault("") + r.prompt.ifBlank { r.title })
        },
    )
    val files = mutableListOf<GeneratedFile>()
    val session = ToolSession(host, s, conv, null, skills, object : ToolUi {
        override fun started(label: String) = onProgress(label)
        override fun finished(label: String, record: SearchRecord) {}
        override fun attach(file: File) {
            files.removeAll { it.path == file.absolutePath }
            files += GeneratedFile(file.name, file.absolutePath, ToolSession.mimeOf(file.name), file.length())
        }
        override suspend fun confirm(title: String, body: String, action: String) = false
    })
    val thinking = if (ep.reasoning && ep.deepseek) s.workThinking.resolve(r.prompt, work = true, hasFiles = false) else ThinkLevel.OFF
    val client = DeepSeekClient()
    val agent = Agent({ msgs, t -> client.stream(ep, msgs, thinking, s.temperature, t) }, sendReasoning = ep.deepseek && thinking.effort != null)
    var spent = 0.0
    agent.run(
        messages = messages,
        tools = tools,
        maxRounds = { session.maxRounds },
        execute = { name, args ->
            if (s.workCostLimit > 0 && spent >= s.workCostLimit) {
                session.maxRounds = 0
                "花费到了上限，不要再调用工具，直接写出目前的结果，并说明还差什么。"
            } else session.perform(name, args).also { log(com.jack.ai.data.RunLog.stepOf(name, it.status, it.text)) }.text
        },
        onUsage = { ev ->
            store.addUsage(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens)
            spent += Pricing.priceAt(ep, System.currentTimeMillis())
                ?.let { Pricing.cost(it, ev.promptTokens, ev.cacheHit ?: 0, ev.completionTokens) } ?: 0.0
        },
    )
    val text = agent.lastRound.trim().ifBlank { agent.content.toString().trim() }
    return if (text.isBlank()) TaskResult("（这次没有生成内容）", files, TaskStatus.FAILED) else TaskResult(text, files)
}
