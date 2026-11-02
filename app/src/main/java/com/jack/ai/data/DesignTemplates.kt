package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设计模板（海报、小红书配图、邀请函、名片……）：一份模板就是一张固定大小的网页，放在 assets/design/templates/，
 * 目录在 assets/design/index.json。规则见 建议/设计模板_给GPT的提示词.md：data-slot 文字和图片位、data-list/data-item 列表、
 * data-icon 图标、.canvas 画布、配色全用 --bg/--fg/--primary/--secondary/--accent/--muted 六个变量。
 */
object DesignTemplates {
    data class Template(val id: String, val name: String, val category: String, val w: Int, val h: Int, val meta: JsonObject)

    /** 分类：给模型看的名字和默认尺寸（不用模板、从头设计时按它定画布）。高 0 是长图，高度随内容。 */
    val SIZES = linkedMapOf(
        "xhs" to Triple("小红书 3:4", 1080, 1440),
        "square" to Triple("朋友圈、微博方图 1:1", 1080, 1080),
        "poster" to Triple("竖版海报（手机屏幕 9:16）", 1080, 1920),
        "a4" to Triple("A4 比例海报", 1240, 1754),
        "invite" to Triple("邀请函、贺卡", 1080, 1620),
        "card" to Triple("名片（横版）", 1050, 600),
        "cover" to Triple("公众号封面", 900, 383),
        "long" to Triple("长图（高度随内容）", 1080, 0),
    )

    /** 模板分类 → 看哪几个尺寸（poster 里有 9:16 和 A4 两种）。 */
    val CATEGORIES = mapOf("xhs" to "小红书", "square" to "方图", "poster" to "海报", "invite" to "邀请函、贺卡", "card" to "名片", "cover" to "公众号封面", "long" to "长图")

    const val VARS_NOTE = "bg 背景、fg 正文字、primary 主色、secondary 辅色、accent 点缀、muted 次要文字和线条"
    private val VAR_NAMES = setOf("bg", "fg", "primary", "secondary", "accent", "muted")
    private val COLOR = Regex("""^(#[0-9a-fA-F]{3,8}|rgba?\([\d.,\s%]+\)|hsla?\([\d.,\s%deg]+\))$""")

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cache: List<Template>? = null

    fun list(ctx: Context): List<Template> = cache ?: runCatching {
        parseIndex(ctx.assets.open("design/index.json").use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrDefault(emptyList()).also { cache = it }

    internal fun parseIndex(text: String): List<Template> =
        (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val size = (o["size"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            val id = o.str("id").takeIf { Regex("^[a-z0-9-]+$").matches(it) } ?: return@mapNotNull null
            Template(id, o.str("name").ifBlank { id }, o.str("category"), size?.getOrNull(0) ?: 1080, size?.getOrNull(1) ?: 0, o)
        }

    fun html(ctx: Context, id: String): String? =
        runCatching { ctx.assets.open("design/templates/$id.html").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

    fun fillJs(ctx: Context): String = ctx.assets.open("design/fill.js").use { it.readBytes().toString(Charsets.UTF_8) }

    /** 给模型看的模板目录：每个模板一行，写明能填哪些位置、各多少字。 */
    fun catalog(list: List<Template>, category: String?): String {
        val shown = if (category.isNullOrBlank()) list else list.filter { it.category == category }
        if (shown.isEmpty()) return if (list.isEmpty()) "还没有装模板：用 html 从头设计（见「设计」技能的做法）。"
            else "「$category」这类还没有模板。有的分类：" + list.map { it.category }.distinct().joinToString("、") { "$it（${CATEGORIES[it] ?: it}）" }
        return shown.joinToString("\n") { t ->
            val m = t.meta
            fun arr(k: String) = (m[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val slots = arr("slots").joinToString("、") { s -> s.str("name") + "（" + s.str("label") + s.str("max").let { if (it.isBlank()) "" else " ≤$it 字" } + "）" }
            val lists = arr("lists").joinToString("；") { l ->
                l.str("name") + "（" + l.str("label") + " " + l.str("min") + "~" + l.str("max") + " 项，每项：" +
                    arr2(l, "slots").joinToString("、") { s -> s.str("name") + s.str("max").let { if (it.isBlank()) "" else " ≤$it 字" } } + "）"
            }
            val images = arr("images").joinToString("、") { i -> i.str("name") + "（" + i.str("label") + if (i.str("optional") == "true") "，可不填）" else "）" }
            val colors = (m["colors"] as? JsonObject)?.entries?.joinToString(" ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.content}" }.orEmpty()
            "- ${t.id}｜${t.name}｜${m.str("style")}｜${if (t.h == 0) "${t.w}×长" else "${t.w}×${t.h}"}｜${m.str("description")}" +
                (if (slots.isNotEmpty()) "\n  文字和图片：$slots" else "") + (if (images.isNotEmpty()) "；图片：$images" else "") +
                (if (lists.isNotEmpty()) "\n  列表：$lists" else "") + (if (colors.isNotEmpty()) "\n  默认配色：$colors" else "")
        }
    }

    private fun arr2(o: JsonObject, k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** 尺寸：分类名（xhs、poster……）或「宽x高」。认不出返回 null。 */
    fun size(raw: String): Pair<Int, Int>? {
        val s = raw.trim().lowercase()
        SIZES[s]?.let { return it.second to it.third }
        val m = Regex("""^(\d{3,4})\s*[x×*]\s*(\d{1,5})$""").find(s) ?: return null
        val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
        return if (w in 200..3000 && (h == 0 || h in 200..12000)) w to h else null
    }

    /** 模型给的配色：只留六个变量、只认颜色值（不让 CSS 从这里混进来）。 */
    fun colors(o: JsonObject?): Map<String, String> = o.orEmpty().mapNotNull { (k, v) ->
        val c = (v as? JsonPrimitive)?.content?.trim().orEmpty()
        val name = k.removePrefix("--")
        if (name in VAR_NAMES && COLOR.matches(c)) name to c else null
    }.toMap()

    /** 页面里用到的图标名 → 内联 SVG（Lucide/Tabler 线框图标，坐标 0..2400，颜色跟文字走）。 */
    fun icons(html: String): Map<String, String> =
        Regex("""data-icon\s*=\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.distinct().mapNotNull { raw ->
            val name = PptDeck.iconName(raw) ?: return@mapNotNull null
            val paths = PptDeck.ICONS[name] ?: return@mapNotNull null
            raw to """<svg viewBox="0 0 2400 2400" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="180" stroke-linecap="round" stroke-linejoin="round" style="display:block">""" +
                paths.joinToString("") { """<path d="$it"/>""" } + "</svg>"
        }.toMap()

    /** 底稿里记下的画布尺寸（出图时写在 <html data-kavi-size="宽x高">）。 */
    fun savedSize(html: String): Pair<Int, Int>? =
        Regex("""data-kavi-size\s*=\s*["'](\d+)x(\d+)["']""").find(html)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /**
     * 出图前把页面准备好：记下尺寸；去掉原来的 viewport，换成画布宽（WebView 按这个宽把一个 CSS 像素对上一个像素）；
     * 关掉手机的字号自动放大；放进填内容的脚本。临时加的都带 data-kavi-temp，存底稿时去掉。
     */
    fun prepare(html: String, w: Int, h: Int, fillJs: String): String {
        var s = html.replace(Regex("""<meta[^>]+name\s*=\s*["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\sdata-kavi-size\s*=\s*["'][^"']*["']"""), "")
        s = if (Regex("<html", RegexOption.IGNORE_CASE).containsMatchIn(s)) s.replaceFirst(Regex("<html", RegexOption.IGNORE_CASE), """<html data-kavi-size="${w}x$h"""")
            else """<!doctype html><html data-kavi-size="${w}x$h"><head></head><body>$s</body></html>"""
        val head = """<meta data-kavi-temp name="viewport" content="width=$w">""" +
            """<style data-kavi-temp>html{-webkit-text-size-adjust:100%;text-size-adjust:100%}body{margin:0}${if (h > 0) ".canvas{width:${w}px;height:${h}px;overflow:hidden}" else ".canvas{width:${w}px}"}</style>""" +
            "<script data-kavi-temp>\n$fillJs\n</script>"
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(s)
        return if (m != null) s.substring(0, m.range.last + 1) + head + s.substring(m.range.last + 1)
            else s.replaceFirst(Regex("""(<html[^>]*>)""", RegexOption.IGNORE_CASE), "$1<head>$head</head>")
    }

    /** 填内容的数据：交给 fill.js 的 kaviFill。 */
    fun data(vars: Map<String, String>, slots: JsonObject?, lists: JsonObject?, images: Map<String, String>, icons: Map<String, String>): JsonObject = buildJsonObject {
        put("vars", JsonObject(vars.mapValues { JsonPrimitive(it.value) }))
        put("slots", slots ?: JsonObject(emptyMap()))
        put("lists", lists ?: JsonObject(emptyMap()))
        put("images", JsonObject(images.mapValues { JsonPrimitive(it.value) }))
        put("icons", JsonObject(icons.mapValues { JsonPrimitive(it.value) }))
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
}
