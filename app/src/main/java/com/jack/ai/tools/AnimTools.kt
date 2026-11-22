package com.jack.ai.tools

import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Workspace
import com.jack.ai.data.design.DesignTemplates
import com.jack.ai.data.media.Subtitles
import com.jack.ai.ui.AnimRender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 代码动画（make_animation）：Kavi 写一个随时间变化的网页，preview 截几个时间点看画面，render 一帧帧出成 MP4（没声音）。
 * 底稿存成「名字.anim.html」（尺寸、时长写在 <html> 上），改了用 edit_file 改它，再 render file=底稿。
 */
internal suspend fun ToolSession.makeAnimation(args: JsonObject): String {
    val action = args["action"].text().ifBlank { "preview" }
    if (action !in setOf("preview", "render")) return "action 要填 preview（先截几帧看看）或 render（出片）"
    val given = args["html"].text()
    val fileName = args["file"].text().trim()
    val (raw, src) = when {
        given.isNotBlank() -> {
            val base = Workspace.safeName(args["filename"].text().trim().removeSuffix(".anim.html").removeSuffix(".html").ifBlank { "动画" })
            given to withContext(Dispatchers.IO) { Workspace.uniqueFile(wsDir, "$base.anim.html") }
        }
        fileName.isNotEmpty() -> {
            val f = runCatching { wsFile(fileName) }.getOrElse { return it.message.orEmpty() }
            withContext(Dispatchers.IO) { f.readText() } to f
        }
        else -> return "要写 html（完整的动画页面）或 file（之前存的底稿 .anim.html）"
    }
    val sizeRaw = args["size"].text().trim().ifBlank { Anim.attr(raw, "data-anim-size") ?: "9:16" }
    val (w, h) = Anim.size(sizeRaw) ?: return "size 写 9:16、16:9、1:1、3:4，或「宽x高」像素"
    val dur = (args["duration"].text().toDoubleOrNull() ?: Anim.attr(raw, "data-anim-duration")?.toDoubleOrNull())
        ?: return "要写 duration：片子多少秒（和配音一样长）"
    if (dur < 1 || dur > Anim.MAX_SEC) return "duration 要在 1 到 ${Anim.MAX_SEC.toInt()} 秒之间；更长的分几段做，make_video 拼起来"
    val fps = args["fps"].text().toIntOrNull()?.takeIf { it in setOf(24, 25, 30) } ?: 30
    val html = Anim.withAttrs(raw, "${w}x$h", dur)
    withContext(Dispatchers.IO) { src.writeText(html) }
    val base = src.name.removeSuffix(".anim.html").removeSuffix(".html")
    val data = DesignTemplates.data(DesignTemplates.colors(args["colors"] as? JsonObject), null, null, emptyMap(),
        DesignTemplates.icons(html), com.jack.ai.data.design.DesignElements.stickers(app, html))
    return if (action == "preview") {
        val times = Anim.previewTimes(args["times"], dur)
        runTool("动画预览：${times.size} 帧") {
            val sheet = withContext(Dispatchers.IO) { File(wsDir, "$base-预览.png") }
            val r = AnimRender.render(app, wsDir, html, w, h, data, times, fps, null, sheet)
            ui.attach(sheet)
            if (canSee) r.preview?.let { images += it }
            (report(r, src, "预览「${sheet.name}」：${times.joinToString("、") { "%.1f".format(it) }} 秒各一帧。") +
                (if (canSee && r.preview != null) "\n图在下一条消息里：看每一帧的构图、字的大小和层次、该出现的东西出现了没有、节奏对不对（第几秒该有什么）。有毛病就 edit_file 改底稿，再 preview；满意了 action=render file=「${src.name}」出片。"
                    else "\n看不到图的话，请用户看一眼预览图。")) to SearchRecord("动画预览：${src.name}")
        }
    } else {
        val frames = Math.round(dur * fps).toInt()
        runTool("动画出片：${"%.1f".format(dur)} 秒") {
            val mp4 = withContext(Dispatchers.IO) { Workspace.uniqueFile(wsDir, "$base.mp4") }
            var last = 0L
            val r = AnimRender.render(app, wsDir, html, w, h, data, List(frames) { it.toDouble() / fps }, fps, mp4, null) { n, total ->
                val now = System.currentTimeMillis()
                if (now - last > 1500 || n == total) { last = now; ui.progress("动画出片：$n/$total 帧") }
            }
            ui.attach(mp4)
            (report(r, src, "出好了：「${mp4.name}」（${Subtitles.stamp((dur * 1000).toLong())}，${w}×$h，$fps 帧/秒，没有声音）。") +
                "\n配音、配乐、音效、字幕用 make_video 合：clips=[{file=「${mp4.name}」}]，voiceover=配音 wav，subtitles=配音的 .srt，subtitles_source=\"output\"，music、sfx 照「视频剪辑」的做法。合之前可以 watch_video 看一眼。") to
                SearchRecord("动画出片：${mp4.name}")
        }
    }
}

private fun report(r: AnimRender.Result, src: File, head: String): String = buildString {
    append(head)
    append("底稿「${src.name}」：要改就 edit_file 改它，再用 file=「${src.name}」重新 preview 或 render。")
    if (r.error.isNotEmpty()) append("\n页面脚本报错：${r.error.take(200)}（先修好它）。")
    if (r.blank) append("\n第一帧是一片空白：可能 App 在后台没出画面（请用户回到 App 再试），也可能开头本来就是空的（那就没事）。")
    if (r.still) append("\n这几帧画面一模一样：动画没跟着时间走。用 CSS 动画（@keyframes）或写 render(t)，别用 setTimeout、setInterval 驱动。")
    if (r.problems.isNotEmpty()) append("\n检查：").append(r.problems.joinToString("；"))
}

/** 动画的尺寸、时长、预览时间点（纯逻辑，好测）。 */
object Anim {
    const val MAX_SEC = 90.0

    private val ASPECTS = mapOf("9:16" to (1080 to 1920), "16:9" to (1920 to 1080), "1:1" to (1080 to 1080), "3:4" to (1080 to 1440), "4:3" to (1440 to 1080))

    /** 宽高都取双数（编码器要），最长边不超过 1920。 */
    fun size(raw: String): Pair<Int, Int>? {
        val s = raw.trim().lowercase().replace('：', ':')
        val (w, h) = ASPECTS[s] ?: DesignTemplates.size(s)?.takeIf { it.second > 0 } ?: return null
        val k = minOf(1.0, 1920.0 / maxOf(w, h))
        return ((w * k).toInt() and 1.inv()) to ((h * k).toInt() and 1.inv())
    }

    fun attr(html: String, name: String): String? = Regex("""<html[^>]*\s$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

    /** 把尺寸、时长写到 <html> 上，下次 file=底稿 不用再给。 */
    fun withAttrs(html: String, size: String, dur: Double): String {
        var s = html.replace(Regex("""\sdata-anim-(size|duration)\s*=\s*["'][^"']*["']"""), "")
        val d = if (dur == Math.floor(dur)) dur.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, dur)
        val attrs = """ data-anim-size="$size" data-anim-duration="$d""""
        s = if (Regex("<html", RegexOption.IGNORE_CASE).containsMatchIn(s)) s.replaceFirst(Regex("<html", RegexOption.IGNORE_CASE), "<html$attrs")
            else "<!doctype html><html$attrs><head><meta charset=\"utf-8\"></head><body>$s</body></html>"
        return s
    }

    /** 预览截哪几帧：给了就用（最多 9 个），没给就从头到尾均匀 6 帧。 */
    fun previewTimes(raw: kotlinx.serialization.json.JsonElement?, dur: Double): List<Double> {
        val given = ((raw as? JsonArray)?.map { it.text() } ?: raw.text().split(',', '，', ' ').filter { it.isNotBlank() })
            .mapNotNull { it.trim().toDoubleOrNull() }.filter { it in 0.0..dur }.distinct().sorted().take(9)
        if (given.isNotEmpty()) return given
        return List(6) { i -> ((dur - 0.1) * i / 5 + if (i == 0) 0.3 else 0.0).coerceAtMost(dur - 0.05).coerceAtLeast(0.0) }
    }
}
