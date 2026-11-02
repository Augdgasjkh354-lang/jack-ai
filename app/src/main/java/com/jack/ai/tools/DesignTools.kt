package com.jack.ai.tools

import com.jack.ai.data.DesignTemplates
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Workspace
import com.jack.ai.ui.DesignRender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * design：做单张设计图（海报、小红书配图、邀请函、名片、公众号封面、长图）。
 * - templates：列出模板（可按分类筛）；
 * - make：套模板（填文字、列表、图片、换配色），或者用 html 从头设计；出 PNG（可再出 PDF），旁边存一份底稿 .html；
 * - render：用 edit_file 改过底稿以后重新出图（同名覆盖，聊天里的图跟着变）。
 * 每次都检查文字有没有装不下，能看图的模型还会收到一张小图自己看一眼。
 */
internal suspend fun ToolSession.design(args: JsonObject): String = when (args["action"].text().ifBlank { "make" }) {
    "templates" -> runTool("看设计模板") {
        val list = withContext(Dispatchers.IO) { DesignTemplates.list(app) }
        val cat = args["category"].text().trim().ifBlank { null }
        (DesignTemplates.catalog(list, cat) + "\n\n配色变量：" + DesignTemplates.VARS_NOTE) to SearchRecord("看设计模板" + (cat?.let { "：${DesignTemplates.CATEGORIES[it] ?: it}" } ?: ""))
    }
    "render" -> renderDesign(args)
    "make" -> makeDesign(args)
    else -> "action 只能是 templates、make、render"
}

private suspend fun ToolSession.makeDesign(args: JsonObject): String {
    val templateId = args["template"].text().trim()
    val list = withContext(Dispatchers.IO) { DesignTemplates.list(app) }
    val tpl = if (templateId.isEmpty()) null else list.firstOrNull { it.id == templateId }
        ?: return "没有「$templateId」这个模板。先用 action=templates 看有哪些；或者不填 template、用 html 从头设计。"
    val html = if (tpl != null) withContext(Dispatchers.IO) { DesignTemplates.html(app, tpl.id) } ?: return "模板「$templateId」的文件读不出来"
        else args["html"].text().takeIf { it.isNotBlank() } ?: return "要么填 template（模板 id），要么写 html 从头设计"
    val (w, h) = if (tpl != null) tpl.w to tpl.h
        else DesignTemplates.size(args["size"].text().ifBlank { "xhs" }) ?: return "size 认不出：用 ${DesignTemplates.SIZES.keys.joinToString("、")}，或写「宽x高」像素，比如 1080x1350"
    val base = safeFileName(args["filename"].text()).ifBlank { tpl?.name?.substringBefore('·') ?: "设计" }
    return draw(base, html, w, h, args, fresh = true, label = "做设计：$base" + (tpl?.let { "（${it.name}）" } ?: ""))
}

private suspend fun ToolSession.renderDesign(args: JsonObject): String {
    val path = args["path"].text().trim()
    if (path.isEmpty()) return "要写 path：底稿 .html 的文件名"
    val file = withContext(Dispatchers.IO) { Workspace.find(wsDir, path) }
        ?: return "对话文件夹里没有「$path」"
    if (file.extension.lowercase() !in setOf("html", "htm")) return "render 要的是底稿 .html，不是「${file.name}」"
    val html = withContext(Dispatchers.IO) { file.readText() }
    val (w, h) = DesignTemplates.savedSize(html) ?: DesignTemplates.size(args["size"].text()) ?: (1080 to 1440)
    return draw(file.nameWithoutExtension, html, w, h, args, fresh = false, label = "重新出图：${file.nameWithoutExtension}")
}

private suspend fun ToolSession.draw(base: String, html: String, w: Int, h: Int, args: JsonObject, fresh: Boolean, label: String): String {
    // 图片位：填的是对话文件夹里的文件名，换成页面里的地址；找不到的先说清楚
    val pics = mutableMapOf<String, String>()
    val slots = (args["slots"] as? JsonObject)?.let { all ->
        JsonObject(all.filter { (k, v) ->
            val name = (v as? JsonPrimitive)?.content.orEmpty()
            if (looksLikeImage(name)) {
                val f = withContext(Dispatchers.IO) { Workspace.find(wsDir, name) } ?: return "对话文件夹里没有图片「$name」（填在 $k）"
                pics[k] = DesignRender.fileUrl(f.canonicalFile.relativeTo(wsDir.canonicalFile).path); false
            } else true
        })
    }
    val lists = (args["lists"] as? JsonObject)
    val vars = DesignTemplates.colors(args["colors"] as? JsonObject)
    val pdfWanted = args["format"].text().lowercase() == "pdf"
    return runTool(label) {
        val png = withContext(Dispatchers.IO) { if (fresh) Workspace.uniqueFile(wsDir, "$base.png") else File(wsDir, "$base.png") }
        val src = File(png.parentFile, png.nameWithoutExtension + ".html")
        val pdf = if (pdfWanted) File(png.parentFile, png.nameWithoutExtension + ".pdf") else null
        val data = DesignTemplates.data(vars, slots, lists, pics, DesignTemplates.icons(html))
        val r = DesignRender.render(app, wsDir, html, w, h, data, png, pdf)
        if (r.source.isNotBlank()) withContext(Dispatchers.IO) { src.writeText(r.source) }
        ui.attach(png)
        pdf?.takeIf { it.exists() }?.let { ui.attach(it) }
        val text = buildString {
            append("图已生成并显示在聊天里：「${png.name}」（${r.width}×${r.height}）")
            if (pdf != null) append("，PDF「${pdf.name}」")
            append("。底稿是「${src.name}」：要改就用 edit_file 改它（文字、颜色、位置、大小都在里面），再用 design action=render path=${src.name} 重新出图，不要从头再做。\n")
            if (r.unknown.isNotEmpty()) append("这些位置模板里没有，没填进去：${r.unknown.joinToString("、")}。对照模板目录里的名字再填。\n")
            if (r.problems.isEmpty()) append("检查：文字都装得下，没有跑出画布。\n")
            else append("检查发现：\n").append(r.problems.joinToString("\n") { "- $it" }).append("\n先改掉这些（缩短文字、拆成两行、调小字号或换个模板），再交给用户。\n")
            when {
                r.blank -> append("截出来是一片空白：可能是 App 在后台没出画面，请用户回到 App 再让你重新出图。")
                canSee && r.preview != null -> { images += r.preview; append("出的图（缩小了）在下一条消息里：看一眼排版、对齐、留白、颜色和字号层次，有毛病就改了再出。") }
            }
        }
        text to SearchRecord("$label：${png.name}" + if (r.problems.isNotEmpty()) "（${r.problems.size} 处要改）" else "")
    }
}

private fun looksLikeImage(v: String) = Regex("""\.(png|jpe?g|webp|gif|bmp|heic)$""", RegexOption.IGNORE_CASE).containsMatchIn(v.trim()) && '\n' !in v
