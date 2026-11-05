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
import com.jack.ai.data.globalAllowed
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
/** 一次回答最多调用几轮工具。 */
const val WORK_MAX_ROUNDS = 500

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
    /** 改设置（存技能用）。 */
    fun updateSettings(transform: (com.jack.ai.data.Settings) -> com.jack.ai.data.Settings) = store.saveSettings(transform(store.loadSettings()))
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
    /** Kavi 结束了这个对话（极端辱骂时）。 */
    fun endConversation(reason: String) {}
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
    ui: ToolUi,
) {
    // 思路来自 deepseek-ai/deepseek-harness packages/guard/timeout-policy/，MIT。
    private val sourceUi = ui
    val ui: ToolUi = object : ToolUi by sourceUi {
        override suspend fun confirm(title: String, body: String, action: String) =
            ToolTimeout.waiting { sourceUi.confirm(title, body, action) }
    }
    val app: Application get() = host.app
    val skillIds: Set<String> = skills.map { it.id }.toSet()
    val web = SkillIds.WEB in skillIds
    val wsDir: File = Workspace.dir(host.app, Workspace.key(conv))
    val wsCache = File(wsDir, ".cache")

    /** 最多调用工具 500 次（Chat 和 Work 合并了，每个对话都可能接长任务；Jack 要能交大任务）。 */
    var maxRounds = WORK_MAX_ROUNDS
    /** 用户在不在（后台任务里是 false）：不在的时候 Kavi 不能改自己。 */
    var interactive = true
    /** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal-round-driver/，MIT。自动续做绝不绕过用户确认。 */
    var goalRun = false
    /** 当前模型能不能看图（浏览器截图要用）。 */
    var canSee = false
    /** 这次回答里给 PPT、Word 拍过几次照（[lookAtPages]）：最多两次，免得改了又看、看了又改停不下来。 */
    var pageLooks = 0
    /** 这次回答里生成的 Word/PPT/Excel（文件路径 → 统计编号）：同一次回答里再改算 Kavi 自己返工。 */
    internal val officeJobs = java.util.concurrent.ConcurrentHashMap<String, String>()
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
        ((!goalRun && !strict && withContext(Dispatchers.IO) { host.store.loadSettings().fullAuth }) ||
            // 这一轮读到过疑似注入的内容：弹窗最上面先说一声
            ask(title, injection?.let { "⚠️ 这一轮读到了疑似提示词注入的内容（$it），确认这是你想让 Kavi 做的。\n\n" }.orEmpty() + body, action))
            .also { if (!it) rejected.incrementAndGet() }

    /** 正在替用户操作别的 App 时，Kavi 的弹窗用户看不到：改在屏幕下方的悬浮卡片里问。 */
    private suspend fun ask(title: String, body: String, action: String): Boolean {
        if (goalRun) return ui.confirm(title, body, action)
        val svc = com.jack.ai.a11y.KaviAccessibility.instance
        if (!remote && phoneSeen != null && svc != null && com.jack.ai.a11y.KaviAccessibility.othersInFront(app)) return ToolTimeout.waiting { svc.confirm(title, body, action) }
        return ui.confirm(title, body, action)
    }

    /** 浏览器里的 Kavi：用户问的就是眼前这一页，浏览器要用当前页，不开后台标签页。 */
    @Volatile var browserVisible = false

    /** 用户在别的设备上远程问的（站点）：确认一律在那边网页上问，不弹在手机上。 */
    @Volatile var remote = false

    /** 操作手机（phone 工具）：最近读到的一屏、截图的缩放比例、开始操作时「停止」按过几次（变了就是用户叫停了）。 */
    @Volatile internal var phoneSeen: com.jack.ai.a11y.Screen? = null
    @Volatile internal var phoneScale = 1f
    @Volatile internal var phoneStops = -1
    @Volatile internal var phoneControl: com.jack.ai.a11y.PhoneControl? = null
    internal val phoneActionCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val phoneLastActionAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 同一轮手机任务共享停留状态，模型思考时间计入停留。 */
    internal val phoneDwell = com.wangzan101.mika.core.cap.phone.AppDwell(com.wangzan101.mika.core.cap.phone.AppPacing.human())
    @Volatile internal var phoneLimitStop: String? = null
    /** 上一次 phone 调用做成了没有（steps 一串动作靠它决定要不要接着做）。 */
    @Volatile internal var phoneStepOk = false
    @Volatile internal var phoneCloseSummary: String? = null

    internal suspend fun claimPhone(service: com.jack.ai.a11y.KaviAccessibility, forceMain: Boolean = false): com.jack.ai.a11y.PhoneControl {
        phoneControl?.let { current ->
            if (forceMain && current.background != null) {
                current.close()?.let { note ->
                    phoneCloseSummary = listOfNotNull(phoneCloseSummary, note).joinToString("；")
                }
                phoneSeen = null
                phoneScale = 1f
                return com.jack.ai.a11y.PhoneControl(service, null).also { phoneControl = it }
            }
            current.ready()
            return current
        }
        val lock = com.jack.ai.a11y.PhoneControl.lock
        try {
            if (!lock.tryLock(this)) {
                ui.progress("等另一个手机操作任务结束")
                if (kotlinx.coroutines.withTimeoutOrNull(120_000) { lock.lock(this); true } != true) {
                    throw java.io.IOException("另一个任务仍在操作手机，请稍后继续。")
                }
            }
            val background = if (settings.phoneBackground && !forceMain) com.jack.ai.a11y.BackgroundPhone.create(app, conv.title) else null
            return com.jack.ai.a11y.PhoneControl(service, background).also { phoneControl = it }
        } catch (t: Throwable) {
            if (lock.holdsLock(this)) lock.unlock(this)
            throw t
        }
    }

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
    /** 自由时间的钱（只有自由时间里有）：budget 工具看余额、从积蓄里拿。 */
    @Volatile var freeBudget: com.jack.ai.data.FreeBudget? = null
    /** Kavi 自己提出的下一步，空着就自然结束。 */
    var freeNext: String = ""
    /** 这次回答里已经在网页上提交过的（网站|按钮），同一个再点就是重复提交，要拦。 */
    val submitted: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
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
     * 占着的那个闲了 [BrowserLock.idleMs] 没碰浏览器，排队的就接手（不然用过一次就占到整个回答结束）。
     * 拿到后调用方要在 finally 里 BrowserLock.exit。
     */
    internal suspend fun claimBrowser(): Boolean {
        val viewing = com.jack.ai.ui.JackBrowser.userViewing
        if (!BrowserLock.enter(this, browserVisible, viewing)) {
            val label = "等浏览器空出来（" + (BrowserLock.holderTitle() ?: "别的回答") + "正在用）"
            ui.started(label)
            ui.progress(label)
            val got = BrowserLock.lock(this, BROWSER_WAIT_MS, browserVisible, viewing)
            ui.finished(label, SearchRecord(label, error = if (got) null else "等了 ${BROWSER_WAIT_MS / 60_000} 分钟还没空出来"))
            if (!got) return false
        }
        // 分到后台位子：开一个自己的标签页（被用户关掉了就再开一个）
        withContext(Dispatchers.Main) {
            val b = com.jack.ai.ui.JackBrowser
            b.webView(app)
            if (BrowserLock.needsTab(this@ToolSession) { it in b.tabs && it.web != null }) BrowserLock.setTab(this@ToolSession, b.newTab(switch = false))
        }
        return true
    }

    /** 回答结束时调：占着浏览器就放出来。 */
    suspend fun releaseBrowser(): String? {
        BrowserLock.unlock(this)?.let { BrowserLock.closeQuietly(it) }
        // 所有调用方都在 finally 收尾，取消任务时也要释放虚拟屏和手机操作锁。
        val current = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            val control = phoneControl ?: return@withContext null
            phoneControl = null
            try { control.close() } finally { com.jack.ai.a11y.PhoneControl.lock.unlock(this@ToolSession) }
        }
        val summary = listOfNotNull(phoneCloseSummary, current).joinToString("；").ifBlank { null }
        phoneCloseSummary = null
        return summary
    }

    /** 这次回答里读过外部内容（邮件、网页）没有；读过之后发信给新地址，完全授权下也要确认。 */
    @Volatile internal var sawExternal = false

    /** 这一轮读到疑似提示词注入的内容没有（记着第一次是在哪读到的）：之后往外做事都要用户确认（完全授权除外）。 */
    @Volatile internal var injection: String? = null

    /** 按工具名执行，返回给模型看的结果，顺便记进工具统计。 */
    suspend fun execute(name: String, argsText: String): String = perform(name, argsText).text

    /** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal-round-driver/，MIT。批处理和分身也要遵守目标状态与月度预算。 */
    internal fun goalAllowed(): Boolean {
        if (!goalRun) return true
        val g = com.jack.ai.data.Goals.current(app, conv.id)
        return g != null && g.id == conv.goal?.id && g.status == "active" && !com.jack.ai.data.Goals.pauseForBudget(app, conv.id)
    }

    /** 同 execute，另外带上这次成没成（由代码判定，不看结果文字怎么写）。 */
    suspend fun perform(name: String, argsText: String): ToolOutcome {
        if (!goalAllowed()) return ToolOutcome("目标已停止，不要再调用工具，请用户查看目标进展。", ToolStatus.REJECTED)
        loop.check(name, argsText)?.let { msg ->
            withContext(Dispatchers.IO) { runCatching { host.store.addToolStat(name, failed = true) } }
            return ToolOutcome(msg, ToolStatus.FAILED)
        }
        // 连接器返回的也是外部内容；被扫描的来源（文件、手机文件、电话、手机数据）同样算，不能只认 EXTERNAL_TOOLS，
        // 否则读了文件里的注入内容、再往外发信时，严格确认会被绕过
        if (name in SCANNED_TOOLS || name.startsWith(com.jack.ai.data.Mcp.PREFIX)) sawExternal = true
        calls.incrementAndGet()
        val before = succeeded.get()
        val refusedBefore = rejected.get()
        val args = runCatching { Json.parseToJsonElement(argsText).jsonObject }.getOrNull()
        // 读到过疑似注入的内容：往外做事、自己又不会问的，先问用户（完全授权下 approve 直接放行）
        if (injection != null && com.jack.ai.data.InjectionGuard.gated(name, args?.get("action").text()) &&
            !approve("让 Kavi 接着做这一步？", "Kavi 接下来要：${toolLabel(name, args)}", "继续")) {
            return ToolOutcome("用户没有同意这一步：这一轮读到过疑似提示词注入的内容，往外做事要用户确认。不要再试这个操作，继续做用户交代的、不用往外做事的部分，或者说清楚需要用户确认什么。", ToolStatus.REJECTED)
        }
        val raw = ToolTimeout.run(name) { dispatch(name, argsText) }
        // 外部内容：查一遍有没有冲着 AI 下指令的话（纯代码，不花 token）
        val result = if (name in SCANNED_TOOLS || name.startsWith(com.jack.ai.data.Mcp.PREFIX)) guard(name, args, raw) else raw
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

    /** 查外部内容里的提示词注入：有就标出可疑的行、附上给 Kavi 的提醒，第一次还在回答下面告诉用户、记进统计。 */
    private suspend fun guard(name: String, args: JsonObject?, raw: String): String {
        val scan = com.jack.ai.data.InjectionGuard.scan(raw)
        if (!scan.hit) return scan.text
        val source = toolLabel(name, args)
        val first = injection == null
        if (first) injection = source
        withContext(Dispatchers.IO) { runCatching { com.jack.ai.data.InjectionGuard.record(app, name, source, scan) } }
        if (first) {
            val full = withContext(Dispatchers.IO) { host.store.loadSettings().fullAuth }
            ui.notice(com.jack.ai.data.InjectionGuard.notice(source, scan, full))
        }
        return scan.text + com.jack.ai.data.InjectionGuard.warning(scan, com.jack.ai.data.Persona.zh(settings))
    }

    /** 给用户看的这一步是什么：工具名加上网址、搜的词、文件名这类。 */
    private fun toolLabel(name: String, args: JsonObject?): String {
        val what = listOf("url", "query", "name", "id", "path", "action").firstNotNullOfOrNull { k -> args?.get(k).text().takeIf { it.isNotBlank() } }.orEmpty()
        val where = if (name == "browser") (BrowserLock.pinOf(this)?.t?.address ?: com.jack.ai.ui.JackBrowser.address).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() else ""
        return (name + if (what.isNotEmpty()) "：${what.take(120)}" else "") + where
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
            name == "check_ai_tone" && SkillIds.TONE in skillIds -> checkAiTone(args)
            name == "create_app" && SkillIds.APP in skillIds -> makeApp(args)
            name == "design" && SkillIds.DESIGN in skillIds -> design(args)
            name == "github" && SkillIds.GITHUB in skillIds -> github(args)
            name == "browser" && SkillIds.BROWSER in skillIds -> browse(args)
            name == "download_file" && web -> downloadFile(args)
            name == "convert_file" -> convertFile(args)
            name == "self_check" -> selfCheck(args)
            name == "phone_files" -> phoneFiles(args)
            name == "phone" -> phone(args)
            name == "phone_data" -> phoneData(args)
            name == "notion_search" && SkillIds.NOTION in skillIds -> notionSearch(args)
            name == "notion_read" && SkillIds.NOTION in skillIds -> notionRead(args)
            name == "notion_write" && SkillIds.NOTION in skillIds -> notionWrite(args)
            name == "create_document" && (SkillIds.WORD in skillIds || SkillIds.PDF in skillIds) -> createDocument(args)
            name in FILE_TOOLS && FILE_TOOLS[name] in skillIds -> makeFile(name, args)
            name == "create_chart" && SkillIds.CALC in skillIds -> makeChart(args)
            name == "create_diagram" && SkillIds.CALC in skillIds -> makeDiagram(args)
            name == "update_plan" -> updatePlan(args)
            name == "delegate_tasks" -> delegateTasks(args)
            name in setOf("get_goal", "create_goal", "update_goal") -> goalTool(name, args)
            name == "run_code" -> runCode(args)
            name == "read_file" -> readFile(args)
            name == "write_file" -> writeFile(args)
            name == "edit_file" -> editFile(args)
            name == "revise_file" && (SkillIds.WORD in skillIds || SkillIds.PPT in skillIds || SkillIds.EXCEL in skillIds) -> reviseFile(args)
            name == "read_table_image" && SkillIds.EXCEL in skillIds -> readTableImage(args)
            name == "my_templates" && (SkillIds.WORD in skillIds || SkillIds.PPT in skillIds || SkillIds.EXCEL in skillIds) -> myTemplates(args)
            name == "delete_file" -> deleteFiles(args)
            name == "search_my_data" -> searchMyData(args)
            name == "my_savings" -> mySavings()
            name == "facts" -> if (args["action"].text() == "save") saveFact(args) else recallFacts(args)
            name == "own_history" && ((conv.id == com.jack.ai.data.Reader.CONV_ID && freeBudget != null) || (settings.memoryEnabled && conv.globalAllowed(project))) -> ownHistory(args)
            name == "free_time" && freeBudget != null -> freeTimeTool(args)
            name == "end_conversation" && interactive -> endConversation(args)
            name == "schedule" && SkillIds.ROUTINE in skillIds -> schedule(args)
            name == "cancel_schedule" && SkillIds.ROUTINE in skillIds -> cancelSchedule(args)
            name == "save_flashcards" && SkillIds.LEARN in skillIds -> saveCards(args)
            name == "add_calendar_event" && SkillIds.CALENDAR in skillIds -> addEvent(args)
            name == "list_calendar_events" && SkillIds.CALENDAR in skillIds -> listEvents(args)
            name == "save_memory" -> remember(args)
            name == "update_self" -> updateSelf(args)
            name == "save_skill" -> saveSkill(args)
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
            name == "use_skill" -> useSkill(arg, args["file"].text().trim())
            name == "run_js" && SkillIds.CALC in skillIds -> runJs(arg)
            name == "run_python" && SkillIds.PYTHON in skillIds -> runPython(args)
            name == "check_page" && SkillIds.CODE in skillIds -> checkPage(args)
            name == "search_code" && SkillIds.CODE in skillIds -> searchCode(args)
            name == "file_history" && SkillIds.CODE in skillIds -> fileHistory(args)
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
        /** 结果要查提示词注入的：外部内容，加上读文件（文件常常是从外面来的）。 */
        val SCANNED_TOOLS get() = EXTERNAL_TOOLS + setOf("read_file", "phone_files", "phone", "phone_data")
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
        if (ask != null && (mode == "always" || (mode == "big" && big)) && !ToolTimeout.waiting { ask(items, crit) }) {
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
/**
 * 浏览器分给几个回答同时用：每个回答占一个位子，最多 [MAX] 个。第一个（用户没在看浏览器时）用用户眼前那一页，
 * 和以前一样能在聊天里看到它在做什么、随时接手；再来的各开一个后台标签页，各干各的（见 [JackBrowser.PinTab]），
 * 回答结束就关掉。位子满了才排队；占着位子的闲了 [idleMs] 没碰浏览器，排队的就接手它的位子。
 */
internal object BrowserLock {
    const val MAX = 3

    private class Slot(val owner: Any, val visible: Boolean) {
        var pin: com.jack.ai.ui.JackBrowser.PinTab? = null
        var busy = 0
        var lastUsed = System.currentTimeMillis()
    }

    private val slots = mutableListOf<Slot>()
    /** 被别人接手过、或者用着的标签页被关掉换过的（下次再用时提醒它页面可能变了）。 */
    private val lost = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /** 占着位子的回答这么久没碰浏览器（在想、在写、在做别的），排队的就接手。 */
    @Volatile var idleMs = 20_000L

    private fun slotOf(owner: Any) = slots.firstOrNull { it.owner === owner }

    @Synchronized
    fun holds(owner: Any) = slotOf(owner) != null

    /**
     * 占一个位子（已经占着也行）并记一次「正在用」；没位子就返回 false。用完调 [exit]。
     * wantsVisible：非要用户眼前那一页不可（浏览器里的 Kavi，用户问的就是这一页）；userViewing：用户正开着浏览器自己翻，
     * 这时新来的回答开后台标签页，不抢用户在看的那页。
     */
    @Synchronized
    fun enter(owner: Any, wantsVisible: Boolean = false, userViewing: Boolean = false): Boolean {
        slotOf(owner)?.let { it.busy++; it.lastUsed = System.currentTimeMillis(); return true }
        if (slots.size >= MAX) return false
        val visibleTaken = slots.any { it.visible }
        if (wantsVisible && visibleTaken) return false
        slots += Slot(owner, visible = wantsVisible || (!visibleTaken && !userViewing)).also { it.busy = 1 }
        return true
    }

    @Synchronized
    fun exit(owner: Any) {
        val s = slotOf(owner) ?: return
        if (s.busy > 0) s.busy--
        s.lastUsed = System.currentTimeMillis()
    }

    /** 这个回答该钉在哪个后台标签页上；用户眼前那页的位子是 null（照旧跟着当前页）。 */
    @Synchronized
    fun pinOf(owner: Any): com.jack.ai.ui.JackBrowser.PinTab? = slotOf(owner)?.pin

    /** 后台位子还没有标签页（或者它的被关了）：要开一个。 */
    @Synchronized
    fun needsTab(owner: Any, alive: (com.jack.ai.ui.JackBrowser.Tab) -> Boolean): Boolean {
        val s = slotOf(owner) ?: return false
        return !s.visible && s.pin?.t?.let(alive) != true
    }

    @Synchronized
    fun setTab(owner: Any, t: com.jack.ai.ui.JackBrowser.Tab) {
        val s = slotOf(owner) ?: return
        if (s.pin != null) lost += owner
        s.pin = s.pin?.also { it.t = t } ?: com.jack.ai.ui.JackBrowser.pin(t)
    }

    /** 有回答正用着用户眼前那一页。 */
    @Synchronized
    fun visibleInUse() = slots.any { it.visible }

    /** 是不是哪个回答正用着的后台标签页。 */
    @Synchronized
    fun isAgentTab(t: com.jack.ai.ui.JackBrowser.Tab) = slots.any { it.pin?.t === t }

    /** 后台标签页里网页弹了新窗口：那个回答接着在新窗口里做。 */
    @Synchronized
    fun repin(from: com.jack.ai.ui.JackBrowser.Tab, to: com.jack.ai.ui.JackBrowser.Tab) {
        slots.forEach { s -> s.pin?.takeIf { it.t === from }?.t = to }
    }

    /** 某个标签页被关了：钉在它上面的回答换到 replacement 给的那页。在主线程调。 */
    @Synchronized
    fun tabClosed(t: com.jack.ai.ui.JackBrowser.Tab, replacement: () -> com.jack.ai.ui.JackBrowser.Tab) {
        slots.filter { it.pin?.t === t }.forEach { s -> s.pin?.t = replacement(); lost += s.owner }
    }

    /** 这个对话的回答正用着的后台标签页（聊天里的实时画面放它）。 */
    @Synchronized
    fun tabForConv(convId: String): com.jack.ai.ui.JackBrowser.Tab? =
        slots.firstOrNull { (it.owner as? ToolSession)?.conv?.id == convId }?.pin?.t

    /** 闲够了的位子让出来（wantsVisible 时只看用户眼前那页的位子）。返回它开的后台标签页，调用方在主线程关掉。 */
    @Synchronized
    private fun takeOverIdle(wantsVisible: Boolean): com.jack.ai.ui.JackBrowser.Tab? {
        val now = System.currentTimeMillis()
        val victim = slots.filter { it.busy == 0 && now - it.lastUsed >= idleMs && (!wantsVisible || it.visible) }
            .minByOrNull { it.lastUsed } ?: return null
        slots.remove(victim)
        lost += victim.owner
        return victim.pin?.t
    }

    @Synchronized
    fun takeLost(owner: Any) = lost.remove(owner)

    /** 正占着浏览器的对话名（排队时告诉用户在等谁）。 */
    @Synchronized
    fun holderTitle(): String? = slots.mapNotNull { (it.owner as? ToolSession)?.conv?.title?.takeIf { t -> t.isNotBlank() } }
        .distinct().takeIf { it.isNotEmpty() }?.joinToString("、") { "「${it.take(20)}」" }

    /** 等到占上位子为止（同 [enter]），最多等 timeoutMs；有位子闲够 [idleMs] 就接手。 */
    suspend fun lock(owner: Any, timeoutMs: Long, wantsVisible: Boolean = false, userViewing: Boolean = false): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (!enter(owner, wantsVisible, userViewing)) {
                takeOverIdle(wantsVisible)?.let { closeQuietly(it) }
                kotlinx.coroutines.delay(500)
            }
            true
        } ?: false

    /** 让出位子；返回它开的后台标签页（调用方在主线程关掉）。 */
    @Synchronized
    fun unlock(owner: Any): com.jack.ai.ui.JackBrowser.Tab? {
        val s = slotOf(owner) ?: return null
        slots.remove(s)
        return s.pin?.t
    }

    /** 关掉回答用完的后台标签页；用户已经切过去在看的留着。 */
    suspend fun closeQuietly(t: com.jack.ai.ui.JackBrowser.Tab) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
            val b = com.jack.ai.ui.JackBrowser
            if (b.tab !== t && t in b.tabs) b.closeTab(t)
        }
    }
}
