package com.jack.ai.data

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 照着用户给的 PPT 做：从它的主题里读出配色和字体，变成一套 [PptDeck.Theme]。
 * 主题还是 Office 默认那套蓝橙时，多半颜色是直接涂在页面上的，就看页面里哪种颜色涂得最多（按面积算，不按次数）。
 * 只读颜色和字体，不搬版式和内容。
 */
object PptStyle {
    class Result(val theme: PptDeck.Theme, val summary: String)

    /** Office 自带主题的强调色：看到这些就说明用户没改主题色。 */
    private val OFFICE_DEFAULTS = setOf("4472C4", "156082", "5B9BD5", "4F81BD", "ED7D31", "E97132", "0F9ED5")

    fun extract(file: File): Result = ZipFile(file).use { z ->
        fun read(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }
        val masterRels = read("ppt/slideMasters/_rels/slideMaster1.xml.rels").orEmpty()
        val themePath = Regex("""Target="\.\./theme/(theme\d+\.xml)"""").find(masterRels)?.groupValues?.get(1)?.let { "ppt/theme/$it" } ?: "ppt/theme/theme1.xml"
        val theme = read(themePath) ?: throw IllegalArgumentException("这个文件里没有主题，可能不是 PowerPoint 或 WPS 存的 .pptx")
        fun scheme(tag: String): String? {
            val block = Regex("""<a:$tag>(.*?)</a:$tag>""", RegexOption.DOT_MATCHES_ALL).find(theme)?.groupValues?.get(1) ?: return null
            return Regex("""(?:srgbClr val|lastClr)="([0-9A-Fa-f]{6})"""").find(block)?.groupValues?.get(1)?.uppercase()
        }
        fun font(kind: String, script: String): String? {
            val block = Regex("""<a:$kind>(.*?)</a:$kind>""", RegexOption.DOT_MATCHES_ALL).find(theme)?.groupValues?.get(1) ?: return null
            return Regex("""<a:$script typeface="([^"]+)"""").find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && !it.startsWith("+") }
                // 主题里没写中文字体时，看有没有给简体中文单独指定的
                ?: if (script == "ea") Regex("""<a:font script="Hans" typeface="([^"]+)"""").find(block)?.groupValues?.get(1) else null
        }
        val dk1 = scheme("dk1") ?: "000000"; val lt1 = scheme("lt1") ?: "FFFFFF"
        val dk2 = scheme("dk2") ?: dk1
        val accents = (1..6).mapNotNull { scheme("accent$it") }

        // 页面上直接涂的颜色和背景色
        val slides = z.entries().toList().map { it.name }.filter { it.matches(Regex("""ppt/(slides/slide|slideMasters/slideMaster|slideLayouts/slideLayout)\d+\.xml""")) }
        val counts = HashMap<String, Double>()
        val bgs = HashMap<String, Int>()
        fun add(c: String, w: Double) { counts[c] = (counts[c] ?: 0.0) + w }
        slides.forEach { name ->
            val xml = read(name).orEmpty()
            // 背景算一整页；形状按它的面积（占一页的几分之几）；半透明的装饰不算；文字的颜色算一点点
            Regex("""<p:bg>.*?srgbClr val="([0-9A-Fa-f]{6})".*?</p:bg>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.let { m ->
                val c = m.groupValues[1].uppercase(); bgs[c] = (bgs[c] ?: 0) + 1; add(c, 1.0)
            }
            Regex("""<p:spPr>(.*?)</p:spPr>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                val sp = m.groupValues[1]
                val ext = Regex("""<a:ext cx="(\d+)" cy="(\d+)"""").find(sp) ?: return@forEach
                val fill = Regex("""^(?:(?!<a:ln).)*?<a:solidFill><a:srgbClr val="([0-9A-Fa-f]{6})"(/>|>(.*?)</a:srgbClr>)""", RegexOption.DOT_MATCHES_ALL).find(sp) ?: return@forEach
                val alpha = Regex("""<a:alpha val="(\d+)"""").find(fill.groupValues[3])?.groupValues?.get(1)?.toInt() ?: 100000
                if (alpha < 50000) return@forEach
                val area = ext.groupValues[1].toDouble() * ext.groupValues[2].toDouble() / (12192000.0 * 6858000.0)
                add(fill.groupValues[1].uppercase(), area.coerceIn(0.0, 1.0))
            }
            Regex("""<a:rPr[^>]*>\s*<a:solidFill><a:srgbClr val="([0-9A-Fa-f]{6})"""").findAll(xml).forEach { m -> add(m.groupValues[1].uppercase(), 0.005) }
        }
        val painted = counts.entries.filter { saturation(it.key) > 0.25 && lum(it.key) in 0.08..0.9 }.sortedByDescending { it.value }.map { it.key }
        val themeColors = accents.filter { saturation(it) > 0.2 }
        val defaultTheme = themeColors.isEmpty() || themeColors.first() in OFFICE_DEFAULTS
        val palette = (if (defaultTheme && painted.isNotEmpty()) painted + themeColors else themeColors + painted).distinct()
        val primary = palette.firstOrNull() ?: "1E40AF"
        // 强调色要和主色有点区别（色相差得开，或者深浅差得开）
        val accent = palette.drop(1).firstOrNull { hueGap(it, primary) > 25 || abs(lum(it) - lum(primary)) > 0.1 } ?: mix(primary, "FFFFFF", 0.35)

        val bg = bgs.maxByOrNull { it.value }?.key ?: lt1
        val dark = lum(bg) < 0.35
        val text = if (dark) (if (lum(lt1) > 0.6) lt1 else "E5E7EB") else (if (lum(dk1) < 0.4) dk1 else "333333")
        val title = if (dark) "F8FAFC" else if (lum(dk2) < 0.35 && saturation(dk2) > 0.15) dk2 else darken(primary, 0.35)
        val headEa = font("majorFont", "ea"); val bodyEa = font("minorFont", "ea")
        val headLatin = font("majorFont", "latin"); val bodyLatin = font("minorFont", "latin")
        val t = PptDeck.Theme(
            primary = if (dark && lum(primary) < 0.15) bg else primary, accent = accent, bg = bg, title = title, text = text,
            soft = mix(primary, bg, if (dark) 0.8 else 0.9), card = mix(primary, bg, if (dark) 0.88 else 0.965), muted = mix(text, bg, 0.45), dark = dark,
            latin = bodyLatin ?: bodyEa ?: "微软雅黑", ea = bodyEa ?: "微软雅黑", headLatin = headLatin ?: headEa ?: bodyLatin ?: "微软雅黑", headEa = headEa ?: bodyEa ?: "微软雅黑",
        )
        val fonts = listOfNotNull(headEa ?: headLatin, bodyEa ?: bodyLatin).distinct().joinToString("、").ifEmpty { "没写，用微软雅黑" }
        Result(t, "照着「${file.name}」：主色 #$primary，强调色 #$accent，背景 #$bg（${if (dark) "深色" else "浅色"}），字体 $fonts" +
            (if (defaultTheme && painted.isNotEmpty()) "（主题是 Office 默认色，颜色取自页面上用得最多的）" else ""))
    }

    // ---------- 颜色计算 ----------

    private fun rgb(h: String) = Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    private fun hex(r: Int, g: Int, b: Int) = "%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** 亮度 0..1。 */
    internal fun lum(h: String): Double { val (r, g, b) = rgb(h); return (0.299 * r + 0.587 * g + 0.114 * b) / 255 }

    internal fun saturation(h: String): Double {
        val (r, g, b) = rgb(h); val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        return if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
    }

    private fun hue(h: String): Double {
        val (r0, g0, b0) = rgb(h); val r = r0 / 255.0; val g = g0 / 255.0; val b = b0 / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0.0) return 0.0
        val x = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        return (x * 60 + 360) % 360
    }

    private fun hueGap(a: String, b: String): Double { val d = abs(hue(a) - hue(b)); return min(d, 360 - d) }

    /** a 往 b 混 k（0 是 a，1 是 b）。 */
    internal fun mix(a: String, b: String, k: Double): String {
        val (r1, g1, b1) = rgb(a); val (r2, g2, b2) = rgb(b)
        return hex((r1 + (r2 - r1) * k).toInt(), (g1 + (g2 - g1) * k).toInt(), (b1 + (b2 - b1) * k).toInt())
    }

    private fun darken(h: String, k: Double) = mix(h, "000000", k)
}
