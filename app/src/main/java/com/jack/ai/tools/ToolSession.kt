package com.jack.ai.tools

import com.jack.ai.data.PlanItem

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import com.jack.ai.api.ApiException
import com.jack.ai.api.TavilyClient
import com.jack.ai.data.Conversation
import com.jack.ai.data.Flashcard
import com.jack.ai.data.LibraryIndex
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Project
import com.jack.ai.data.Routine
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Skill
import com.jack.ai.data.SkillIds
import com.jack.ai.data.Store
import com.jack.ai.data.Workspace
import com.jack.ai.data.fullAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/** 工具要改 App 里的数据时找它（由 ChatViewModel 提供）。 */
/** Chat 模式一次回答最多调用几轮工具。 */
const val CHAT_MAX_ROUNDS = 20

interface ToolHost {
    val app: Application
    val store: Store
    val tavily: TavilyClient
    val web: com.jack.ai.api.WebClient
    fun conversations(): List<Conversation>
    fun setMemories(list: List<MemoryItem>)
    fun editCards(transform: (List<Flashcard>) -> List<Flashcard>)
    /** 在主线程调用。 */
    fun saveRoutine(r: Routine)
    fun deleteRoutine(id: String)
    fun libraryIndex(): LibraryIndex
    fun projectIndex(p: Project): LibraryIndex
    fun refreshWidget()
    /** 存一份笔记：projectId 不为空就加进那个项目的资料，否则进资料库。 */
    fun saveNote(projectId: String?, title: String, content: String)
}

/** 一次工具调用成没成：OK 做成了；FAILED 出错或读回核实没通过；REJECTED 要确认的操作没被同意（后台一律不同意）。 */
enum class ToolStatus { OK, FAILED, REJECTED }

data class ToolOutcome(val text: String, val status: ToolStatus)

/** 工具执行过程要在聊天里显示的东西：进行中/完成的标签，生成的文件卡片。 */
interface ToolUi {
    fun started(label: String)
    fun finished(label: String, record: SearchRecord)
    fun attach(file: File)
    /** 做了就收不回的操作（发邮件等）先问用户，返回 true 才执行。工具里别直接调，用 ToolSession.approve。 */
    suspend fun confirm(title: String, body: String, action: String): Boolean
    /** Work 模式的任务清单（步骤和验收标准）更新了。 */
    fun plan(items: List<PlanItem>, criteria: List<PlanItem>) {}
    /** 要让用户一眼看到的事（比如 Kavi 改了自己），在回答里显示成一张小卡片。 */
    fun notice(text: String) {}
    /** 慢的工具（下载大文件）的进度，显示在通知栏上。 */
    fun progress(text: String) {}
}

/**
 * 一次回答里的工具：知道这次开着哪些技能、对话文件夹在哪，按工具名分发到各个工具文件里的实现
 * （WebTools、KnowledgeTools、FileTools、ScheduleTools、CodeTools）。
 */
class ToolSession(
    val host: ToolHost,
    val settings: Settings,
    val conv: Conversation,
    val project: Project?,
    val skills: List<Skill>,
    val ui: ToolUi,
) {
    val app: Application get() = host.app
    val skillIds: Set<String> = skills.map { it.id }.toSet()
    val web = SkillIds.WEB in skillIds
    val wsDir: File = Workspace.dir(host.app, Workspace.key(conv))
    val wsCache = File(wsDir, ".cache")

    /** 最多几轮工具：Chat 20 轮（用了「深度研究」放宽到 25），Work 不限。 */
    var maxRounds = if (conv.work) Int.MAX_VALUE else CHAT_MAX_ROUNDS
    /** 用户在不在（后台任务里是 false）：不在的时候 Kavi 不能改自己。 */
    var interactive = true
    /** 当前模型能不能看图（浏览器截图要用）。 */
    var canSee = false
    /** 这次回答用的模型（显示名），自查时报给 Kavi。 */
    var modelName = ""
    /** 工具产生的图片（data URL），下一轮请求时作为一条用户消息补上，补完清空。 */
    val images = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun takeImages(): List<String> = images.toList().also { images.clear() }

    /** 搜索结果的编号接着往下排，引用时不重号。 */
    internal var sourceCount = 0

    /**
     * 做了就收不回的操作先问用户。设置里开了「完全授权」就直接放行；每次现读设置，到点关掉或用户手动关掉立刻生效。
     * strict 的操作（读过外部内容后发信给新地址）完全授权下也要问。
     */
    suspend fun approve(title: String, body: String, action: String, strict: Boolean = false): Boolean =
        ((!strict && withContext(Dispatchers.IO) { host.store.loadSettings().fullAuth }) || ui.confirm(title, body, action))
            .also { if (!it) rejected.incrementAndGet() }

    /** 没被同意的次数；和 succeeded 一样，execute 前后比一比就知道这次是不是被拒了。 */
    internal val rejected = java.util.concurrent.atomic.AtomicInteger()

    /** 成功跑完的工具次数；execute 前后比一比，就知道这次调用成没成（参数不对没进 runTool 的也算失败）。 */
    internal val succeeded = java.util.concurrent.atomic.AtomicInteger()
    /** 这次回答一共调了几次工具（收尾关卡用：没动过工具的简单问答不用列清单）。 */
    val calls = java.util.concurrent.atomic.AtomicInteger()
    /** 最近一次 update_plan 写下的步骤和验收标准。 */
    @Volatile var steps: List<PlanItem> = emptyList()
    @Volatile var criteria: List<PlanItem> = emptyList()
    /** 这次回答里各类失败出现了几次（见 [Failures]），同一类再出现时提示说得更重。 */
    val failures = java.util.concurrent.ConcurrentHashMap<Failures.Kind, Int>()
    /** 这次回答里工具结果、调用参数中出现过的链接（来源核对用，见 [Sources]）。 */
    val seenUrls: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 开工前确认清单（Work 在聊天里、设置允许时由调用方给）：返回 true 就开始。 */
    var planConfirm: (suspend (steps: List<PlanItem>, criteria: List<PlanItem>) -> Boolean)? = null
    internal var planAsked = false
    /** 让模型停下时给它看的话（maxRounds 设成 0 之后，再调工具就回这句）。 */
    @Volatile var stopNote: String? = null
    /** 这次回答开始的时间：之后改过的文件算这次的产出。 */
    val startedAt = System.currentTimeMillis()

    /** 执行一个工具，聊天里显示「进行中」→「完成/出错」。出错时把原因交给模型，不抛出。 */
    suspend fun runTool(label: String, action: suspend () -> Pair<String, SearchRecord>): String {
        ui.started(label)
        return try {
            val (text, rec) = action()
            ui.finished(label, rec)
            succeeded.incrementAndGet()
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = errorText(e)
            ui.finished(label, SearchRecord(label, error = err))
            "工具调用失败：$err"
        }
    }

    /** 这次回答里用 use_skill 打开的技能（工具按需出现的那些），调用方据此更新工具列表、记进对话。 */
    val unlocked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 这次回答里每个标签页上一步看到的页面：浏览器下一步只告诉模型变了什么。 */
    internal val browserSeen = java.util.concurrent.ConcurrentHashMap<Int, com.jack.ai.ui.JackBrowser.Page>()
    /** 这次回答里已经提醒过「这个网站存了流程」的网站，只提醒一次。 */
    internal val flowHinted: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 这次回答里已经提醒过「到了登录页」的网站。 */
    internal val loginFlagged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 这次回答里因为「读不到文字」自动截过几次图（有上限，截图贵）。 */
    internal val autoShots = java.util.concurrent.atomic.AtomicInteger()

    private val loop = LoopGuard()

    /**
     * 浏览器同一时间只给一个回答用（几个对话同时答、浏览器里的 Kavi、后台任务都算）：
     * 谁先用谁占着，回答结束（releaseBrowser）才放；别的要用就排队等，最多等 BROWSER_WAIT_MS。
     */
    internal suspend fun claimBrowser(): Boolean {
        if (BrowserLock.holds(this)) return true
        if (BrowserLock.tryLock(this)) return true
        val label = "等浏览器空出来（另一个回答正在用）"
        ui.started(label)
        ui.progress(label)
        val got = BrowserLock.lock(this, BROWSER_WAIT_MS)
        ui.finished(label, SearchRecord(label, error = if (got) null else "等了 ${BROWSER_WAIT_MS / 60_000} 分钟还没空出来"))
        return got
    }

    /** 回答结束时调：占着浏览器就放出来。 */
    fun releaseBrowser() = BrowserLock.unlock(this)

    /** 这次回答里读过外部内容（邮件、网页）没有；读过之后发信给新地址，完全授权下也要确认。 */
    @Volatile internal var sawExternal = false

    /** 按工具名执行，返回给模型看的结果，顺便记进工具统计。 */
    suspend fun execute(name: String, argsText: String): String = perform(name, argsText).text

    /** 同 execute，另外带上这次成没成（由代码判定，不看结果文字怎么写）。 */
    suspend fun perform(name: String, argsText: String): ToolOutcome {
        loop.check(name, argsText)?.let { msg ->
            withContext(Dispatchers.IO) { runCatching { host.store.addToolStat(name, failed = true) } }
            return ToolOutcome(msg, ToolStatus.FAILED)
        }
        // 连接器返回的也是外部内容
        if (name in EXTERNAL_TOOLS || name.startsWith(com.jack.ai.data.Mcp.PREFIX)) sawExternal = true
        calls.incrementAndGet()
        val before = succeeded.get()
        val refusedBefore = rejected.get()
        val result = dispatch(name, argsText)
        val status = when {
            rejected.get() != refusedBefore -> ToolStatus.REJECTED
            succeeded.get() == before || com.jack.ai.data.Verify.failed(result) -> ToolStatus.FAILED
            else -> ToolStatus.OK
        }
        withContext(Dispatchers.IO) {
            runCatching { host.store.addToolStat(name, status == ToolStatus.FAILED) }
            // 没做成的连同完整结果记下来，self_check 能翻
            if (status != ToolStatus.OK) runCatching {
                host.store.addToolError(com.jack.ai.data.ToolError(System.currentTimeMillis(), name, argsText.take(300), result.take(800), status == ToolStatus.REJECTED))
            }
        }
        // 没做成（或没拿到东西）：按原因附一句换法
        if (status == ToolStatus.OK) { seenUrls += Sources.find(result); seenUrls += Sources.find(argsText) }
        val kind = Failures.classify(name, result, status)
        val note = kind?.let { Failures.note(it, failures.merge(it, 1, Int::plus) ?: 1, result, settings) }.orEmpty()
        recordSite(name, argsText, result, status, kind)
        return ToolOutcome(result + note, status)
    }

    /** 按工具名分给各自的实现。没开对应技能的工具一律不可用。 */
    private suspend fun dispatch(name: String, argsText: String): String {
        val args = runCatching { Json.parseToJsonElement(argsText).jsonObject }.getOrNull()
        val key = when (name) {
            "read_webpage" -> "url"
            "use_skill" -> "name"
            "run_js", "run_python" -> "code"
            "delete_routine", "delete_calendar_event" -> "id"
            else -> "query"
        }
        val arg = args?.get(key).text()
        return when {
            args == null -> "参数不是有效的 JSON，可能内容太长被截断了，请精简后重试"
            name.startsWith(com.jack.ai.data.Mcp.PREFIX) -> callConnector(name, args)
            name == "query_trains" && SkillIds.TRAIN in skillIds -> queryTrains(args)
            name == "create_app" && SkillIds.APP in skillIds -> makeApp(args)
            name == "github" && SkillIds.GITHUB in skillIds -> github(args)
            name == "browser" && SkillIds.BROWSER in skillIds -> browse(args)
            name == "download_file" && web -> downloadFile(args)
            name == "convert_file" -> convertFile(args)
            name == "self_check" -> selfCheck(args)
            name == "phone_files" -> phoneFiles(args)
            name == "notion_search" && SkillIds.NOTION in skillIds -> notionSearch(args)
            name == "notion_read" && SkillIds.NOTION in skillIds -> notionRead(args)
            name == "notion_write" && SkillIds.NOTION in skillIds -> notionWrite(args)
            name == "create_document" && (SkillIds.WORD in skillIds || SkillIds.PDF in skillIds) -> createDocument(args)
            name in FILE_TOOLS && FILE_TOOLS[name] in skillIds -> makeFile(name, args)
            name == "create_chart" && SkillIds.CALC in skillIds -> makeChart(args)
            name == "create_diagram" && SkillIds.CALC in skillIds -> makeDiagram(args)
            name == "update_plan" && conv.work -> updatePlan(args)
            name == "delegate_tasks" && conv.work -> delegateTasks(args)
            name == "read_file" -> readFile(args)
            name == "write_file" -> writeFile(args)
            name == "edit_file" -> editFile(args)
            name == "delete_file" -> deleteFiles(args)
            name == "search_my_data" -> searchMyData(args)
            name == "schedule" && SkillIds.ROUTINE in skillIds -> schedule(args)
            name == "cancel_schedule" && SkillIds.ROUTINE in skillIds -> cancelSchedule(args)
            name == "save_flashcards" && SkillIds.LEARN in skillIds -> saveCards(args)
            name == "add_calendar_event" && SkillIds.CALENDAR in skillIds -> addEvent(args)
            name == "list_calendar_events" && SkillIds.CALENDAR in skillIds -> listEvents(args)
            name == "save_memory" -> remember(args)
            name == "update_self" -> updateSelf(args)
            name == "save_note" -> saveNote(args)
            name == "add_rows" && SkillIds.TABLES in skillIds -> addRows(args)
            name == "query_table" && SkillIds.TABLES in skillIds -> queryTable(args)
            name == "edit_table" && SkillIds.TABLES in skillIds -> editTable(args)
            name == "list_emails" && SkillIds.MAIL in skillIds -> listEmails(args)
            name == "read_email" && SkillIds.MAIL in skillIds -> readEmail(args)
            name == "send_email" && SkillIds.MAIL in skillIds -> sendEmail(args)
            name == "manage_emails" && SkillIds.MAIL in skillIds -> manageEmails(args)
            // 旧工具名：现在不再发给模型，但老对话里模型可能照着以前的调用记录用，照样能用
            name == "list_files" -> listFiles()
            name == "set_alarm" && SkillIds.ROUTINE in skillIds -> setAlarm(args)
            name == "cancel_alarm" && SkillIds.ROUTINE in skillIds -> cancelAlarm(args)
            name == "list_routines" && SkillIds.ROUTINE in skillIds -> listRoutines()
            name == "create_reminder" && SkillIds.ROUTINE in skillIds -> makeRoutine("reminder", args)
            name == "create_task" && SkillIds.ROUTINE in skillIds -> makeRoutine("task", args)
            name == "list_library" && SkillIds.LIBRARY in skillIds -> listLibrary()
            name == "list_tables" && SkillIds.TABLES in skillIds -> listTables()
            name == "create_table" && SkillIds.TABLES in skillIds -> createTable(args)
            name == "update_rows" && SkillIds.TABLES in skillIds -> updateRows(args)
            name == "delete_rows" && SkillIds.TABLES in skillIds -> deleteRows(args)
            name == "delete_table" && SkillIds.TABLES in skillIds -> deleteTable(args)
            name == "mark_email_read" && SkillIds.MAIL in skillIds -> markEmailRead(args)
            name == "delete_emails" && SkillIds.MAIL in skillIds -> deleteEmails(args)
            name == "move_emails" && SkillIds.MAIL in skillIds -> moveEmails(args)
            name == "empty_mail_trash" && SkillIds.MAIL in skillIds -> emptyMailTrash(args)
            arg.isBlank() -> "参数缺失"
            name == "use_skill" -> useSkill(arg)
            name == "run_js" && SkillIds.CALC in skillIds -> runJs(arg)
            name == "run_python" && SkillIds.PYTHON in skillIds -> runPython(arg)
            name == "delete_calendar_event" && SkillIds.CALENDAR in skillIds -> deleteEvent(arg)
            name == "web_search" && web -> search(arg, args["engine"].text())
            name == "read_webpage" && web -> readPage(arg)
            name == "search_library" && SkillIds.LIBRARY in skillIds -> searchLibrary(arg)
            name == "delete_routine" && SkillIds.ROUTINE in skillIds -> removeRoutine(arg)
            name == "search_history" -> searchOld(arg)
            name == "search_project" -> searchProject(arg)
            name == "search_chats" && SkillIds.CHATS in skillIds -> searchChats(arg)
            else -> "这个工具现在不可用"
        }
    }

    companion object {
        /** 浏览器被别的回答占着时最多等多久。 */
        const val BROWSER_WAIT_MS = 5 * 60_000L

        /** 返回外部内容的工具：内容里可能藏着冒充用户的指令。 */
        val EXTERNAL_TOOLS = setOf("list_emails", "read_email", "web_search", "read_webpage", "notion_search", "notion_read", "browser", "github", "download_file")

        /** 报错尽量带上具体原因，模型才知道该重试、换方法还是去问用户。 */
        /** 报错说清两件事：为什么失败，下一步该做什么（模型照着做，不用瞎试）。 */
        fun errorText(e: Exception): String {
            val net = com.jack.ai.api.Net
            val msg = e.message.orEmpty()
            // 「Failed to connect to api.x.com/1.2.3.4:443」「Unable to resolve host "api.x.com"」里抠出主机名
            val host = Regex("""(?:to |host ")([\w.-]+\.[a-z]{2,})""").find(msg)?.groupValues?.get(1)
                ?: msg.takeIf { e is java.net.UnknownHostException && it.matches(Regex("""[\w.-]+""")) }
            val (what, next) = when (e) {
                is ApiException -> return e.message ?: "接口出错"
                is java.net.SocketTimeoutException -> "网络连接超时" to net.nextStep(host)
                is java.net.UnknownHostException -> "网络连接失败（找不到服务器 ${host ?: msg}）" to net.nextStep(host)
                is java.net.ConnectException ->
                    if ("127.0.0.1" in msg) "连不上 Kavi 专用代理" to "下一步：请用户到 设置 → Kavi 专用代理 点「测试」；不通就换个节点或更新订阅，再重试。"
                    else "网络连接失败（连接被拒绝：$msg）" to net.nextStep(host)
                is javax.net.ssl.SSLException -> "网络连接失败（安全连接出错：$msg）" to
                    "下一步：请用户确认手机时间是否准确；走代理的话换个节点再试。不要改成不校验证书。"
                is SecurityException -> "没有权限（$msg）" to
                    "下一步：请用户在系统设置里给 Kavi 打开相应权限（日历权限可以在左侧菜单「提醒和任务」里授权），然后再试。"
                is java.io.FileNotFoundException -> "找不到文件或没有权限（$msg）" to
                    "下一步：对照【对话文件夹】清单确认文件名，再重试。"
                is IOException -> "读写或网络出错（${msg.ifBlank { e.javaClass.simpleName }}）" to net.nextStep(host)
                else -> return msg.ifBlank { "出错了（${e.javaClass.simpleName}）" }
            }
            return "$what。$next" + net.hint()
        }


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
}

// ---------- 各工具文件共用的小工具 ----------

internal fun JsonElement?.text(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

/** "2026-10-15 09:00" → (日期, 时, 分)；只给 "09:00" 时取最近的那个时间点。 */
internal fun parseDateTime(s: String): Triple<String, Int, Int>? {
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

internal fun findSkill(skills: List<Skill>, name: String): Skill? {
    val n = name.trim()
    return skills.firstOrNull { it.name == n }
        ?: skills.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) }
}

internal fun safeFileName(name: String): String =
    name.trim().replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
        .removeSuffix(".docx").removeSuffix(".pptx").removeSuffix(".xlsx").removeSuffix(".pdf").take(60).trim()

/** 打开系统 App（时钟、日历）。找不到时给模型一句看得懂的原因。 */
internal suspend fun ToolSession.startClock(intent: Intent, missing: String = "手机上没找到能设闹钟的时钟 App") = withContext(Dispatchers.Main) {
    try {
        app.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        throw IllegalStateException(missing)
    } catch (e: SecurityException) {
        throw IllegalStateException("没有设闹钟的权限")
    }
}

/** 任务清单：整份替换，显示在回答上方。不算一次“工具调用”的标记，免得刷屏。 */
/** 碰网站的工具：做完记一笔这个网站怎么样（经验沉淀，见 SiteNotes）。 */
private val SITE_TOOLS = setOf("read_webpage", "download_file", "browser")
private val LOGIN_SITE = Regex("""这一页是 (\S+?) 的登录页""")

internal suspend fun ToolSession.recordSite(name: String, argsText: String, result: String, status: ToolStatus, kind: Failures.Kind?) {
    if (name !in SITE_TOOLS || project?.isolated == true) return
    val host = LOGIN_SITE.find(result)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")
        ?: Sources.find(argsText).firstOrNull()?.let(com.jack.ai.data.SiteNotes::hostOf) ?: return
    val ok = status == ToolStatus.OK && kind == null
    if (!ok && kind == null) return
    withContext(Dispatchers.IO) { runCatching { com.jack.ai.data.SiteNotes.record(app, host, if (ok) null else kind) } }
}

/** 清单有这么多步就算大任务，开工前先给用户确认（设置是 big 时）。 */
internal const val CONFIRM_STEPS = 6

internal suspend fun ToolSession.updatePlan(args: JsonObject): String {
    fun list(key: String, ok: Set<String>, max: Int) = (args[key] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val text = o["text"].text().trim().ifBlank { return@mapNotNull null }
        PlanItem(text.take(120), o["status"].text().takeIf { it in ok } ?: "pending")
    }.take(max)
    val items = list("items", setOf("pending", "doing", "done", "skipped"), 30)
    if (items.isEmpty()) return "清单是空的"
    // 验收标准没传就沿用上次的（模型更新步骤时常常只传 items）
    val crit = if (args["criteria"] is JsonArray) list("criteria", setOf("pending", "met", "failed"), 10) else criteria
    steps = items
    criteria = crit
    ui.plan(items, crit)
    // 第一次写清单：大任务（或设置成每次）先给用户确认，不同意就停下问要怎么改
    if (!planAsked) {
        planAsked = true
        val ask = planConfirm
        val mode = settings.workConfirmPlan
        val big = items.size >= CONFIRM_STEPS || args["confirm"].text() == "true"
        if (ask != null && (mode == "always" || (mode == "big" && big)) && !ask(items, crit)) {
            succeeded.incrementAndGet()
            maxRounds = 0
            stopNote = com.jack.ai.data.Persona.fill("{User} wants to adjust the plan before you start. Don't call any more tools: show the steps and criteria briefly and ask what to change.", settings)
            return stopNote!!
        }
    }
    // 不走 runTool，自己记一次成功，工具统计里才不会算成失败
    succeeded.incrementAndGet()
    val done = items.count { it.status == "done" || it.status == "skipped" }
    return "清单已更新（步骤 $done/${items.size} 完成" +
        (if (crit.isEmpty()) "；还没写验收标准，补上 criteria" else "；验收标准 ${crit.count { it.status != "pending" }}/${crit.size} 已核对") + "）"
}

/** 浏览器的占用：谁占着（一个 ToolSession），没人占时排队的按先后拿到。 */
internal object BrowserLock {
    private val mutex = kotlinx.coroutines.sync.Mutex()

    fun holds(owner: Any) = mutex.holdsLock(owner)

    fun tryLock(owner: Any) = mutex.tryLock(owner)

    /** 等到拿到为止，最多等 timeoutMs；拿到返回 true。 */
    suspend fun lock(owner: Any, timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { mutex.lock(owner); true } ?: false

    fun unlock(owner: Any) {
        if (mutex.holdsLock(owner)) mutex.unlock(owner)
    }
}
