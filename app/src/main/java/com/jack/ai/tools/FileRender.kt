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
internal suspend fun ToolSession.renderFile(tool: String, args: JsonObject, file: File, cache: File): List<String> {
    val slides = if (tool == "create_ppt") withImages(parseSlides(args), args, cache) else null
    var warnings = emptyList<String>()
    withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".tmp-" + file.name)
        try {
            tmp.outputStream().buffered().use { out ->
                when (tool) {
                    "create_word" -> OfficeWriter.writeDocx(args["markdown"].text(), out)
                    "create_ppt" -> warnings = OfficeWriter.writePptx(slides!!, args["theme"].text().ifBlank { "blue" }, out,
                        args["transition"].text().ifBlank { "fade" }, args["animation"].text().ifBlank { "fade" })
                    "create_pdf" -> PdfWriter.write(args["markdown"].text(), out)
                    "create_excel" -> OfficeWriter.writeXlsx(parseSheets(args), out)
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
    return warnings
}

/** 排版提醒写给模型看：哪页字太多、哪些图标没有。 */
internal fun layoutNote(warnings: List<String>): String =
    if (warnings.isEmpty()) "" else "\n【排版提醒】\n" + warnings.take(8).joinToString("\n") { "- $it" } +
        "\n能改就用 edit_file 改底稿（精简文字、拆成两页、换个版式），改完会重新生成。"

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

/** chart 版式的数据：type、categories、series[{name, values}]、unit。数字写成字符串也认（去掉千分位逗号）。 */
internal fun parseChart(o: JsonObject?): PptChart.Chart? {
    o ?: return null
    val cats = (o["categories"] as? JsonArray).orEmpty().map { it.text() }
    val series = (o["series"] as? JsonArray).orEmpty().mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        val values = (s["values"] as? JsonArray).orEmpty().map { it.text().replace(",", "").replace("，", "").trim().toDoubleOrNull() ?: 0.0 }
        PptChart.Series(s["name"].text(), values)
    }
    return PptChart.Chart(o["type"].text().ifBlank { "column" }, cats, series, o["unit"].text())
}

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
        OfficeWriter.Sheet(o["name"].text().ifBlank { "Sheet${i + 1}" }, rows)
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
