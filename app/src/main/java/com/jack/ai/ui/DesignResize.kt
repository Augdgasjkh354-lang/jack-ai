package com.jack.ai.ui

import android.content.Context
import com.jack.ai.data.DesignSeries
import com.jack.ai.data.DesignTemplates
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一键改尺寸：把做好的设计图按新尺寸重新排版（不是拉伸、裁切），比如小红书 3:4 改成方图、竖版海报、公众号封面。
 * 单独请一次模型重排整张，出新图存在旁边（原图不动），检查出毛病再请它改一次。编辑器和 design action=resize 都用它。
 */
object DesignResize {
    /** 能改成的尺寸（长图不算：高度随内容，用不着改）。 */
    val TARGETS = listOf("xhs", "square", "poster", "a4", "invite", "card", "cover")

    /** 新文件名里的叫法。 */
    val LABELS = mapOf("xhs" to "小红书", "square" to "方图", "poster" to "竖版海报", "a4" to "A4", "invite" to "邀请函", "card" to "名片", "cover" to "公众号封面")

    class Out(val png: File, val html: File, val problems: List<String>, val note: String)

    private const val RULES = """You re-lay out a finished design (an HTML/CSS poster or social-media card) for a new canvas size, the way a designer adapts one design to several formats. You get the old size, the new size and the full HTML document.
Return only one JSON object: {"html": "the full new HTML document", "note": "one short sentence in Chinese saying how you adapted the layout"}.

Keep:
- All the content: every text, every element with data-slot, data-max, data-kind, data-list, data-item, data-icon or data-kv-* attributes (keep those attributes exactly), list items, photos (background-image URLs exactly as they are), icons.
- The look: the canvas's CSS variables (palette --bg, --fg, --primary, --secondary, --accent, --muted and fonts --font-*), font families, colors, decorations, textures. Same style, new format.

Change the layout so it looks designed for the new size, not scaled or cropped:
- Set .canvas width and height to the new size (in the CSS and in any inline style on the canvas).
- Wide and short formats (width at least 1.6 × height, like 900×383 covers or 1050×600 business cards): go horizontal, e.g. title and text on the left, photo or big graphic on the right. Small canvases hold less: keep the title, the key line and the essential info; hide minor pieces (tags, notes, extra list items) with style="display:none" instead of deleting them; shorten text only when it can't fit otherwise.
- Tall formats (9:16, A4): more vertical rhythm, a bigger focal title, more breathing room; don't leave a big empty band.
- Square: compact, one clear focal point.
- Elements the user moved by hand carry inline transform: translate(...) or left/top in their style: recompute them for the new layout, or remove them when the normal flow already places the element well.
- Font sizes in px scale with the canvas: title at least 2.5 × body text; body at least 24px on canvases 1000px or wider, at least 18px on smaller ones.
- Keep at least 5% of the shorter side as margin from every edge. Nothing may overflow the canvas, and no text may be clipped.
- Rewrite the CSS rules in <style> as needed. No <script>, no event attributes, no external URLs."""

    fun slotsOf(html: String): Set<String> =
        Regex("""\bdata-slot\s*=\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toSet()

    /** 请模型按新尺寸重排一次；fix 不为空时是在上一版的基础上改掉这些毛病。 */
    internal suspend fun relayout(ctx: Context, html: String, from: Pair<Int, Int>, w: Int, h: Int, label: String, fix: List<String> = emptyList()): DesignKavi.Result {
        if (html.length > 80_000) error("这张图的底稿太大，没法自动改尺寸")
        val input = buildString {
            if (fix.isEmpty()) append("Old size: ${from.first}×${from.second}px. New size: ${w}×${h}px ($label).\n\n【HTML】\n")
            else append("You already adapted this design to ${w}×${h}px ($label), but the check found problems. Fix them and return the full corrected document.\n【Problems】\n")
                .append(fix.joinToString("\n") { "- $it" }).append("\n\n【Your HTML】\n")
            append(html)
        }
        val raw = DesignKavi.ask(ctx, RULES, input, 16_000)
        return DesignKavi.parse(raw)?.takeIf { "canvas" in it.html } ?: error("Kavi 这次没排好，再试一次")
    }

    /**
     * 把一张设计图（底稿 .html）改成 w×h，存成 [stem].png 和 [stem].html（stem 不带扩展名、已经确定不重名）。
     * 出图检查有毛病、或者丢了文字位，再请模型改一次。
     */
    suspend fun resize(ctx: Context, root: File, src: File, w: Int, h: Int, label: String, stem: String, renderLock: Mutex = Mutex()): Out {
        val html = withContext(Dispatchers.IO) { src.readText() }
        val from = DesignTemplates.savedSize(html) ?: (1080 to 1440)
        val png = File(src.parentFile, "$stem.png")
        val out = File(src.parentFile, "$stem.html")
        val want = slotsOf(html)
        var res = relayout(ctx, html, from, w, h, label)
        var problems = emptyList<String>()
        for (round in 0..1) {
            val r = renderLock.withLock {
                DesignRender.render(ctx, root, res.html, w, h, DesignTemplates.data(emptyMap(), null, null, emptyMap(), DesignTemplates.icons(res.html)), png, null)
            }
            withContext(Dispatchers.IO) { out.writeText(r.source.ifBlank { res.html }) }
            val lost = (want - slotsOf(res.html)).map { "The element with data-slot=\"$it\" is missing: put it back (hide it with display:none if it doesn't fit)." }
            problems = r.problems + lost
            if (problems.isEmpty() || round == 1) break
            res = runCatching { relayout(ctx, res.html, from, w, h, label, problems) }.getOrNull() ?: break
        }
        return Out(png, out, problems, res.note)
    }

    /** 新图的名字（不带扩展名）：一组是「原名-方图-01、-02……」（还是一组），单张是「原名-方图」；重名就往后加数字。 */
    internal fun stems(srcs: List<File>, label: String): List<String> {
        val first = srcs.first()
        val dir = first.parentFile!!
        val group = srcs.size > 1
        val tag = label.replace(Regex("""[\\/:*?"<>|\s]+"""), "")
        if (!group) return listOf(Workspace.uniqueFile(dir, first.nameWithoutExtension + "-" + tag + ".png").nameWithoutExtension)
        val name = (DesignSeries.baseOf(first) ?: first.nameWithoutExtension) + "-" + tag
        val base = generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name$it" }.first { !File(dir, DesignSeries.pageName(it, 0) + ".png").exists() }
        return srcs.indices.map { DesignSeries.pageName(base, it) }
    }

    /**
     * 一张或一组改尺寸。一组的新名字是「原名-方图-01、-02……」（还是一组，编辑器里能翻页），单张是「原名-方图」。
     * 模型同时请三张，出图一张一张来。progress(做完几张, 一共几张)。有几张没成的跳过（全没成才报错）。
     */
    suspend fun resizeAll(ctx: Context, root: File, srcs: List<File>, w: Int, h: Int, label: String, progress: (Int, Int) -> Unit = { _, _ -> }): List<Out> {
        if (srcs.isEmpty()) return emptyList()
        val stems = withContext(Dispatchers.IO) { stems(srcs, label) }
        val gate = Semaphore(3)
        val lock = Mutex()
        var done = 0
        progress(0, srcs.size)
        val results = coroutineScope {
            srcs.mapIndexed { i, f ->
                async {
                    val r = gate.withPermit { runCatching { resize(ctx, root, f, w, h, label, stems[i], lock) } }
                    synchronized(this@DesignResize) { done++ }
                    progress(done, srcs.size)
                    r
                }
            }.map { it.await() }
        }
        // 全失败才报错；失败了几张的，成功的照样留着
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        return results.mapNotNull { it.getOrNull() }
    }
}
