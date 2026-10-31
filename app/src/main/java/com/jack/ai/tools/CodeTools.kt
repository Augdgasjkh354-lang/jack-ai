package com.jack.ai.tools

import android.content.Intent
import android.provider.AlarmClock
import com.jack.ai.api.formatResultsForModel
import com.jack.ai.api.toSources
import com.jack.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 跑代码：JavaScript 精确计算、Python。 */

internal suspend fun ToolSession.runJs(code: String) = runTool("运行代码计算") {
    withContext(Dispatchers.Default) { JsRunner.run(code) } to SearchRecord("运行代码计算")
}

/** 手机上的 Python 没有 pip，subprocess 调它只会返回 1、什么输出都没有；先拦下来说清楚。 */
private val PIP = Regex("""(^|[\s"'(\[])(pip3?\s+install|-m\s+pip)\b|pip\.main""")

internal suspend fun ToolSession.runPython(args: JsonObject): String {
    val code = args["code"].text()
    val file = args["file"].text().trim()
    val timeout = args["timeout"].text().toIntOrNull()?.coerceIn(10, 300) ?: 90
    if (file.isEmpty() && code.isBlank()) return "code 和 file 至少给一个"
    if (PIP.containsMatchIn(code)) {
        return "这个 Python 是装在 App 里的，没有 pip，不能安装任何库。能用的只有标准库、numpy、pandas、matplotlib、openpyxl、PIL、pypdf。" +
            "要读 PDF、Word、PPT 的文字，直接用 read_file。"
    }
    val input = args["input"].text()
    if (file.isEmpty()) return pythonRun(code, timeout, "运行 Python", input)
    val target = File(wsDir, file).canonicalFile
    if (!target.path.startsWith(wsDir.canonicalPath + File.separator)) return "file 只能是文件夹里的文件"
    if (!target.isFile) return "文件夹里没有「$file」。现有的 .py：" + Workspace.files(wsDir).filter { it.extension == "py" }.joinToString("、") { it.name }.ifEmpty { "（没有）" }
    val argv = (args["args"] as? JsonArray).orEmpty().map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
    return pythonRun(runFileCode(target.relativeTo(wsDir.canonicalFile).path, argv), timeout, "运行 $file", input)
}

/** 像命令行里 python 文件名 参数… 那样运行文件：__name__ 是 __main__，sys.argv 是文件名加参数。 */
internal fun runFileCode(path: String, argv: List<String>): String {
    // JSON 的字符串写法 Python 也认（引号、反斜杠、换行都转义好了）
    fun lit(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    return "import runpy as _runpy, sys as _sys\n_sys.argv = [" + (listOf(path) + argv).joinToString(", ") { lit(it) } + "]\n" +
        "_runpy.run_path(" + lit(path) + ", run_name=\"__main__\")\nNone"
}

private suspend fun ToolSession.pythonRun(code: String, timeout: Int, label: String, input: String = "") = runTool(label) {
    val r = PyRunner.run(app, code, wsDir, timeout, input)
    r.changed.forEach { ui.attach(it) }
    val files = if (r.changed.isEmpty()) "" else "\n\n新建或改动的文件（已显示在聊天里）：" + r.changed.joinToString("、") { it.name }
    val eof = if ("EOFError" in r.output) "\n\n（程序在等输入，但没有更多输入了：要喂输入就用 input 参数，一行一个。）" else ""
    (r.output.ifBlank { "（运行完成，没有输出）" } + eof + files) to SearchRecord(label)
}

/**
 * check_page：打开文件夹里的网页看一眼（见 PageCheck）。报错、没加载上的文件、测试脚本的结果写成文字，
 * 截图放进下一条消息（模型看得了图的话）。
 */
internal suspend fun ToolSession.checkPage(args: JsonObject): String {
    val path = args["path"].text().trim().ifEmpty { "index.html" }
    val page = com.jack.ai.ui.PageCheck.resolve(wsDir, path)
        ?: return "文件夹里没有「$path」。现有的网页：" + Workspace.files(wsDir).filter { it.extension.lowercase() in setOf("html", "htm") }.joinToString("、") { it.name }.ifEmpty { "（没有）" }
    val script = args["script"].text().takeIf { it.isNotBlank() }
    val wait = args["wait_ms"].text().toLongOrNull() ?: 1500
    val frames = args["frames"].text().toIntOrNull() ?: 1
    val interval = args["interval_ms"].text().toLongOrNull() ?: 500
    return runTool("检查网页：$path") {
        val r = com.jack.ai.ui.PageCheck.run(app, wsDir, page, script, wait, frames, interval)
        val errors = r.log.count { it.startsWith("[error]") }
        val text = buildString {
            append("【$path】").append(r.info.ifEmpty { "打开了" }).append("\n")
            append(if (errors == 0 && r.failed.isEmpty()) "没有报错。\n" else "报错 $errors 条，没加载上的文件 ${r.failed.size} 个。\n")
            if (r.failed.isNotEmpty()) append("没加载上：\n").append(r.failed.joinToString("\n") { "- $it" }).append("\n")
            if (r.log.isNotEmpty()) append("控制台：\n").append(r.log.joinToString("\n")).append("\n")
            r.scriptResult?.let { append("测试脚本的结果：").append(it).append("\n") }
            when {
                r.blank -> append("截图是一片空白：页面可能什么都没画出来，也可能是 App 在后台截不到画面；以报错和测试脚本的结果为准。")
                !canSee -> append("（当前模型看不了图，没附截图；以报错和测试脚本的结果为准。）")
                r.shot != null -> { images += r.shot; append(if (frames > 1) "${frames.coerceIn(1, 4)} 张截图（每隔 ${interval} 毫秒一张，左上角是第几张）拼在一起，在下一条消息里：对比着看画面有没有动。" else "截图（手机屏幕大小）在下一条消息里。") }
            }
        }
        text to SearchRecord("检查网页：$path" + if (errors > 0) "（$errors 条报错）" else "")
    }
}

/** search_code：在文件夹的代码文件里搜，返回文件名和行号，再用 read_file 按行读。 */
internal suspend fun ToolSession.searchCode(args: JsonObject): String {
    val q = args["query"].text()
    return runTool("在代码里搜：$q") {
        withContext(Dispatchers.IO) { CodeSearch.search(wsDir, q, args["regex"].text() == "true", args["ext"].text()) } to SearchRecord("在代码里搜：$q")
    }
}

/** file_history：代码文件的旧版本（见 CodeHistory）。list 看有哪几版，diff 看某版和现在差在哪，restore 退回去。 */
internal suspend fun ToolSession.fileHistory(args: JsonObject): String {
    val name = args["name"].text().trim()
    val action = args["action"].text().ifBlank { "list" }
    val file = File(wsDir, Workspace.safeName(name))
    if (name.isEmpty() || !file.isFile) return "文件夹里没有「$name」"
    val n = args["version"].text().toIntOrNull() ?: 1
    return runTool(when (action) { "restore" -> "退回 $name 第 $n 版"; "diff" -> "对比 $name"; else -> "查看 $name 的旧版本" }) {
        val text = withContext(Dispatchers.IO) {
            when (action) {
                "diff" -> CodeHistory.versions(file).firstOrNull { it.n == n }?.let { v -> CodeHistory.diff(v.file.readText(), file.readText(), "第 $n 版", "现在") }
                    ?: "没有第 $n 版。" + CodeHistory.listing(file)
                "restore" -> CodeHistory.restore(file, n)?.let { v ->
                    "已把「${file.name}」退回第 $n 版（${v.lines} 行）。退回前的样子也存了一版，退错了还能再回来。" + verifyText(file)
                } ?: ("没有第 $n 版。" + CodeHistory.listing(file))
                else -> CodeHistory.listing(file)
            }
        }
        if (action == "restore") ui.attach(file)
        text to SearchRecord(if (action == "restore") "退回 ${file.name} 第 $n 版" else "查看 ${file.name} 的旧版本")
    }
}
