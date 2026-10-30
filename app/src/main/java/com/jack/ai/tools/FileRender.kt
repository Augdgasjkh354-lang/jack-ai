package com.jack.ai.tools

import android.content.Intent
import android.provider.AlarmClock
import com.jack.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 按工具名和参数把文件真正画出来/写出来（FileTools 用）。 */

/**
 * 按工具名和参数生成文件。先写到临时文件，成功了再替换，失败不会弄坏原文件。
 * 返回排版提醒（PPT 字太多、图标没有之类），没有就是空的。
 */
internal suspend fun ToolSession.renderFile(tool: String, original: JsonObject, file: File, cache: File): List<String> {
    // 我的模板：参数里没写的设置从模板里补
    val (args, templateColors, template) = withContext(Dispatchers.IO) { StyleTemplates.apply(StyleTemplates.load(app), tool, original) }
    val templateNote = when {
        template != null -> listOf(INFO + "用了模板「${template.name}」")
        (original["template"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().let { it.isNotBlank() && it != "none" } ->
            listOf("没有叫「${original["template"].text()}」的模板，按默认样式做的。现有模板：" + StyleTemplates.describe(StyleTemplates.load(app).filter { it.kind == StyleTemplates.kindOf(tool) }))
        else -> emptyList()
    }
    val slides = if (tool == "create_ppt") withImages(parseSlides(args), args, cache) else null
    // 照着用户的 PPT 做：从对话文件夹里那个 .pptx 读配色和字体
    val style = args["style_from"].text().trim().takeIf { tool == "create_ppt" && it.isNotEmpty() }?.let { name ->
        val f = wsFile(name)
        if (!f.name.endsWith(".pptx", true)) throw IllegalArgumentException("style_from 要是 .pptx 文件（「${f.name}」不是）。.ppt 老格式先另存为 .pptx")
        withContext(Dispatchers.IO) { runCatching { PptStyle.extract(f) }.getOrElse { throw IllegalArgumentException("读不出「${f.name}」的风格：${it.message}") } }
    }
    // Word 里的图片：对话文件夹里的图，或者按说明搜一张
    val wordImages = if (tool == "create_word") wordImages(args["markdown"].text(), cache) else null
    var warnings = emptyList<String>()
    withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".tmp-" + file.name)
        try {
            tmp.outputStream().buffered().use { out ->
                when (tool) {
                    "create_word" -> warnings = OfficeWriter.writeDocx(args["markdown"].text(), out, wordOptions(args), wordImages!!)
                    "create_ppt" -> warnings = style?.let { listOf(INFO + it.summary) }.orEmpty() +
                        OfficeWriter.writePptx(slides!!, args["theme"].text().ifBlank { "blue" }, out,
                            args["transition"].text().ifBlank { "fade" }, args["animation"].text().ifBlank { "fade" }, style?.theme ?: templateColors?.theme())
                    "create_pdf" -> PdfWriter.write(pdfMarkdown(args["markdown"].text()), out)
                    "create_excel" -> warnings = OfficeWriter.writeXlsx(parseSheets(args), out, args["theme"].text().ifBlank { "blue" })
                    "create_chart" -> renderChart(args, out)
                    "create_diagram" -> renderDiagram(args, out)
                    "create_app" -> out.write(args["html"].text().toByteArray(Charsets.UTF_8))
                    else -> throw IllegalArgumentException("不认识的文件类型")
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("保存文件失败")
            }
        } finally {
            tmp.delete()
        }
    }
    return templateNote + warnings
}

/** 排版说明里不是问题、只是告诉模型一声的条目（比如照着哪个 PPT 取了什么颜色）。 */
internal const val INFO = "ℹ "

/** 排版提醒写给模型看：哪页字太多、哪些图标没有。 */
internal fun layoutNote(warnings: List<String>): String {
    val (info, problems) = warnings.partition { it.startsWith(INFO) }
    return info.joinToString("") { "\n" + it.removePrefix(INFO) } +
        (if (problems.isEmpty()) "" else "\n【排版提醒】\n" + problems.take(8).joinToString("\n") { "- $it" } +
            "\n能改就用 edit_file 改底稿（精简文字、拆成两页、换个版式），改完会重新生成。")
}

internal fun ToolSession.renderChart(args: JsonObject, out: java.io.OutputStream) {
    val labels = (args["labels"] as? JsonArray).orEmpty().map { it.text() }
    val series = (args["series"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val values = (o["values"] as? JsonArray).orEmpty().map { it.text().toDoubleOrNull() ?: 0.0 }
        ChartWriter.Series(o["name"].text(), values)
    }
    ChartWriter.render(args["type"].text(), args["title"].text().ifBlank { "图表" }, args["unit"].text(), labels, series, out)
}

internal fun ToolSession.renderDiagram(args: JsonObject, out: java.io.OutputStream) {
    val title = args["title"].text().trim()
    if (args["type"].text() == "flowchart") {
        val nodes = (args["nodes"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"].text().trim()
            if (id.isEmpty()) null else Diagram.FNode(id, o["label"].text().ifBlank { id }, o["shape"].text().ifBlank { "box" })
        }
        val edges = (args["edges"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val from = o["from"].text().trim()
            val to = o["to"].text().trim()
            if (from.isEmpty() || to.isEmpty()) null else Diagram.FEdge(from, to, o["label"].text())
        }
        if (nodes.isEmpty() && edges.isEmpty()) throw IllegalArgumentException("流程图没有节点")
        val layout = Diagram.flow(nodes, edges, args["direction"].text() == "LR", DiagramWriter.measure)
        DiagramWriter.render(layout, title, mindmap = false, out = out)
    } else {
        val outline = args["outline"].text()
        if (outline.isBlank()) throw IllegalArgumentException("思维导图需要 outline")
        val root = Diagram.parseOutline(outline, title)
        val layout = Diagram.tree(root, DiagramWriter.measure)
        DiagramWriter.render(layout, if (root.label == title) "" else title, mindmap = true, out = out)
    }
}

internal fun ToolSession.parseSlides(args: JsonObject): List<OfficeWriter.Slide> =
    (args["slides"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val bullets = when (val b = o["bullets"]) {
            is JsonArray -> b.map { it.text() }.filter { it.isNotBlank() }
            null, JsonNull -> emptyList()
            else -> b.text().lines().filter { it.isNotBlank() }
        }
        val icons = when (val ic = o["icons"]) {
            is JsonArray -> ic.map { it.text() }
            null, JsonNull -> listOfNotNull(o["icon"].text().takeIf { it.isNotBlank() })
            else -> ic.text().split(',', '，').map { it.trim() }
        }
        OfficeWriter.Slide(o["layout"].text().ifBlank { "content" }, o["title"].text(), bullets,
            icons = icons, transition = o["transition"].text(), notes = o["notes"].text(), chart = parseChart(o["chart"] as? JsonObject))
    }.ifEmpty { throw IllegalArgumentException("没有幻灯片内容") }

/**
 * Word 里每张 ![说明](来源) 的图：来源是对话文件夹里的图片文件就用它（比如 create_chart 画的图、用户发的照片），
 * 否则把来源当搜索词（可以写成 search:英文关键词）搜一张。最多 8 张，找不到的在排版提醒里说。
 */
internal suspend fun ToolSession.wordImages(markdown: String, cache: File): Map<String, WordDoc.Image> {
    val srcs = markdown.lines().mapNotNull { WordDoc.IMAGE_LINE.find(it.trim())?.groupValues?.get(2)?.trim() }.distinct().take(8)
    if (srcs.isEmpty()) return emptyMap()
    val key = settings.tavilyKeys.firstOrNull { !com.jack.ai.api.KeyRest.resting(it) }
    val out = mutableMapOf<String, WordDoc.Image>()
    for (src in srcs) {
        val local = withContext(Dispatchers.IO) { Workspace.find(wsDir, src)?.takeIf { Workspace.ext(it) in Workspace.IMAGE_EXT } }
        val img = if (local != null) withContext(Dispatchers.IO) {
            val bytes = local.readBytes()
            // png 原样放（图表的线条、透明底不受损），别的统一转成 jpeg、缩到 1600 宽
            if (Workspace.ext(local) == "png") {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth > 0) WordDoc.Image(bytes, "png", o.outWidth, o.outHeight) else null
            } else Attachments.toJpeg(bytes, 1600)?.let { WordDoc.Image(it.first, "jpeg", it.second, it.third) }
        } else {
            val q = src.removePrefix("search:").trim()
            if (key == null || q.isEmpty() || q.contains('/')) null
            else runCatching { cachedImage(cache, q) ?: fetchImage(key, q)?.also { saveImage(cache, q, it) } }.getOrNull()
                ?.let { WordDoc.Image(it.first, "jpeg", it.second, it.third) }
        }
        if (img != null) out[src] = img
    }
    return out
}

/** PDF 画不了原生图表和网上的图：图表变成一张数据表，图片只留说明。 */
internal fun pdfMarkdown(md: String): String {
    val out = StringBuilder()
    val lines = md.lines()
    var i = 0
    while (i < lines.size) {
        val s = lines[i].trim()
        when {
            s.startsWith("```chart") -> {
                val json = StringBuilder(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { json.append(lines[i]).append('\n'); i++ }
                val c = runCatching { PptChart.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(json.toString()) as JsonObject) }.getOrNull()
                if (c != null && c.categories.isNotEmpty()) {
                    if (c.title.isNotBlank()) out.append("**").append(c.title).append(if (c.unit.isNotBlank()) "（单位：${c.unit}）" else "").append("**\n\n")
                    out.append("| | ").append(c.series.joinToString(" | ") { it.name }).append(" |\n|---|").append(c.series.joinToString("") { "---|" }).append("\n")
                    c.categories.forEachIndexed { k, cat ->
                        out.append("| $cat | ").append(c.series.joinToString(" | ") { s -> s.values.getOrNull(k)?.let { v -> if (v == Math.floor(v)) v.toLong().toString() else v.toString() }.orEmpty() }).append(" |\n")
                    }
                    out.append("\n")
                }
            }
            WordDoc.IMAGE_LINE.matches(s) -> WordDoc.IMAGE_LINE.find(s)!!.groupValues[1].takeIf { it.isNotBlank() }?.let { out.append("（图：$it）\n") }
            else -> out.append(lines[i]).append('\n')
        }
        i++
    }
    return out.toString()
}

/** Word 的排版选项：封面、目录、页眉、页码、标题编号、首行缩进、字体风格、配色。没写的按默认。 */
internal fun wordOptions(args: JsonObject): WordDoc.Options {
    val c = args["cover"]
    val cover = when (c) {
        is JsonObject -> WordDoc.Cover(c["title"].text(), c["subtitle"].text(), c["author"].text(), c["organization"].text(), c["date"].text())
        is kotlinx.serialization.json.JsonPrimitive -> if (c.text() == "true") WordDoc.Cover() else null
        else -> null
    }
    fun bool(key: String): Boolean? = when (args[key].text()) { "true" -> true; "false" -> false; else -> null }
    return WordDoc.Options(
        theme = args["theme"].text().ifBlank { "blue" },
        cover = cover,
        toc = bool("toc"),
        header = (args["header"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        pageNumbers = bool("page_numbers") ?: true,
        headingNumbers = args["heading_numbers"].text().ifBlank { "none" },
        indent = bool("indent") ?: false,
        fontStyle = args["font_style"].text().ifBlank { "modern" },
    )
}

internal fun parseChart(o: JsonObject?): PptChart.Chart? = PptChart.fromJson(o)

/** 给写了 image_query 的正文页搜一张图，下载失败就不配图。最多 6 张。 */
internal suspend fun ToolSession.withImages(slides: List<OfficeWriter.Slide>, args: JsonObject, cache: File): List<OfficeWriter.Slide> {
    val key = settings.tavilyKeys.firstOrNull { !com.jack.ai.api.KeyRest.resting(it) } ?: return slides
    val queries = (args["slides"] as? JsonArray).orEmpty().map { (it as? JsonObject)?.get("image_query").text() }
    var count = 0
    return slides.mapIndexed { i, s ->
        val q = queries.getOrNull(i).orEmpty()
        if (q.isBlank() || s.layout !in setOf("content", "image") || count >= 6) return@mapIndexed s
        val img = runCatching { cachedImage(cache, q) ?: fetchImage(key, q)?.also { saveImage(cache, q, it) } }.getOrNull()
            ?: return@mapIndexed s
        count++
        s.copy(image = img.first, imageExt = "jpeg", imageW = img.second, imageH = img.third)
    }
}

internal suspend fun ToolSession.fetchImage(key: String, query: String): Triple<ByteArray, Int, Int>? {
    for (url in host.tavily.searchImages(key, query).take(4)) {
        val bytes = host.tavily.download(url) ?: continue
        val img = withContext(Dispatchers.Default) { Attachments.toJpeg(bytes, 1600) } ?: continue
        if (img.second >= 300 && img.third >= 200) return img
    }
    return null
}

internal fun ToolSession.parseSheets(args: JsonObject): List<OfficeWriter.Sheet> =
    (args["sheets"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
        val o = el as? JsonObject ?: return@mapIndexedNotNull null
        val rows = (o["rows"] as? JsonArray).orEmpty().map { r ->
            (r as? JsonArray)?.map { it.text() } ?: listOf(r.text())
        }
        val highlights = (o["highlights"] as? JsonArray).orEmpty().mapNotNull { h ->
            val x = h as? JsonObject ?: return@mapNotNull null
            XlsxBook.Highlight(x["column"].text(), x["op"].text(), x["value"].text(), x["color"].text().ifBlank { "red" })
        }
        val charts = (o["charts"] as? JsonArray).orEmpty().mapNotNull { c ->
            val x = c as? JsonObject ?: return@mapNotNull null
            XlsxBook.SheetChart(x["type"].text().ifBlank { "column" }, x["title"].text(), x["category_column"].text(),
                (x["value_columns"] as? JsonArray).orEmpty().map { it.text() }, x["unit"].text())
        }
        OfficeWriter.Sheet(o["name"].text().ifBlank { "Sheet${i + 1}" }, rows, o["total_row"].text() == "true", highlights, charts)
    }.ifEmpty { throw IllegalArgumentException("没有表格内容") }

/** PPT 配图存一份，修改 PPT 重新生成时不用再搜一遍。 */
internal fun ToolSession.imageCacheFile(cache: File, query: String) =
    File(cache, "img-" + query.trim().lowercase().hashCode().toUInt() + ".jpg")

internal suspend fun ToolSession.cachedImage(cache: File, query: String): Triple<ByteArray, Int, Int>? = withContext(Dispatchers.IO) {
    val f = imageCacheFile(cache, query)
    if (!f.exists()) return@withContext null
    val bytes = f.readBytes()
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0) null else Triple(bytes, o.outWidth, o.outHeight)
}

internal suspend fun ToolSession.saveImage(cache: File, query: String, img: Triple<ByteArray, Int, Int>) = withContext(Dispatchers.IO) {
    runCatching { cache.mkdirs(); imageCacheFile(cache, query).writeBytes(img.first) }
}
