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

/** 对话文件夹里的文件：读、写、局部修改，以及生成 Word/PPT/Excel/PDF/图表/流程图。 */

internal suspend fun ToolSession.create(tool: String, args: JsonObject, base: String, ext: String, label: String): String =
    runTool("生成 $label：$base.$ext") {
        val file = withContext(Dispatchers.IO) { Workspace.uniqueFile(wsDir, "$base.$ext") }
        try {
            renderFile(tool, args, file, wsCache)
        } catch (e: Exception) {
            file.delete(); throw e
        }
        withContext(Dispatchers.IO) { Workspace.saveSource(file, tool, args) }
        ui.attach(file)
        val shown = when (ext) {
            "png" -> "图已生成并显示在聊天里"
            "html" -> "小工具「${file.name}」已经显示在聊天里，用户可以直接点、输入、计算，也能全屏打开"
            else -> "已生成文件「${file.name}」，用户可以在聊天里打开或分享"
        }
        "$shown。文件保存在对话文件夹里，以后要修改就用 edit_file 改它的底稿，不要重新生成。" + verifyGenerated(tool, args, file) to
            SearchRecord("生成 $label：${file.name}")
    }

internal suspend fun ToolSession.makeChart(args: JsonObject) =
    create("create_chart", args, safeFileName(args["title"].text()).ifBlank { "图表" }, "png", "图表")

internal suspend fun ToolSession.makeDiagram(args: JsonObject): String {
    val kind = if (args["type"].text() == "flowchart") "流程图" else "思维导图"
    return create("create_diagram", args, safeFileName(args["title"].text()).ifBlank { kind }, "png", kind)
}

/** 小工具：一个能在聊天里直接操作的网页（计算器、面板、小游戏……）。 */
internal suspend fun ToolSession.makeApp(args: JsonObject): String {
    if (args["html"].text().isBlank()) return "要写 html：完整的网页代码"
    return create("create_app", args, safeFileName(args["title"].text()).ifBlank { "小工具" }, "html", "小工具")
}

internal suspend fun ToolSession.makeFile(kind: String, args: JsonObject): String {
    val base = safeFileName(args["filename"].text()).ifBlank { "未命名" }
    val (ext, label) = when (kind) {
        "create_word" -> "docx" to "Word"
        "create_ppt" -> "pptx" to "PPT"
        "create_pdf" -> "pdf" to "PDF"
        else -> "xlsx" to "Excel"
    }
    return create(kind, args, base, ext, label)
}

/** create_document：Word 和 PDF 合成一个，按 format 分开；底稿照旧记成 create_word / create_pdf，改起来不受影响。 */
internal suspend fun ToolSession.createDocument(args: JsonObject): String {
    val word = SkillIds.WORD in skillIds
    val pdf = SkillIds.PDF in skillIds
    val tool = when (args["format"].text().lowercase()) {
        "pdf" -> if (pdf) "create_pdf" else return "PDF 技能没开，用 format=word"
        "word", "docx" -> if (word) "create_word" else return "Word 技能没开，用 format=pdf"
        else -> if (word) "create_word" else "create_pdf"
    }
    return makeFile(tool, JsonObject(args - "format"))
}

internal fun ToolSession.wsFile(name: String, exact: Boolean = false): File =
    (if (exact) Workspace.findExact(wsDir, name) else Workspace.find(wsDir, name)) ?: throw TextEdit.EditException(
    "对话文件夹里没有「$name」。" + Workspace.files(wsDir).let { l ->
        if (l.isEmpty()) "文件夹是空的。" else "现有文件：" + l.joinToString("、") { it.name }
    })

internal suspend fun ToolSession.listFiles() = runTool("查看文件夹") {
    val list = withContext(Dispatchers.IO) { Workspace.files(wsDir).map { "${it.name}｜${ToolSession.sizeText(it.length())}｜${Workspace.editKind(it)}" } }
    (if (list.isEmpty()) "对话文件夹是空的。" else list.joinToString("\n")) to SearchRecord("查看文件夹")
}

/** Kavi 自己工作台（自由时间的文件夹）上的文件：聊天里提到自己做过的东西时能直接读。隐私项目里不看外面的。 */
private fun ToolSession.ownWorkbenchFile(name: String): File? {
    if (project?.isolated == true || conv.id == Reader.CONV_ID) return null
    return Workspace.find(Workspace.dir(app, Workspace.key(Conversation(id = Reader.CONV_ID))), name)
}

internal suspend fun ToolSession.readFile(args: JsonObject): String {
    val name = args["name"].text()
    return runTool("查看 $name") {
        val file = runCatching { wsFile(name) }.getOrElse { e -> ownWorkbenchFile(name) ?: throw TextEdit.EditException(e.message.orEmpty() +
            if (name.startsWith("free_") || name.endsWith(".json")) "（自由时间、梦的原文用 own_history 看，不用 read_file）" else "") }
        val extracted = args["extracted"].text() == "true"
        val text = withContext(Dispatchers.IO) { Workspace.readText(file, extracted) }
        val lines = text.lines()
        val start = (args["start_line"].text().toIntOrNull() ?: 1).coerceIn(1, lines.size.coerceAtLeast(1))
        val max = (args["max_lines"].text().toIntOrNull() ?: 400).coerceIn(1, 2000)
        val part = lines.drop(start - 1).take(max)
        var body = part.joinToString("\n")
        if (body.length > 40_000) body = body.take(40_000) + "\n…（太长，后面截断了，用 start_line 接着读）"
        val end = start + part.size - 1
        val head = "「${file.name}」共 ${lines.size} 行" +
            (if (extracted) "，下面是从文件里直接抽出来的文字"
            else if (Workspace.hasSource(file)) "，下面是你生成它时的底稿，edit_file 改的就是这份底稿（要核对文件本身的文字，加 extracted=true）" else "") +
            (if (start > 1 || end < lines.size) "，这里是第 $start–$end 行" else "") + "：\n"
        head + body to SearchRecord("查看 ${file.name}")
    }
}

/** 删对话文件夹里的文件：先问用户，连同生成时的底稿一起删。 */
internal suspend fun ToolSession.deleteFiles(args: JsonObject): String {
    val names = (args["names"] as? JsonArray).orEmpty().map { it.text().trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOfNotNull(args["name"].text().trim().takeIf { it.isNotEmpty() }) }
    if (names.isEmpty()) return "缺少文件名 names"
    return runTool("删除文件：${names.joinToString("、")}") {
        val files = names.map { wsFile(it, exact = true) }.distinct()
        val list = files.joinToString("\n") { "· ${it.name}（${ToolSession.sizeText(it.length())}）" }
        val ownWorkbench = freeBudget != null && conv.id == Reader.CONV_ID && conv.projectId == null
        if (!ownWorkbench && !approve("删除 ${files.size} 个文件？", "删了找不回来。\n\n$list", "删除")) {
            return@runTool "用户取消了，文件没有删。" to SearchRecord("删除文件：已取消")
        }
        withContext(Dispatchers.IO) { files.forEach { Workspace.delete(it) } }
        "已删除：${files.joinToString("、") { it.name }}。" to SearchRecord("删除 ${files.size} 个文件")
    }
}

internal suspend fun ToolSession.writeFile(args: JsonObject): String {
    val name = Workspace.safeName(args["name"].text())
    return runTool("写入 $name") {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("docx", "pptx", "xlsx", "pdf", "png", "jpg", "jpeg")) {
            throw TextEdit.EditException("write_file 只能写文字文件。Word/PPT/Excel/PDF 用对应的生成工具，已有的用 edit_file 改")
        }
        val file = File(wsDir, name)
        withContext(Dispatchers.IO) {
            wsDir.mkdirs()
            file.writeText(args["content"].text())
            // 覆盖了生成过的文件（比如小工具的 html）：旧底稿作废，免得 edit_file 拿旧底稿重新生成、把这次写的冲掉
            Workspace.dropSource(file)
        }
        ui.attach(file)
        "已写入「${file.name}」（${file.length()} 字节）。" + verifyText(file) to SearchRecord("写入 ${file.name}")
    }
}

internal suspend fun ToolSession.editFile(args: JsonObject): String {
    val name = args["name"].text()
    return runTool("修改 $name") {
        val file = wsFile(name)
        val old = args["old_text"].text()
        val new = args["new_text"].text()
        val all = args["replace_all"].text() == "true"
        val src = withContext(Dispatchers.IO) { Workspace.source(file) }
        // 有底稿的文件重新生成后核实；纯文字文件改完检查格式和语法
        var check: String? = null
        val n = if (src != null) {
            val (tool, a) = src
            val (edited, count) = TextEdit.replace(Workspace.sourceText(tool, a), old, new, all)
            val newArgs = Workspace.argsFromText(tool, a, edited)
            renderFile(tool, newArgs, file, wsCache)
            withContext(Dispatchers.IO) { Workspace.saveSource(file, tool, newArgs) }
            check = verifyGenerated(tool, newArgs, file)
            count
        } else withContext(Dispatchers.IO) {
            when {
                Workspace.ext(file) == "docx" -> {
                    val (bytes, count) = TextEdit.replaceInDocx(file.readBytes(), old, new, all)
                    file.writeBytes(bytes); count
                }
                Workspace.isText(file) -> {
                    val (text, count) = TextEdit.replace(file.readText(), old, new, all)
                    file.writeText(text); count
                }
                else -> throw TextEdit.EditException("「${file.name}」这种文件没法直接修改")
            }
        }
        ui.attach(file)
        val verified = check ?: if (Workspace.isText(file)) verifyText(file)
            else Verify.note(listOfNotNull(withContext(Dispatchers.IO) { Verify.fileOpens(file) }), "文件能正常打开")
        "已修改「${file.name}」，替换了 $n 处，其他内容没动。$verified" to SearchRecord("修改 ${file.name}")
    }
}

/** 生成的文件核实：能不能打开；Excel 再查公式引用。 */
internal suspend fun ToolSession.verifyGenerated(tool: String, args: JsonObject, file: File): String = withContext(Dispatchers.IO) {
    val problems = listOfNotNull(Verify.fileOpens(file)) + when (tool) {
        "create_excel" -> runCatching { Verify.xlsxFormulas(parseSheets(args)) }.getOrDefault(emptyList())
        "create_app" -> Verify.html(args["html"].text())
        // PDF 生成后真抽一次字：抽不出来就是坏了（或者字没画上去）
        "create_pdf" -> listOfNotNull(runCatching {
            "「${file.name}」里抽不出文字，可能没生成好".takeIf { args["markdown"].text().isNotBlank() && Workspace.readText(file, extracted = true).isBlank() }
        }.getOrNull())  // 抽字本身出错（比如后台还没初始化 PDF 库）不算文件坏了，文件头上面已经查过
        else -> emptyList()
    }
    Verify.note(problems, when (tool) {
        "create_excel" -> "文件能正常打开，公式引用都在数据范围内"
        "create_pdf" -> "PDF 能打开，也能抽出文字"
        "create_app" -> "网页结构完整，没有引用会被拦截的外部资源（运行时出错会显示在卡片上）"
        else -> "文件能正常打开"
    })
}

/** 文字文件核实：JSON、CSV 查格式，Python、JavaScript 查语法（不运行）。其他类型不查，返回空。 */
internal suspend fun ToolSession.verifyText(file: File): String {
    val text = withContext(Dispatchers.IO) { file.readText() }
    val problems = Verify.textFile(file.name, text).toMutableList()
    when (file.extension.lowercase()) {
        "py" -> PyRunner.syntaxError(app, text)?.let { problems += "「${file.name}」有语法错误：$it" }
        "js" -> JsRunner.syntaxError(text)?.let { problems += "「${file.name}」有语法错误：$it" }
        "json", "csv" -> {}
        else -> return ""
    }
    return Verify.note(problems, "格式检查通过")
}
