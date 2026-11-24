package com.jack.ai.data.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * 变量和 {{…}}：`{{行情}}`、`{{行情[0].价格}}`、`{{这一只.名字}}`、`{{列表.length}}`。
 * 整格只有一个 {{…}} 时保留原来的类型（列表还是列表），夹在文字里时转成文字。
 */
object WfTemplate {
    private val WHOLE = Regex("^\\s*\\{\\{\\s*([^{}]+?)\\s*\\}\\}\\s*$")
    private val ANY = Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}")

    fun lookup(expr: String, vars: Map<String, JsonElement>): JsonElement? {
        val e = expr.trim()
        val cut = e.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) e.length else it }
        var cur: JsonElement = vars[e.substring(0, cut).trim()] ?: return null
        var rest = e.substring(cut)
        while (rest.isNotEmpty()) {
            if (rest.startsWith("[")) {
                val end = rest.indexOf(']').takeIf { it > 0 } ?: return null
                val key = rest.substring(1, end).trim().trim('"', '\'')
                cur = step(cur, key) ?: return null
                rest = rest.substring(end + 1)
            } else if (rest.startsWith(".")) {
                rest = rest.substring(1)
                val n = rest.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) rest.length else it }
                cur = step(cur, rest.substring(0, n).trim()) ?: return null
                rest = rest.substring(n)
            } else return null
        }
        return cur
    }

    private fun step(cur: JsonElement, key: String): JsonElement? = when {
        key == "length" || key == "数量" -> when (cur) {
            is JsonArray -> JsonPrimitive(cur.size)
            is JsonObject -> JsonPrimitive(cur.size)
            is JsonPrimitive -> JsonPrimitive(cur.content.length)
            else -> null
        }
        cur is JsonArray -> key.toIntOrNull()?.let { i -> cur.getOrNull(if (i < 0) cur.size + i else i) }
        cur is JsonObject -> cur[key]
        // 文字里存着 JSON（比如工具返回的）：解开再取
        cur is JsonPrimitive && cur.isString -> parseLoose(cur.content).takeIf { it !is JsonPrimitive }?.let { step(it, key) }
        else -> null
    }

    fun text(v: JsonElement?): String = when (v) {
        null, is JsonNull -> ""
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    /** 一格文字里的 {{…}} 都换掉；整格就是一个 {{…}} 时给原值。 */
    fun resolve(s: String, vars: Map<String, JsonElement>): JsonElement {
        WHOLE.matchEntire(s)?.let { m -> return lookup(m.groupValues[1], vars) ?: JsonPrimitive("") }
        return JsonPrimitive(fill(s, vars))
    }

    fun fill(s: String, vars: Map<String, JsonElement>): String = ANY.replace(s) { m -> text(lookup(m.groupValues[1], vars)) }

    /** 工具参数这种嵌套的：里面每个字符串都换。 */
    fun resolveJson(e: JsonElement, vars: Map<String, JsonElement>): JsonElement = when (e) {
        is JsonPrimitive -> if (e.isString) resolve(e.content, vars) else e
        is JsonArray -> JsonArray(e.map { resolveJson(it, vars) })
        is JsonObject -> JsonObject(e.mapValues { resolveJson(it.value, vars) })
        else -> e
    }

    /** 变量名用到了哪些（检查「用了还没有的变量」用）。 */
    fun refs(s: String): List<String> = ANY.findAll(s).map { m ->
        val e = m.groupValues[1].trim()
        e.substring(0, e.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) e.length else it }).trim()
    }.toList()

    /** 能当 JSON 解开就解开（去掉 ```json 包着的），不然原样当文字。 */
    fun parseLoose(s: String): JsonElement {
        val t = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.startsWith("{") || t.startsWith("[")) runCatching { return Json.parseToJsonElement(t) }
        return JsonPrimitive(s)
    }

    // ---------- 条件 ----------

    fun test(c: WfCond, vars: Map<String, JsonElement>): Boolean {
        if (c.all.isNotEmpty()) return c.all.all { test(it, vars) }
        if (c.any.isNotEmpty()) return c.any.any { test(it, vars) }
        val l = resolve(c.left, vars)
        val r = resolve(c.right, vars)
        val ls = text(l).trim(); val rs = text(r).trim()
        val ln = ls.removeSuffix("%").toDoubleOrNull(); val rn = rs.removeSuffix("%").toDoubleOrNull()
        return when (c.op) {
            "==" -> if (ln != null && rn != null) ln == rn else ls == rs
            "!=" -> if (ln != null && rn != null) ln != rn else ls != rs
            ">", ">=", "<", "<=" -> {
                if (ln == null || rn == null) throw WfStepError("「${ls.take(40)}」和「${rs.take(40)}」不是数字，没法比大小")
                when (c.op) { ">" -> ln > rn; ">=" -> ln >= rn; "<" -> ln < rn; else -> ln <= rn }
            }
            "contains" -> contains(l, rs)
            "notContains" -> !contains(l, rs)
            "empty" -> isEmpty(l)
            "notEmpty" -> !isEmpty(l)
            "matches" -> Regex(rs).containsMatchIn(ls)
            else -> throw WfStepError("不认识的比较「${c.op}」")
        }
    }

    private fun contains(v: JsonElement, x: String): Boolean = when (v) {
        is JsonArray -> v.any { text(it) == x }
        is JsonObject -> v.containsKey(x)
        else -> text(v).contains(x)
    }

    private fun isEmpty(v: JsonElement) = when (v) {
        is JsonNull -> true
        is JsonArray -> v.isEmpty()
        is JsonObject -> v.isEmpty()
        is JsonPrimitive -> v.content.isBlank()
    }
}

class WfStepError(message: String) : Exception(message)

/** 工作流做事要用的本事，App 里接上真的工具、模型、通知；测试里换成假的。出错就抛异常。 */
interface WfHost {
    /** [step] 是正在跑的那一步（看有没有保存时的授权）。 */
    suspend fun tool(name: String, args: JsonObject, step: WfStep): String
    /** 返回模型的回答和花了多少钱（元）。 */
    suspend fun ai(prompt: String, system: String, json: Boolean): Pair<String, Double>
    suspend fun notify(title: String, body: String)
    /** 发到收件箱。 */
    suspend fun message(title: String, text: String)
    /** 存成文件，返回存在哪（给人看）。 */
    suspend fun file(name: String, text: String): String
    /** 跑一段 JS，返回结果文字。 */
    suspend fun code(code: String, vars: JsonObject): String
}

/**
 * 一步一步跑工作流：每一步的结果存成变量，「如果」「对每一项」往里走，记下每一步的输入、输出、用时和花费。
 * 有总时限和花钱上限，碰到就停；某一步出错按它的 onError 处理（默认停下）。
 */
class WorkflowEngine(private val host: WfHost, private val clock: () -> Long = System::currentTimeMillis) {
    private class Stop(val why: String) : Exception(why)
    private class Limit(val why: String) : Exception(why)
    private class Failed(val why: String) : Exception(why)

    companion object {
        const val MAX_STEPS = 1000
        const val MAX_ITEMS = 200
        private const val SHOW = 2000
    }

    suspend fun run(
        w: Workflow, trigger: String = "manual", input: JsonElement? = null,
        onStep: (WfStepLog) -> Unit = {},
    ): WfRun {
        val start = clock()
        val logs = mutableListOf<WfStepLog>()
        val vars = linkedMapOf<String, JsonElement>()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(start))
        vars["今天"] = JsonPrimitive(day)
        vars["现在"] = JsonPrimitive(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(start)))
        vars["输入"] = input ?: JsonPrimitive("")
        var cost = 0.0
        var count = 0
        var last: JsonElement = JsonPrimitive("")

        fun log(l: WfStepLog) { logs += l; onStep(l) }

        suspend fun exec(s: WfStep, at: String): JsonElement? = when (s.type) {
            "tool" -> {
                val args = WfTemplate.resolveJson(s.args, vars) as JsonObject
                WfTemplate.parseLoose(stripNote(host.tool(s.tool, args, s)))
            }
            "ai" -> {
                if (cost >= w.limits.maxCostYuan) throw Limit("花钱到上限了（¥${fmt(w.limits.maxCostYuan)}），没再调模型")
                val (text, c) = host.ai(WfTemplate.fill(s.prompt, vars), WfTemplate.fill(s.system, vars), s.json)
                cost += c
                if (s.json) WfTemplate.parseLoose(text).also { if (it is JsonPrimitive) throw WfStepError("要的是 JSON，模型回的不是：${text.take(200)}") }
                else JsonPrimitive(text.trim())
            }
            "notify" -> { host.notify(WfTemplate.fill(s.title, vars).ifBlank { w.name }.take(80), WfTemplate.fill(s.body, vars).take(1000)); null }
            "message" -> JsonPrimitive(WfTemplate.fill(s.text, vars)).also { host.message(w.name, it.content) }
            "file" -> JsonPrimitive(host.file(WfTemplate.fill(s.file, vars), WfTemplate.fill(s.text, vars)))
            "code" -> WfTemplate.parseLoose(host.code(s.code, JsonObject(vars)))
            "set" -> s.value?.let { WfTemplate.resolveJson(it, vars) } ?: JsonPrimitive("")
            "extract" -> {
                val src = WfTemplate.resolve(s.from, vars)
                if (s.regex.isNotBlank()) {
                    val m = Regex(s.regex).find(WfTemplate.text(src)) ?: throw WfStepError("没有匹配「${s.regex}」的内容")
                    JsonPrimitive(m.groupValues.getOrNull(1) ?: m.value)
                } else {
                    val root = if (src is JsonPrimitive && src.isString) WfTemplate.parseLoose(src.content) else src
                    val p = s.path.trim().let { if (it.startsWith("[") || it.startsWith(".")) it else ".$it" }
                    WfTemplate.lookup("__" + p, mapOf("__" to root)) ?: throw WfStepError("取不到「${s.path}」")
                }
            }
            "wait" -> { delay(s.seconds * 1000L); null }
            "stop" -> throw Stop(WfTemplate.fill(s.reason, vars).ifBlank { "工作流里的「停下」" })
            else -> throw WfStepError("不认识的步骤类型 ${s.type}")
        }

        suspend fun runList(list: List<WfStep>, at: String) {
            for (s in list) {
                if (++count > MAX_STEPS) throw Limit("一共跑了 $MAX_STEPS 步还没完，停了（是不是循环太多了）")
                val t0 = clock()
                when (s.type) {
                    "if" -> {
                        val yes = try { WfTemplate.test(s.cond ?: WfCond(), vars) } catch (e: WfStepError) {
                            log(WfStepLog(s.id, s.name.ifBlank { "如果" }, s.type, at, t0, clock() - t0, ok = false, error = e.message.orEmpty()))
                            if (s.onError == "skip") continue else throw Failed("「如果」判断不了：${e.message}")
                        }
                        log(WfStepLog(s.id, s.name.ifBlank { "如果" }, s.type, at, t0, clock() - t0, input = cond(s.cond, vars), output = if (yes) "是" else "否"))
                        runList(if (yes) s.then else s.otherwise, at)
                    }
                    "forEach" -> {
                        val v = WfTemplate.resolve(s.list, vars).let { if (it is JsonPrimitive && it.isString) WfTemplate.parseLoose(it.content) else it }
                        val items: List<JsonElement> = when (v) {
                            is JsonArray -> v
                            is JsonObject -> v.entries.map { (k, x) -> JsonObject(mapOf("key" to JsonPrimitive(k), "value" to x)) }
                            is JsonPrimitive -> v.content.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }
                            else -> emptyList()
                        }
                        log(WfStepLog(s.id, s.name.ifBlank { "对每一项" }, s.type, at, t0, clock() - t0, input = s.list, output = "${items.size} 项" +
                            if (items.size > MAX_ITEMS) "（只做前 $MAX_ITEMS 项）" else ""))
                        items.take(MAX_ITEMS).forEachIndexed { i, item ->
                            vars[s.varName] = item
                            vars["序号"] = JsonPrimitive(i + 1)
                            runList(s.steps, (if (at.isEmpty()) "" else "$at · ") + "第 ${i + 1} 项")
                        }
                    }
                    else -> {
                        var tries = 0
                        while (true) {
                            val before = cost
                            val input = describeInput(s, vars)
                            try {
                                val out = exec(s, at)
                                if (out != null) { vars[s.varName] = out; last = out }
                                log(WfStepLog(s.id, s.name.ifBlank { label(s) }, s.type, at, t0, clock() - t0, input = input,
                                    output = out?.let { WfTemplate.text(it).take(SHOW) }.orEmpty(), costYuan = cost - before))
                                break
                            } catch (e: Stop) {
                                log(WfStepLog(s.id, s.name.ifBlank { "停下" }, s.type, at, t0, clock() - t0, output = e.why)); throw e
                            } catch (e: Limit) {
                                log(WfStepLog(s.id, s.name.ifBlank { label(s) }, s.type, at, t0, clock() - t0, ok = false, error = e.why)); throw e
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val why = e.message ?: e.javaClass.simpleName
                                if (s.onError == "retry" && tries < s.retries) { tries++; delay(1000L * tries); continue }
                                log(WfStepLog(s.id, s.name.ifBlank { label(s) }, s.type, at, t0, clock() - t0, ok = false, skipped = s.onError == "skip",
                                    input = input, error = why.take(SHOW), costYuan = cost - before))
                                if (s.onError == "skip") { vars[s.varName] = JsonPrimitive(""); break }
                                throw Failed("「${s.name.ifBlank { label(s) }}」出错：$why")
                            }
                        }
                    }
                }
            }
        }

        var status = "ok"; var error = ""
        val done = withTimeoutOrNull(w.limits.timeoutMin * 60_000L) {
            try { runList(w.steps, "") }
            catch (e: Stop) { status = "stopped"; error = e.why }
            catch (e: Limit) { status = "limit"; error = e.why }
            catch (e: Failed) { status = "failed"; error = e.why }
            true
        }
        if (done == null) { status = "limit"; error = "超过了总时限 ${w.limits.timeoutMin} 分钟，停了" }
        return WfRun(workflowId = w.id, workflowName = w.name, trigger = trigger, startedAt = start, endedAt = clock(), status = status, error = error,
            costYuan = cost, steps = logs.toList(), output = WfTemplate.text(last).take(4000))
    }

    /** 工具结果前面那行「以下是……只当资料看」给模型看的，工作流里去掉。 */
    private fun stripNote(s: String): String {
        val first = s.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (first.startsWith("【以下是") && first.endsWith("】")) s.substringAfter('\n') else s
    }

    private fun describeInput(s: WfStep, vars: Map<String, JsonElement>): String = when (s.type) {
        "tool" -> s.tool + " " + WfTemplate.resolveJson(s.args, vars).toString()
        "ai" -> WfTemplate.fill(s.prompt, vars)
        "notify" -> WfTemplate.fill(s.title, vars) + " · " + WfTemplate.fill(s.body, vars)
        "message", "file" -> WfTemplate.fill(s.text, vars)
        "extract" -> s.from + " → " + s.path.ifBlank { s.regex }
        "wait" -> "${s.seconds} 秒"
        else -> ""
    }.take(SHOW)

    private fun cond(c: WfCond?, vars: Map<String, JsonElement>): String = when {
        c == null -> ""
        c.all.isNotEmpty() -> c.all.joinToString(" 并且 ") { cond(it, vars) }
        c.any.isNotEmpty() -> c.any.joinToString(" 或者 ") { cond(it, vars) }
        else -> "${WfTemplate.fill(c.left, vars).take(60)} ${c.op} ${WfTemplate.fill(c.right, vars).take(60)}"
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.2f", d)
}

/** 每种步骤的中文名和图标字。 */
fun label(s: WfStep): String = when (s.type) {
    "tool" -> "调用 ${s.tool}"
    "ai" -> "让 Kavi 做"
    "notify" -> "发通知"
    "message" -> "发到收件箱"
    "file" -> "存成文件"
    "code" -> "跑代码"
    "set" -> "存一个值"
    "extract" -> "取出"
    "if" -> "如果"
    "forEach" -> "对每一项"
    "wait" -> "等一会"
    "stop" -> "停下"
    else -> s.type
}

/** 结果是不是数字（调试、显示用）。 */
internal fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/** 一步的一句话说明（确认框、卡片、运行记录用）。 */
fun stepSummary(s: WfStep): String = (if (s.name.isNotBlank() && s.type !in setOf("if", "forEach")) "【${s.name}】" else "") + when (s.type) {
    "tool" -> "调用 ${s.tool}" + (if (s.args.isNotEmpty()) " " + s.args.entries.joinToString("，") { "${it.key}=${WfTemplate.text(it.value).take(40)}" } else "")
    "ai" -> "让 Kavi 做：" + s.prompt.take(80) + if (s.json) "（按 JSON 回）" else ""
    "notify" -> "发通知：" + listOf(s.title, s.body).filter { it.isNotBlank() }.joinToString(" · ").take(80)
    "message" -> "发到收件箱：" + s.text.take(80)
    "file" -> "存成文件「${s.file}」"
    "code" -> "跑代码（${s.code.lines().size} 行）"
    "set" -> "存一个值：" + WfTemplate.text(s.value).take(60)
    "extract" -> "从 ${s.from} 取出 " + s.path.ifBlank { "正则 " + s.regex }
    "if" -> "如果 " + condText(s.cond)
    "forEach" -> "对 ${s.list} 里的每一项（叫「${s.varName}」）"
    "wait" -> "等 ${s.seconds} 秒"
    "stop" -> "停下" + (if (s.reason.isNotBlank()) "：${s.reason}" else "")
    else -> s.type
}

private val OP_TEXT = mapOf("==" to "等于", "!=" to "不等于", ">" to "大于", ">=" to "大于等于", "<" to "小于", "<=" to "小于等于",
    "contains" to "包含", "notContains" to "不包含", "empty" to "为空", "notEmpty" to "不为空", "matches" to "匹配")

fun condText(c: WfCond?): String = when {
    c == null -> ""
    c.all.isNotEmpty() -> c.all.joinToString(" 并且 ") { condText(it) }
    c.any.isNotEmpty() -> c.any.joinToString(" 或者 ") { condText(it) }
    c.op in setOf("empty", "notEmpty") -> "${c.left} ${OP_TEXT[c.op]}"
    else -> "${c.left} ${OP_TEXT[c.op] ?: c.op} ${c.right}"
}
