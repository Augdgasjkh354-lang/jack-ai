package com.jack.ai.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 手机操作考试：用用户勾过的 App 跑一组只看不改的题，按真实路径（后台任务 + phone 工具）做，自动核对、出报告。
 * 改了手机操作的代码、换了模型，跑一遍就知道是变好了还是变差了。
 *
 * 题分调优池（tune）和保留池（holdout）：调提示词时只看调优池，保留池留着做最后的判断，不照着它改。
 * 思路来自 Mika 的真机评测题集（eval/tasks），Apache-2.0；题目是 Kavi 自己的。
 */
object PhoneExam {
    /** [app]：calculator 用计算器；each 每个勾过的 App 各考一次（最多两个）。[answer] 回答要对上的；[grounded] 回答里「」引的原文要真在屏幕上出现过。 */
    data class Task(val id: String, val goal: String, val app: String, val answer: Regex? = null, val grounded: Int = 0, val pool: String = "tune")

    val TASKS = listOf(
        Task("calc-mul", "用手机自带的计算器算 123×456，告诉我结果。只算，不要动别的。", "calculator", Regex("56,?088")),
        Task("calc-div", "用计算器算 98765÷25，告诉我结果。", "calculator", Regex("3,?950\\.6")),
        Task("calc-mix", "用计算器算 (17+28)×3−9，告诉我结果。", "calculator", Regex("(?<![\\d.])126(?![\\d.])"), pool = "holdout"),
        Task("app-first", "打开「{app}」，告诉我第一屏上最显眼的三样东西，用「」引出屏幕上的原文。只看，不要点任何东西。", "each", grounded = 2),
        Task("app-scroll", "打开「{app}」，往下翻两屏，用「」引出你看到的三条内容的原文。只看，不点开、不点赞、不评论。", "each", grounded = 2, pool = "holdout"),
    )

    data class Row(val task: String, val app: String, val pool: String, val result: String, val why: String, val seconds: Int, val cost: Double)

    sealed interface State {
        data object Idle : State
        data class Running(val done: Int, val total: Int, val now: String) : State
        data class Done(val report: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun reportFile(ctx: Context) = File(ctx.filesDir, "phone_exam.md")

    private fun isCalculator(pkg: String, label: String) = "calculator" in pkg.lowercase() || "计算器" in label

    /** 这道题考哪些 App（包名 → 名字）；没有能考的就跳过。 */
    internal fun appsFor(t: Task, ticked: List<Pair<String, String>>): List<Pair<String, String>> = when (t.app) {
        "calculator" -> ticked.filter { (p, l) -> isCalculator(p, l) }.take(1)
        else -> ticked.filterNot { (p, l) -> isCalculator(p, l) || AppUsageNotes.policyForPackage(p).mainScreenOnly }.take(2)
    }

    /** 核对一道题的回答：答案对上了、「」里引的原文有足够多条真在屏幕上出现过。 */
    internal fun grade(t: Task, answer: String, seen: Set<String>): Pair<Boolean, String> {
        t.answer?.let { if (!it.containsMatchIn(answer)) return false to "答案不对" }
        if (t.grounded > 0) {
            fun norm(s: String) = s.filter { it.isLetterOrDigit() }
            val seenN = seen.map(::norm).filter { it.length >= 2 }
            val quotes = Regex("「([^」]{2,80})」").findAll(answer).map { norm(it.groupValues[1]) }.filter { it.length >= 2 }.toList()
            val real = quotes.count { q -> seenN.any { it.contains(q) || q.contains(it) && it.length >= 4 } }
            if (real < t.grounded) return false to "引的原文只有 $real 条在屏幕上出现过（要 ${t.grounded} 条）"
        }
        return true to ""
    }

    fun start(ctx: Context) {
        if (_state.value is State.Running) return
        val app = ctx.applicationContext
        scope.launch { _state.value = State.Done(run(app)) }
    }

    private suspend fun run(ctx: Context): String {
        val s = Store(ctx).loadSettings()
        val pm = ctx.packageManager
        val ticked = s.phoneApps.map { p -> p to runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }
        val plan = TASKS.flatMap { t -> appsFor(t, ticked).map { t to it } }
        val rows = mutableListOf<Row>()
        val skipped = TASKS.filter { appsFor(it, ticked).isEmpty() }.map { it.id }
        plan.forEachIndexed { i, (t, a) ->
            _state.value = State.Running(i, plan.size, "${t.id} · ${a.second}")
            val goal = t.goal.replace("{app}", a.second)
            val started = System.currentTimeMillis()
            var session: com.jack.ai.tools.ToolSession? = null
            val r = Routine(id = "exam-${t.id}-${a.first}", title = "手机操作考试 · ${t.id}", prompt = goal, hour = 0, minute = 0,
                convId = "exam-${t.id}", work = true)
            val res = runCatching {
                com.jack.ai.tools.runBackgroundWork(ctx, r, allow = { name, _ -> name == "phone" || name == "update_plan" },
                    brief = BRIEF, onSession = { session = it })
            }.getOrElse { e -> com.jack.ai.tools.TaskResult("出错：${e.message}", status = com.jack.ai.tools.TaskStatus.FAILED) }
            val (ok, why) = if (res.status == com.jack.ai.tools.TaskStatus.OK) grade(t, res.text, session?.phoneSeenTexts.orEmpty()) else false to res.text.take(80)
            rows += Row(t.id, a.second, t.pool, if (ok) "✅" else "❌", why, ((System.currentTimeMillis() - started) / 1000).toInt(), res.spent)
        }
        val report = format(rows, skipped)
        runCatching { reportFile(ctx).writeText(report) }
        return report
    }

    internal fun format(rows: List<Row>, skipped: List<String>): String = buildString {
        fun score(pool: String) = rows.filter { it.pool == pool }.let { l -> "${l.count { it.result == "✅" }}/${l.size}" }
        append("# 手机操作考试\n\n")
        append("调优池 ${score("tune")}，保留池 ${score("holdout")}；用时 ${rows.sumOf { it.seconds }} 秒，花费 ¥${"%.3f".format(rows.sumOf { it.cost })}\n\n")
        append("| 题 | App | 池 | 结果 | 原因 | 用时 | 花费 |\n|---|---|---|---|---|---:|---:|\n")
        rows.forEach { r -> append("| ${r.task} | ${r.app} | ${r.pool} | ${r.result} | ${r.why.replace("|", "｜").replace("\n", " ")} | ${r.seconds}s | ¥${"%.3f".format(r.cost)} |\n") }
        if (skipped.isNotEmpty()) append("\n没考的（没勾对应的 App）：${skipped.joinToString("、")}。勾上手机自带的计算器能多考几道。\n")
        append("\n调提示词时只看调优池；保留池只用来做最后判断，别照着它改。\n")
    }

    private const val BRIEF = "【Phone exam】This is an automated test of operating phone apps. {User} isn't here and can't be asked anything. " +
        "Only look and read: don't send, post, like, follow, buy, delete or change any setting. Answer in one or two sentences in Chinese; " +
        "when quoting what's on the screen, quote the exact text in 「」."
}
