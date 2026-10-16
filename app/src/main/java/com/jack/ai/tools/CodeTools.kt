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

internal suspend fun ToolSession.runPython(code: String) = runTool("运行 Python") {
    val r = PyRunner.run(app, code, wsDir)
    r.changed.forEach { ui.attach(it) }
    val files = if (r.changed.isEmpty()) "" else "\n\n新建或改动的文件（已显示在聊天里）：" + r.changed.joinToString("、") { it.name }
    (r.output.ifBlank { "（运行完成，没有输出）" } + files) to SearchRecord("运行 Python")
}
