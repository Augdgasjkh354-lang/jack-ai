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
        val shown = if (ext == "png") "图已生成并显示在聊天里" else "已生成文件「${file.name}」，用户可以在聊天里打开或分享"
        "$shown。文件保存在对话文件夹里，以后要修改就用 edit_file 改它的底稿，不要重新生成。" to
            SearchRecord("生成 $label：${file.name}")
    }

internal suspend fun ToolSession.makeChart(args: JsonObject) =
    create("create_chart", args, safeFileName(args["title"].text()).ifBlank { "图表" }, "png", "图表")

internal suspend fun ToolSession.makeDiagram(args: JsonObject): String {
    val kind = if (args["type"].text() == "flowchart") "流程图" else "思维导图"
    return create("create_diagram", args, safeFileName(args["title"].text()).ifBlank { kind }, "png", kind)
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

internal suspend fun ToolSession.readFile(args: JsonObject): String {
    val name = args["name"].text()
    return runTool("查看 $name") {
        val file = wsFile(name)
        val text = withContext(Dispatchers.IO) { Workspace.readText(file) }
        val lines = text.lines()
        val start = (args["start_line"].text().toIntOrNull() ?: 1).coerceIn(1, lines.size.coerceAtLeast(1))
        val max = (args["max_lines"].text().toIntOrNull() ?: 400).coerceIn(1, 2000)
        val part = lines.drop(start - 1).take(max)
        var body = part.joinToString("\n")
        if (body.length > 40_000) body = body.take(40_000) + "\n…（太长，后面截断了，用 start_line 接着读）"
        val end = start + part.size - 1
        val head = "「${file.name}」共 ${lines.size} 行" +
            (if (Workspace.hasSource(file)) "，下面是你生成它时的底稿，edit_file 改的就是这份底稿" else "") +
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
        if (!approve("删除 ${files.size} 个文件？", "删了找不回来。\n\n$list", "删除")) {
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
        }
        ui.attach(file)
        "已写入「${file.name}」（${file.length()} 字节）。" to SearchRecord("写入 ${file.name}")
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
        val n = if (src != null) {
            val (tool, a) = src
            val (edited, count) = TextEdit.replace(Workspace.sourceText(tool, a), old, new, all)
            val newArgs = Workspace.argsFromText(tool, a, edited)
            renderFile(tool, newArgs, file, wsCache)
            withContext(Dispatchers.IO) { Workspace.saveSource(file, tool, newArgs) }
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
        "已修改「${file.name}」，替换了 $n 处，其他内容没动。" to SearchRecord("修改 ${file.name}")
    }
}
