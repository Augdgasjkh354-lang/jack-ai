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

internal suspend fun ToolSession.runPython(code: String): String {
    if (PIP.containsMatchIn(code)) {
        return "这个 Python 是装在 App 里的，没有 pip，不能安装任何库。能用的只有标准库、numpy、pandas、matplotlib、openpyxl、PIL。" +
            "要读 PDF、Word、PPT 的文字，直接用 read_file。"
    }
    return pythonRun(code)
}

private suspend fun ToolSession.pythonRun(code: String) = runTool("运行 Python") {
    val r = PyRunner.run(app, code, wsDir)
    r.changed.forEach { ui.attach(it) }
    val files = if (r.changed.isEmpty()) "" else "\n\n新建或改动的文件（已显示在聊天里）：" + r.changed.joinToString("、") { it.name }
    (r.output.ifBlank { "（运行完成，没有输出）" } + files) to SearchRecord("运行 Python")
}
