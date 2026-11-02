package com.jack.ai.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mozilla.javascript.*

/** 思路来自 deepseek-ai/deepseek-harness packages/ptc-runtime/，MIT。只读工具的顺序批处理。 */
object CodeRunner {
    val readOnly = setOf("web_search", "read_webpage", "read_file", "list_files", "list_emails", "read_email",
        "query_table", "list_tables", "search_my_data", "search_chats", "search_history", "search_project",
        "search_library", "notion_search", "notion_read", "search_code", "list_calendar_events", "query_trains")

    private class Stopped(message: String) : Error(message)

    /** 思路来自 deepseek-ai/deepseek-harness packages/ptc-runtime/，MIT。宿主顺序等待，只擦除代码里的 await tools，保留文本和正则。 */
    internal fun sequential(code: String): String = buildString {
        var i = 0
        var regexAllowed = true
        while (i < code.length) {
            val start = i
            val ch = code[i++]
            when {
                ch == '\'' || ch == '"' || ch == '`' -> {
                    while (i < code.length) {
                        val c = code[i++]
                        if (c == '\\' && i < code.length) i++ else if (c == ch) break
                    }
                    regexAllowed = false
                }
                ch == '/' && code.getOrNull(i) == '/' -> {
                    while (i < code.length && code[i] != '\n') i++
                }
                ch == '/' && code.getOrNull(i) == '*' -> {
                    i = code.indexOf("*/", i + 1).let { if (it < 0) code.length else it + 2 }
                }
                ch == '/' && regexAllowed -> {
                    var inClass = false
                    while (i < code.length) {
                        val c = code[i++]
                        if (c == '\\' && i < code.length) i++
                        else if (c == '[') inClass = true
                        else if (c == ']') inClass = false
                        else if (c == '/' && !inClass) break
                    }
                    regexAllowed = false
                }
                ch.isLetter() || ch == '_' || ch == '$' -> {
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] in "_$")) i++
                    val word = code.substring(start, i)
                    if (word == "await" && Regex("""^\s+tools\s*[.\[]""").containsMatchIn(code.substring(i))) continue
                    regexAllowed = word in setOf("return", "throw", "typeof", "void", "delete", "case", "in")
                }
                !ch.isWhitespace() -> regexAllowed = !ch.isDigit() && ch !in ")]} ."
            }
            append(code, start, i)
        }
    }

    /** 思路来自 deepseek-ai/deepseek-harness packages/ptc-runtime/，MIT。隔离作用域、调用次数与截止时间，出错时保留已完成的结果。 */
    suspend fun run(code: String, timeoutMs: Long = 60_000, execute: suspend (String, String) -> String): String =
        withContext(Dispatchers.Default) {
            val parent = currentCoroutineContext()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            val results = mutableListOf<String>()
            val denied = mutableListOf<String>()
            var calls = 0
            val cx = JsRunner.Factory.enterContext()
            try {
                val scope = cx.initSafeStandardObjects()
                var value: Any? = null
                var error: String? = null
                try {
                    cx.putThreadLocal("job", parent[Job])
                    cx.putThreadLocal("start", System.currentTimeMillis())
                    cx.putThreadLocal("timeout", timeoutMs)
                    cx.evaluateString(scope, JsRunner.PRELUDE + "\nvar print = console.log;", "prelude", 1, null)
                    val tools = object : ScriptableObject() {
                        override fun getClassName() = "Tools"
                        override fun get(name: String, start: Scriptable): Any = object : BaseFunction() {
                            override fun call(c: Context, s: Scriptable, self: Scriptable, args: Array<out Any>): Any {
                                parent.ensureActive()
                                if (calls++ >= 30) throw Stopped("已停止：最多调用 30 次工具")
                                val left = (deadline - System.nanoTime()) / 1_000_000
                                if (left <= 0) throw Stopped("已停止：总时长超过 60 秒")
                                val json = NativeJSON.stringify(c, scope, args.firstOrNull() ?: c.newObject(scope), null, null)
                                val params = Json.parseToJsonElement(Context.toString(json)).jsonObject
                                if (name !in readOnly || (name == "read_email" && params["save_attachments"]?.jsonPrimitive?.content == "true"))
                                    return "错误：run_code 不开放 $name 的写入或其他有影响的操作，请改用普通工具调用（读邮件不要设 save_attachments=true）。".also { denied += it }
                                val result = runBlocking(parent) {
                                    withTimeoutOrNull(left) { execute(name, Context.toString(json)) }
                                } ?: throw Stopped("已停止：总时长超过 60 秒")
                                results += "$name：$result"
                                return result
                            }
                        }
                    }
                    ScriptableObject.putProperty(scope, "tools", tools)
                    value = cx.evaluateString(scope, "(function(){\n${sequential(code)}\n})()", "run_code", 1, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e.message ?: e.javaClass.simpleName
                }
                cx.removeThreadLocal("start")
                try {
                    buildString {
                        append("输出：\n").append(Context.toString(cx.evaluateString(scope, "__out.join('\\n')", "output", 1, null)))
                        if (value != null && value !is Undefined) append("\n返回值：").append(
                            if (value is Scriptable) Context.toString(NativeJSON.stringify(cx, scope, value, null, null)) else Context.toString(value))
                        if (denied.isNotEmpty()) append("\n").append(denied.joinToString("\n"))
                        if (error != null) append("\n停止原因：$error\n已取得的结果：\n").append(results.joinToString("\n"))
                    }
                } finally { cx.removeThreadLocal("job"); cx.removeThreadLocal("timeout") }
            } finally { Context.exit() }
        }
}
