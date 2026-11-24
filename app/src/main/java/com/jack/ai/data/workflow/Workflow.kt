package com.jack.ai.data.workflow

import com.jack.ai.data.newId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * 工作流：「什么时候开始 → 一步步做什么 → 按条件分叉」，存成固定的步骤，跑的时候不用每步问模型。
 * 步骤是树形的（「如果」「对每一项」里面套步骤），和卡片编辑器一一对应。格式见 docs/工具包与工作流方案.md。
 */

@Serializable
data class Workflow(
    val id: String = newId(),
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val trigger: WfTrigger = WfTrigger(),
    val steps: List<WfStep> = emptyList(),
    val limits: WfLimits = WfLimits(),
    /** 结果送到哪：inbox（收件箱）、notify（通知）、none。 */
    val deliver: String = "inbox",
    /** 谁建的：user、kavi。 */
    val by: String = "user",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 上次到点跑的时间（排下一次、防重复用）。 */
    val lastFiredAt: Long = 0,
)

/**
 * 什么时候开始。type：manual 手动；schedule 定时。
 * 定时的 repeat：daily 每天、weekdays 工作日、weekly 每周（days 里 1=周一 … 7=周日）、interval 每隔 everyMin 分钟、once 某天一次（date）。
 */
@Serializable
data class WfTrigger(
    val type: String = "manual",
    val repeat: String = "daily",
    val time: String = "08:00",
    val days: List<Int> = emptyList(),
    val everyMin: Int = 60,
    val date: String = "",
)

@Serializable
data class WfLimits(
    /** 整个工作流最多跑几分钟。 */
    val timeoutMin: Int = 10,
    /** 一次最多花多少钱（元），只算「让 Kavi 做」和调模型的工具。 */
    val maxCostYuan: Double = 1.0,
)

/**
 * 一步。字段按 type 用：
 * - tool：tool、args（值里能写 {{变量}}）
 * - ai：prompt、system、json（要它按 JSON 回）
 * - notify：title、body
 * - message：text（发到收件箱）
 * - file：file（文件名）、text
 * - code：code（JavaScript，`vars` 是所有变量，return 的值就是结果）
 * - set：value（存一个值）
 * - extract：from、path（JSON 路径，比如 [0].price）或 regex（第一组）
 * - if：cond、then、else
 * - forEach：list、steps（name 是「这一项」的变量名）
 * - wait：seconds
 * - stop：reason
 * 每一步的结果存成变量，名字是 name（没写就用 id）。
 */
@Serializable
data class WfStep(
    val id: String = newId().take(8),
    val type: String,
    val name: String = "",
    val tool: String = "",
    val args: JsonObject = JsonObject(emptyMap()),
    val prompt: String = "",
    val system: String = "",
    val json: Boolean = false,
    val title: String = "",
    val body: String = "",
    val text: String = "",
    val file: String = "",
    val code: String = "",
    val value: JsonElement? = null,
    val from: String = "",
    val path: String = "",
    val regex: String = "",
    val cond: WfCond? = null,
    val then: List<WfStep> = emptyList(),
    @SerialName("else") val otherwise: List<WfStep> = emptyList(),
    val list: String = "",
    val steps: List<WfStep> = emptyList(),
    val seconds: Int = 0,
    val reason: String = "",
    /** 出错了怎么办：stop 停下（默认）、skip 跳过接着做、retry 重试 retries 次再停。 */
    val onError: String = "stop",
    val retries: Int = 0,
) {
    val varName get() = name.ifBlank { id }
}

/**
 * 条件：left op right。op：== != > >= < <= contains（包含）、notContains、empty（为空）、notEmpty、matches（正则）。
 * 也可以用 all（都满足）/ any（满足一个）套几条。
 */
@Serializable
data class WfCond(
    val left: String = "",
    val op: String = "==",
    val right: String = "",
    val all: List<WfCond> = emptyList(),
    val any: List<WfCond> = emptyList(),
)

/** 一次运行的记录。 */
@Serializable
data class WfRun(
    val id: String = newId(),
    val workflowId: String,
    val workflowName: String = "",
    val trigger: String = "manual",
    val startedAt: Long,
    val endedAt: Long = 0,
    /** ok、failed、stopped（停下步骤）、limit（超时或超花钱上限）、running。 */
    val status: String = "running",
    val error: String = "",
    val costYuan: Double = 0.0,
    val steps: List<WfStepLog> = emptyList(),
    /** 最后一步的结果（送到收件箱、通知时用）。 */
    val output: String = "",
)

@Serializable
data class WfStepLog(
    val stepId: String,
    val name: String,
    val type: String,
    /** 在第几层循环里（「对每一项」的第几个），给人看。 */
    val at: String = "",
    val startedAt: Long,
    val ms: Long = 0,
    val ok: Boolean = true,
    val skipped: Boolean = false,
    val input: String = "",
    val output: String = "",
    val error: String = "",
    val costYuan: Double = 0.0,
)

object WorkflowFormat {
    val TYPES = setOf("tool", "ai", "notify", "message", "file", "code", "set", "extract", "if", "forEach", "wait", "stop")
    val OPS = setOf("==", "!=", ">", ">=", "<", "<=", "contains", "notContains", "empty", "notEmpty", "matches")
    private val REPEATS = setOf("daily", "weekdays", "weekly", "interval", "once")
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; explicitNulls = false }
    val pretty = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

    fun parse(text: String): Result<Workflow> {
        val w = runCatching { json.decodeFromString(Workflow.serializer(), text) }.getOrElse { e ->
            return Result.failure(WorkflowError(listOf("不是有效的工作流 JSON：" + e.message.orEmpty().lineSequence().first().take(200))))
        }
        val p = check(w)
        return if (p.isEmpty()) Result.success(w) else Result.failure(WorkflowError(p))
    }

    fun encode(w: Workflow): String = pretty.encodeToString(Workflow.serializer(), w)

    fun check(w: Workflow): List<String> = buildList {
        if (w.name.isBlank()) add("name 不能空")
        if (w.steps.isEmpty()) add("至少要有一步")
        val t = w.trigger
        if (t.type !in setOf("manual", "schedule")) add("trigger.type 只能是 manual 或 schedule")
        if (t.type == "schedule") {
            if (t.repeat !in REPEATS) add("trigger.repeat 只能是 ${REPEATS.joinToString()}")
            if (t.repeat != "interval" && hm(t.time) == null) add("trigger.time 要写成 08:00 这样")
            if (t.repeat == "weekly" && (t.days.isEmpty() || t.days.any { it !in 1..7 })) add("每周的要在 trigger.days 里写星期几（1=周一 … 7=周日）")
            if (t.repeat == "interval" && t.everyMin < 15) add("每隔几分钟的最少 15 分钟")
            if (t.repeat == "once" && !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(t.date)) add("一次的要在 trigger.date 写日期，比如 2026-12-01")
        }
        if (w.limits.timeoutMin !in 1..120) add("limits.timeoutMin 要在 1 到 120 之间")
        if (w.limits.maxCostYuan < 0 || w.limits.maxCostYuan > 50) add("limits.maxCostYuan 要在 0 到 50 元之间")
        if (w.deliver !in setOf("inbox", "notify", "none")) add("deliver 只能是 inbox、notify 或 none")
        val ids = mutableSetOf<String>()
        fun walk(list: List<WfStep>, where: String) {
            list.forEachIndexed { i, s ->
                val at = "$where 第 ${i + 1} 步" + (s.name.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
                if (!ids.add(s.id)) add("$at：id「${s.id}」重复了")
                if (s.type !in TYPES) add("$at：不认识的类型「${s.type}」，能用的：${TYPES.joinToString()}")
                when (s.type) {
                    "tool" -> if (s.tool.isBlank()) add("$at：要写 tool（工具名）")
                    "ai" -> if (s.prompt.isBlank()) add("$at：要写 prompt")
                    "notify" -> if (s.title.isBlank() && s.body.isBlank()) add("$at：通知要写 title 或 body")
                    "message" -> if (s.text.isBlank()) add("$at：要写 text")
                    "file" -> if (s.file.isBlank() || s.file.contains("/") || s.file.contains("..")) add("$at：file 要写一个文件名（不带文件夹）")
                    "code" -> if (s.code.isBlank()) add("$at：要写 code")
                    "extract" -> if (s.from.isBlank() || (s.path.isBlank() && s.regex.isBlank())) add("$at：要写 from，和 path 或 regex")
                    "if" -> { if (s.cond == null) add("$at：要写 cond") else checkCond(s.cond, at); if (s.then.isEmpty() && s.otherwise.isEmpty()) add("$at：then 和 else 里至少有一步") }
                    "forEach" -> { if (s.list.isBlank()) add("$at：要写 list"); if (s.steps.isEmpty()) add("$at：steps 里至少有一步") }
                    "wait" -> if (s.seconds !in 1..3600) add("$at：seconds 要在 1 到 3600 之间")
                }
                if (s.onError !in setOf("stop", "skip", "retry")) add("$at：onError 只能是 stop、skip 或 retry")
                if (s.retries !in 0..5) add("$at：retries 最多 5 次")
                if (s.regex.isNotBlank() && runCatching { Regex(s.regex) }.isFailure) add("$at：正则写错了")
                walk(s.then, "$at 的「是」"); walk(s.otherwise, "$at 的「否」"); walk(s.steps, "$at 里面")
            }
        }
        walk(w.steps, "")
    }

    private fun MutableList<String>.checkCond(c: WfCond, at: String) {
        if (c.all.isNotEmpty() || c.any.isNotEmpty()) { (c.all + c.any).forEach { checkCond(it, at) }; return }
        if (c.op !in OPS) add("$at：条件里的 op「${c.op}」不认识，能用的：${OPS.joinToString()}")
        if (c.op == "matches" && runCatching { Regex(c.right) }.isFailure) add("$at：条件里的正则写错了")
    }

    fun hm(s: String): Pair<Int, Int>? = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s.trim())?.let { m ->
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h in 0..23 && mi in 0..59) h to mi else null
    }

    /** 给人看的「什么时候开始」。 */
    fun describe(t: WfTrigger): String = if (t.type != "schedule") "手动" else when (t.repeat) {
        "daily" -> "每天 ${t.time}"
        "weekdays" -> "工作日 ${t.time}"
        "weekly" -> "每周" + t.days.sorted().joinToString("、") { "一二三四五六日"[(it - 1).coerceIn(0, 6)].toString() } + " ${t.time}"
        "interval" -> "每 " + (if (t.everyMin % 60 == 0) "${t.everyMin / 60} 小时" else "${t.everyMin} 分钟")
        "once" -> "${t.date} ${t.time}"
        else -> t.repeat
    }

    /** 下一次到点的时间，没有了（手动、一次性的过了、关着）返回 null。 */
    fun nextAt(w: Workflow, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
        val t = w.trigger
        if (!w.enabled || t.type != "schedule") return null
        if (t.repeat == "interval") {
            val step = t.everyMin.coerceAtLeast(15) * 60_000L
            return if (w.lastFiredAt <= 0) now + step else maxOf(now + 1_000, w.lastFiredAt + step)
        }
        val (h, m) = hm(t.time) ?: return null
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = now; set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        if (t.repeat == "once") {
            val d = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(t.date) ?: return null
            c.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt(), h, m)
            return c.timeInMillis.takeIf { it > now && w.lastFiredAt < it }
        }
        c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, m)
        repeat(9) {
            // Calendar：周日 = 1 … 周六 = 7；换成 1=周一 … 7=周日
            val dow = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            val ok = when (t.repeat) {
                "weekdays" -> dow <= 5
                "weekly" -> dow in t.days
                else -> true
            }
            if (ok && c.timeInMillis > now) return c.timeInMillis
            c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** 数一共几步（套在里面的也算）。 */
    fun count(steps: List<WfStep>): Int = steps.sumOf { 1 + count(it.then) + count(it.otherwise) + count(it.steps) }
}

class WorkflowError(val problems: List<String>) : Exception(problems.joinToString("；"))
