package com.jack.ai.tools

import com.jack.ai.data.AiTone
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 去 AI 味检测：读稿子（文件或直接给的字），出报告；带原稿时出前后对比。只检测，不改文件。 */
internal suspend fun ToolSession.checkAiTone(args: JsonObject): String {
    suspend fun load(file: String, text: String): String? = when {
        file.isNotBlank() -> withContext(Dispatchers.IO) { Workspace.readText(wsFile(file), extracted = true) }
        text.isNotBlank() -> text
        else -> null
    }
    return runTool("检查 AI 味") {
        val now = load(args["file"].text().trim(), args["text"].text())
            ?: return@runTool "要填 file（对话文件夹里的文件名）或 text（稿子的文字）。" to SearchRecord("检查 AI 味")
        if (now.count { !it.isWhitespace() } < 50) return@runTool "稿子太短（不到 50 字），看不出什么。" to SearchRecord("检查 AI 味")
        val before = load(args["compare_file"].text().trim(), args["compare_text"].text())?.let(AiTone::check)
        AiTone.format(AiTone.check(now), before) to SearchRecord("检查 AI 味")
    }
}

/** check_rewrite：改写稿对着原文核对数字和人称（见 RewriteCheck）。 */
internal suspend fun ToolSession.checkRewrite(args: JsonObject): String {
    suspend fun load(file: String, text: String): String? = when {
        file.isNotBlank() -> withContext(Dispatchers.IO) { Workspace.readText(wsFile(file), extracted = true) }
        text.isNotBlank() -> text
        else -> null
    }
    return runTool("核对改写稿") {
        val draft = load(args["file"].text().trim(), args["text"].text())
            ?: return@runTool "要填 file 或 text（稿子）。" to SearchRecord("核对改写稿")
        val source = load(args["source_file"].text().trim(), args["source_text"].text())
            ?: return@runTool "要填 source_file 或 source_text（原文）。网页先用 browser extract what=article save_as=原文 存下来。" to SearchRecord("核对改写稿")
        val r = com.jack.ai.data.RewriteCheck.check(draft, source, self = args["self"].text() == "true")
        com.jack.ai.data.RewriteCheck.format(r) to SearchRecord("核对改写稿" + if (r.missing.isNotEmpty() || r.firstPerson.isNotEmpty()) "（${r.missing.size + r.firstPerson.size} 处）" else "")
    }
}
