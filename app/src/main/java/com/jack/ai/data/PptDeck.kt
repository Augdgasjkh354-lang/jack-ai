package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * PPT 每一页怎么排：12 种版式、9 套配色、Lucide 矢量图标（跟着配色变色，放大不糊），
 * 字多了自动缩小字号，再多就截掉并提醒；还有转场和逐条出现的动画。
 * [OfficeWriter.writePptx] 负责打包，这里只管一页的样子和动作。
 */
object PptDeck {
    const val W = 12192000L
    const val H = 6858000L
    private const val MX = 640000L
    private const val FONT = "微软雅黑"

    val LAYOUTS = listOf("title", "section", "agenda", "content", "cards", "two_column", "timeline", "stats", "process", "chart", "quote", "image", "end")
    val TRANSITIONS = listOf("none", "fade", "push", "wipe", "split", "cover", "zoom", "morph")
    val ANIMATIONS = listOf("none", "fade", "rise", "wipe")

    /** 配色和字体。latin/ea 是正文的西文、中文字体，head 开头的是标题和加粗字用的。 */
    data class Theme(
        val primary: String, val accent: String, val bg: String, val title: String, val text: String,
        val soft: String, val card: String, val muted: String, val dark: Boolean = false,
        val latin: String = FONT, val ea: String = FONT, val headLatin: String = FONT, val headEa: String = FONT,
    )

    /** 配色：商务蓝、自然绿、活力橙、文艺紫、科技暗、中国红、清新青、极简黑白、暖调莫兰迪。 */
    val THEMES = mapOf(
        "blue" to Theme("1E40AF", "3B82F6", "FFFFFF", "1E3A8A", "334155", "EFF6FF", "F8FAFC", "64748B"),
        "green" to Theme("166534", "22C55E", "FFFFFF", "14532D", "334155", "F0FDF4", "F8FAFC", "64748B"),
        "orange" to Theme("C2410C", "F97316", "FFFFFF", "7C2D12", "3F3F46", "FFF7ED", "FAFAF9", "78716C"),
        "purple" to Theme("6D28D9", "A78BFA", "FFFFFF", "4C1D95", "3F3F46", "F5F3FF", "FAFAFA", "71717A"),
        "dark" to Theme("0B1220", "38BDF8", "0F172A", "F8FAFC", "CBD5E1", "1E293B", "162033", "94A3B8", dark = true),
        "red" to Theme("B91C1C", "F59E0B", "FFFFFF", "7F1D1D", "3F3F46", "FEF2F2", "FAFAF9", "78716C"),
        "teal" to Theme("0F766E", "14B8A6", "FFFFFF", "134E4A", "334155", "F0FDFA", "F8FAFC", "64748B"),
        "ink" to Theme("18181B", "2563EB", "FFFFFF", "18181B", "3F3F46", "F4F4F5", "FAFAFA", "71717A"),
        "warm" to Theme("7C6A5A", "C9A27E", "FAF7F2", "4A3F35", "5C5248", "F1EBE3", "FFFFFF", "8C8279"),
    /** 新增：形状、年度、太平洋、液滴、玛奇朵、头条、科学、城市、浅蓝、音乐（均取自微软 Create 官方模板，见 tools/ms-templates.json）。 */
        "shapes" to Theme("445464", "80AF97", "FFFFFF", "2C3741", "000000", "ECEEF0", "F8F9FA", "737373"),
        "impact" to Theme("4495A2", "7CA655", "FFFFFF", "2C6169", "000000", "ECF4F6", "F8FBFC", "737373"),
        "pacific" to Theme("AC5B4C", "A09D79", "FFFFFF", "703B31", "543E34", "F7EFED", "FCF9F9", "A1958F"),
        "droplet" to Theme("F19E17", "054E9D", "FFFFFF", "9D670F", "000000", "FEF5E8", "FFFCF7", "737373"),
        "macchiato" to Theme("A9D4DB", "FBE284", "FFFFFF", "6E8A8E", "000000", "F6FBFB", "FCFDFE", "737373"),
        "headlines" to Theme("474134", "87847B", "FFFFFF", "59512B", "000000", "EDECEB", "F9F8F8", "737373"),
        "scientific" to Theme("70AAC4", "8B6A94", "FFFFFF", "496E7F", "374C81", "F1F6F9", "FAFCFD", "919DBA"),
        "city" to Theme("4A5EE6", "62D382", "FFFFFF", "303D96", "36393B", "EDEFFC", "F9F9FE", "909293"),
        "ms11-blue" to Theme("96D3ED", "688EBD", "FFFFFF", "44546A", "000000", "F4FBFD", "FBFDFE", "737373"),
        "music" to Theme("0F263F", "4472C4", "FFFFFF", "0F253E", "000000", "E7E9EC", "F7F7F8", "737373"),
    )

    fun theme(name: String) = THEMES[name] ?: THEMES.getValue("blue")

    // ---------- 图标 ----------

    /** 图标名 → 每条路径（M/L/C/Z，坐标 0..2400）。从 Lucide 转来，见 tools/lucide2json.py。 */
    val ICONS: Map<String, List<String>> by lazy {
        runCatching {
            val text = PptDeck::class.java.getResourceAsStream("/ppt/icons.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
            (Json.parseToJsonElement(text) as JsonObject).mapValues { (_, v) -> (v as JsonArray).map { it.jsonPrimitive.content } }
        }.getOrDefault(emptyMap())
    }

    /** 模型写的图标名认不出时找个最像的：去掉空格大小写、找包含关系，再不行就不放。 */
    fun iconName(raw: String): String? {
        val n = raw.trim().lowercase().replace(' ', '-').replace('_', '-')
        if (n.isEmpty()) return null
        if (n in ICONS) return n
        ALIASES[n]?.let { return it }
        return ICONS.keys.firstOrNull { it.startsWith("$n-") || it.endsWith("-$n") } ?: ICONS.keys.firstOrNull { n.contains(it) && it.length >= 4 }
    }

    private val ALIASES = mapOf(
        "money" to "banknote", "home" to "house", "chart" to "chart-column", "bar-chart" to "chart-column", "line-chart" to "chart-line",
        "pie-chart" to "chart-pie", "growth" to "trending-up", "idea" to "lightbulb", "team" to "users", "people" to "users",
        "person" to "user", "time" to "clock", "date" to "calendar", "location" to "map-pin", "email" to "mail", "security" to "shield-check",
        "safe" to "shield-check", "check" to "circle-check", "warning" to "triangle-alert", "question" to "circle-help", "help" to "circle-help",
        "ai" to "brain", "robot" to "bot", "health" to "heart-pulse", "medicine" to "pill", "education" to "graduation-cap", "study" to "book-open",
        "book" to "book-open", "goal" to "goal", "success" to "trophy", "win" to "trophy", "quality" to "award", "fast" to "zap", "energy" to "zap",
        "environment" to "leaf", "eco" to "leaf", "nature" to "trees", "world" to "earth", "global" to "globe", "travel" to "plane",
        "food" to "utensils", "shop" to "store", "shopping" to "shopping-cart", "data" to "database", "computer" to "laptop", "phone-mobile" to "smartphone",
        "cooperation" to "handshake", "partner" to "handshake", "plan" to "clipboard-list", "strategy" to "compass", "process" to "workflow",
        "law" to "scale", "finance" to "circle-dollar-sign", "cost" to "receipt", "save" to "piggy-bank", "innovation" to "sparkles",
    )

    /** 一个矢量图标：线条粗细按 Lucide 的 2/24。 */
    private fun icon(id: Int, name: String, x: Long, y: Long, size: Long, color: String): String {
        val paths = ICONS[name] ?: return ""
        iconIds.get()?.add(id)
        // 所有笔画合成一条路径（里面可以有好几个 moveTo）：有的查看器只画第一条
        val pl = """<a:path w="2400" h="2400" fill="none">${paths.joinToString("") { pathOps(it) }}</a:path>"""
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Icon $name"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$size" cy="$size"/></a:xfrm>""" +
            """<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="0" t="0" r="r" b="b"/><a:pathLst>$pl</a:pathLst></a:custGeom><a:noFill/>""" +
            """<a:ln w="${max(9525L, size / 12)}" cap="rnd"><a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:round/></a:ln></p:spPr></p:sp>"""
    }

    internal fun pathOps(p: String): String = buildString {
        for (tok in p.split(' ')) {
            if (tok.isEmpty()) continue
            val n = tok.substring(1).split(',').filter { it.isNotEmpty() }
            fun pt(i: Int) = """<a:pt x="${n[i]}" y="${n[i + 1]}"/>"""
            when (tok[0]) {
                'M' -> append("<a:moveTo>").append(pt(0)).append("</a:moveTo>")
                'L' -> append("<a:lnTo>").append(pt(0)).append("</a:lnTo>")
                'C' -> append("<a:cubicBezTo>").append(pt(0)).append(pt(2)).append(pt(4)).append("</a:cubicBezTo>")
                'Z' -> append("<a:close/>")
            }
        }
    }

    /** 这一页里哪些编号是图标（画的时候记下，动画要用）。 */
    private val iconIds = ThreadLocal<MutableSet<Int>>()
    /** 正在画的这页用的配色（字体从这里取）。 */
    private val current = ThreadLocal<Theme>()

    // ---------- 字号 ----------

    /** 估一段字在这个宽度、这个字号下占几行：中文一个字一个字号宽，英文数字大约一半。 */
    internal fun lines(text: String, size: Int, width: Long): Int {
        val em = size / 100.0 * 12700
        var w = 0.0
        for (ch in text) w += if (ch.code >= 0x2E80) em else if (ch == ' ') em * 0.3 else em * 0.56
        return max(1, ceil(w / (width * 0.92)).toInt())
    }

    /** 在 [min, max] 里找放得下的最大字号（每段之间算 spacing 倍行高的空）。 */
    internal fun fit(texts: List<String>, width: Long, height: Long, maxSize: Int, minSize: Int, spacing: Double = 0.4): Int {
        var size = maxSize
        while (size > minSize) {
            val lineH = size / 100.0 * 12700 * 1.25
            val total = texts.sumOf { lines(it, size, width) } * lineH + (texts.size - 1).coerceAtLeast(0) * lineH * spacing
            if (total <= height * 0.95) return size
            size -= 100
        }
        return minSize
    }

    /** 几个并排的框用同一个字号：取每个框各自放得下的最小那个。 */
    internal fun fitEach(texts: List<String>, width: Long, height: Long, maxSize: Int, minSize: Int): Int =
        texts.filter { it.isNotBlank() }.minOfOrNull { fit(listOf(it), width, height, maxSize, minSize) } ?: maxSize

    // ---------- 形状 ----------

    private fun esc(s: String) = OfficeWriter.escXml(s)

    private fun fill(color: String?, alpha: Int = 100) = when {
        color == null -> "<a:noFill/>"
        alpha >= 100 -> """<a:solidFill><a:srgbClr val="$color"/></a:solidFill>"""
        else -> """<a:solidFill><a:srgbClr val="$color"><a:alpha val="${alpha * 1000}"/></a:srgbClr></a:solidFill>"""
    }

    private fun shape(
        id: Int, x: Long, y: Long, cx: Long, cy: Long, geom: String = "rect", fill: String? = null, alpha: Int = 100,
        line: String? = null, lineW: Long = 12700, adj: String = "", body: String = "", name: String = "Shape $id", shadow: Boolean = false,
    ): String {
        val av = if (adj.isEmpty()) "<a:avLst/>" else """<a:avLst><a:gd name="adj" fmla="val $adj"/></a:avLst>"""
        val ln = if (line == null) "<a:ln><a:noFill/></a:ln>" else """<a:ln w="$lineW"><a:solidFill><a:srgbClr val="$line"/></a:solidFill></a:ln>"""
        val fx = if (shadow) """<a:effectLst><a:outerShdw blurRad="190500" dist="38100" dir="5400000" algn="t" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="12000"/></a:srgbClr></a:outerShdw></a:effectLst>""" else ""
        val tx = body.ifEmpty { """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody>""" }
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="$geom">$av</a:prstGeom>${fill(fill, alpha)}$ln$fx</p:spPr>$tx</p:sp>"""
    }

    private class Run(val text: String, val size: Int, val color: String, val bold: Boolean = false)

    private fun para(runs: List<Run>, align: String = "l", bullet: String? = null, bulletColor: String = "", level: Int = 0, before: Int = 0): String {
        val ppr = if (bullet != null) {
            val mar = 342900 + level * 457200
            """<a:pPr marL="$mar" indent="-342900" algn="$align"><a:lnSpc><a:spcPct val="110000"/></a:lnSpc><a:spcBef><a:spcPts val="$before"/></a:spcBef><a:buClr><a:srgbClr val="$bulletColor"/></a:buClr><a:buFont typeface="Arial"/><a:buChar char="$bullet"/></a:pPr>"""
        } else """<a:pPr algn="$align"><a:lnSpc><a:spcPct val="110000"/></a:lnSpc><a:spcBef><a:spcPts val="$before"/></a:spcBef><a:buNone/></a:pPr>"""
        val th = current.get()
        val rs = runs.joinToString("") { r ->
            // 加粗的（标题、小标题）用标题字体，其余用正文字体
            val latin = esc((if (r.bold) th?.headLatin else th?.latin) ?: FONT)
            val ea = esc((if (r.bold) th?.headEa else th?.ea) ?: FONT)
            """<a:r><a:rPr lang="zh-CN" altLang="en-US" sz="${r.size}"${if (r.bold) " b=\"1\"" else ""} dirty="0">${fill(r.color)}<a:latin typeface="$latin"/><a:ea typeface="$ea"/></a:rPr><a:t>${esc(r.text)}</a:t></a:r>"""
        }
        return "<a:p>$ppr$rs</a:p>"
    }

    /** 不开自动缩小：字号已经用 [fit] 算好了，再让 PowerPoint、WPS、LibreOffice 各缩各的，大小就不一致了。 */
    private fun body(paras: List<String>, anchor: String = "t", inset: Long = 91440) =
        """<p:txBody><a:bodyPr wrap="square" lIns="$inset" tIns="45720" rIns="$inset" bIns="45720" anchor="$anchor"><a:noAutofit/></a:bodyPr><a:lstStyle/>${paras.joinToString("")}</p:txBody>"""

    private fun text(id: Int, x: Long, y: Long, cx: Long, cy: Long, paras: List<String>, anchor: String = "t", name: String = "Text $id"): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>${body(paras, anchor)}</p:sp>"""

    /** 图片：cover=true 时裁成铺满框（大图页），否则按比例放进框里。 */
    private fun picture(id: Int, rId: String, x: Long, y: Long, cx: Long, cy: Long, imgW: Int, imgH: Int, cover: Boolean, round: Boolean): String {
        var bx = x; var by = y; var bw = cx; var bh = cy
        var crop = ""
        val ratio = if (imgW > 0 && imgH > 0) imgW.toDouble() / imgH else 4.0 / 3
        if (cover) {
            val boxRatio = cx.toDouble() / cy
            if (ratio > boxRatio) { val cut = ((1 - boxRatio / ratio) / 2 * 100000).toInt(); crop = """<a:srcRect l="$cut" r="$cut"/>""" }
            else { val cut = ((1 - ratio / boxRatio) / 2 * 100000).toInt(); crop = """<a:srcRect t="$cut" b="$cut"/>""" }
        } else {
            var w = cx; var h = (cx / ratio).toLong()
            if (h > cy) { h = cy; w = (cy * ratio).toLong() }
            bx = x + (cx - w) / 2; by = y + (cy - h) / 2; bw = w; bh = h
        }
        val geom = if (round) """<a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val 4000"/></a:avLst></a:prstGeom>""" else """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>"""
        return """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture $id"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="$rId"/>$crop<a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$bx" y="$by"/><a:ext cx="$bw" cy="$bh"/></a:xfrm>$geom</p:spPr></p:pic>"""
    }

    // ---------- 一页 ----------

    /** 动画的一步：点一下，这些一起出现。target 是 (形状编号, 第几段)，段为 -1 表示整个形状。 */
    class Step(val targets: List<Pair<Int, Int>>)

    /**
     * noBuild：图片和图标，动画的 bldLst 里不列它们（没有文字）；graphics：图表框，bldLst 里写成 bldGraphic。
     * chart：理过的图表数据，打包时写成图表部件（关系 rId3）。
     */
    class Rendered(
        val bg: String, val shapes: String, val steps: List<Step>, val byParagraph: Set<Int>, val noBuild: Set<Int>, val warnings: List<String>,
        val graphics: Set<Int> = emptySet(), val chart: PptChart.Chart? = null,
    )

    /** 「标题：说明」拆成两半；没有冒号就只有标题。 */
    internal fun split(s: String): Pair<String, String> {
        val i = s.indexOfFirst { it == '：' || it == ':' }
        return if (i in 1..30) s.substring(0, i).trim() to s.substring(i + 1).trim() else s.trim() to ""
    }

    /**
     * 画一页。number 是第几页（页码），section 是第几个章节（章节页的大号编号）。
     * image 有的话用 rId2。
     */
    fun render(s: OfficeWriter.Slide, t: Theme, number: Int, total: Int, section: Int): Rendered {
        val out = StringBuilder()
        val steps = mutableListOf<Step>()
        val byPara = mutableSetOf<Int>()
        val pics = mutableSetOf<Int>()
        iconIds.set(pics)
        current.set(t)
        val warn = mutableListOf<String>()
        var id = 2
        fun next() = id++
        val items = s.bullets.map { it.trimEnd() }.filter { it.isNotBlank() }
        val icons = s.icons.map { iconName(it) }
        s.icons.filterIndexed { i, n -> n.isNotBlank() && icons[i] == null }.takeIf { it.isNotEmpty() }?.let {
            warn += "第 $number 页：图标 ${it.joinToString("、")} 没有，已省掉"
        }
        fun iconAt(i: Int) = icons.getOrNull(i)
        val onPrimary = "FFFFFF"

        // 浅色页右上角一个淡淡的大圆，深色页也有，只是更暗：让页面不那么空
        fun decor() {
            out.append(shape(next(), W - 2300000, -1500000, 3600000, 3600000, "ellipse", t.soft, if (t.dark) 70 else 100, name = "!!Decor"))
        }
        fun header(title: String, iconName: String? = null) {
            var x = MX
            if (iconName != null) {
                out.append(shape(next(), MX, 430000, 640000, 640000, "ellipse", t.soft, name = "!!Title icon bg"))
                out.append(icon(next(), iconName, MX + 150000, 580000, 340000, t.accent))
                x = MX + 820000
            }
            val size = fit(listOf(title), W - x - MX - 1800000, 800000, 3200, 2200)
            out.append(text(next(), x, 360000, W - x - MX, 800000, listOf(para(listOf(Run(title, size, t.title, true)))), "ctr", "!!Title"))
            out.append(shape(next(), MX + 40000, 1230000, 720000, 54000, "rect", t.accent, name = "!!Title bar"))
        }
        fun pageNo() {
            out.append(text(next(), W - MX - 1200000, H - 560000, 1200000, 380000, listOf(para(listOf(Run("$number / $total", 1100, t.muted)), "r")), name = "!!Page"))
        }
        fun clip(list: List<String>, maxN: Int, what: String): List<String> {
            if (list.size > maxN) warn += "第 $number 页（${s.layout}）最多 $maxN 个$what，多出的 ${list.size - maxN} 个没放"
            return list.take(maxN)
        }
        fun small(size: Int, min: Int, label: String) {
            if (size <= min) warn += "第 $number 页$label 字太多，已经缩到最小字号，可能显得挤，建议精简"
        }

        val bg: String
        var chart: PptChart.Chart? = null
        val graphics = mutableSetOf<Int>()
        when (s.layout) {
            "title", "end" -> {
                bg = t.primary
                out.append(shape(next(), W - 4200000, -1900000, 6000000, 6000000, "ellipse", t.accent, 22, name = "!!Decor"))
                out.append(shape(next(), -1300000, H - 1700000, 3000000, 3000000, "ellipse", t.accent, 14, name = "!!Decor"))
                val title = s.title.ifBlank { if (s.layout == "end") "谢谢观看" else "演示文稿" }
                val tw = W - 2 * MX - 1200000
                val size = fit(listOf(title), tw, 1700000, if (s.layout == "end") 6000 else 5000, 3200)
                small(size, 3200, "标题")
                val anchorY = if (s.layout == "end") 2000000L else 1850000L
                out.append(text(next(), MX + 200000, anchorY, tw, 1700000, listOf(para(listOf(Run(title, size, onPrimary, true)))), "b", "!!Title"))
                out.append(shape(next(), MX + 290000, anchorY + 1820000, 900000, 60000, "rect", t.accent, name = "!!Title bar"))
                if (items.isNotEmpty()) {
                    val sub = items.take(3)
                    out.append(text(next(), MX + 200000, anchorY + 2000000, tw, 1500000,
                        sub.mapIndexed { i, v -> para(listOf(Run(v, if (i == 0) 2000 else 1600, if (i == 0) onPrimary else "E5E7EB")), before = if (i == 0) 0 else 300) }, name = "!!Subtitle"))
                }
            }
            "section" -> {
                bg = if (t.dark) t.bg else t.soft
                out.append(shape(next(), 0, 0, 220000, H, "rect", t.primary, name = "!!Side bar"))
                val no = "%02d".format(section)
                out.append(text(next(), MX + 300000, 1500000, 3400000, 3000000, listOf(para(listOf(Run(no, 13000, t.accent, true)))), "ctr", "!!Number"))
                val tx = MX + 3700000
                val tw = W - tx - MX
                val size = fit(listOf(s.title), tw, 1400000, 4400, 2800)
                out.append(text(next(), tx, 2000000, tw, 1400000, listOf(para(listOf(Run(s.title, size, t.title, true)))), "b", "!!Title"))
                out.append(shape(next(), tx + 90000, 3500000, 900000, 60000, "rect", t.accent, name = "!!Title bar"))
                items.firstOrNull()?.let { out.append(text(next(), tx, 3650000, tw, 900000, listOf(para(listOf(Run(it, 1800, t.muted)))), name = "!!Subtitle")) }
            }
            "agenda" -> {
                bg = t.bg; decor()
                header(s.title.ifBlank { "目录" })
                val list = clip(items, 8, "条目")
                val cols = if (list.size > 4) 2 else 1
                val rows = ceil(list.size / cols.toDouble()).toInt().coerceAtLeast(1)
                val top = 1650000L; val areaH = H - top - 700000
                val rowH = min(1150000L, areaH / rows)
                val colW = (W - 2 * MX) / cols
                val y0 = top + (areaH - rows * rowH) / 2
                list.forEachIndexed { i, v ->
                    val c = i / rows; val r = i % rows
                    val x = MX + c * colW; val y = y0 + r * rowH
                    val d = min(rowH - 200000, 720000L)
                    val circle = next()
                    out.append(shape(circle, x, y + (rowH - d) / 2, d, d, "ellipse", t.primary,
                        body = body(listOf(para(listOf(Run("%02d".format(i + 1), (d / 12700 * 40).toInt().coerceIn(1400, 2400), onPrimary, true)), "ctr")), "ctr", 0), name = "Number ${i + 1}"))
                    val (head, rest) = split(v)
                    val tid = next()
                    val ps = listOf(para(listOf(Run(head, 2400, t.title, true)))) + (if (rest.isNotEmpty()) listOf(para(listOf(Run(rest, 1600, t.muted)), before = 200)) else emptyList())
                    out.append(text(tid, x + d + 250000, y, colW - d - 400000, rowH, ps, "ctr", "Item ${i + 1}"))
                    steps += Step(listOf(circle to -1, tid to -1))
                }
                pageNo()
            }
            "cards", "stats", "process", "timeline" -> {
                bg = t.bg; decor()
                header(s.title)
                when (s.layout) {
                    "cards" -> {
                        val list = clip(items, 6, "卡片")
                        val n = list.size.coerceAtLeast(1)
                        val cols = if (n > 4) 3 else n
                        val rowsN = ceil(n / cols.toDouble()).toInt()
                        val gap = 280000L
                        val top = 1600000L
                        val cw = (W - 2 * MX - (cols - 1) * gap) / cols
                        val room = (H - top - 650000 - (rowsN - 1) * gap) / rowsN
                        val parts = list.map(::split)
                        val bodyW = cw - 400000
                        val top0 = if (rowsN == 1) 1700000L else 1250000L
                        val bodies = parts.map { it.second }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
                        val bodySize = fitEach(bodies, bodyW, (room - top0).coerceAtLeast(300000), if (rowsN == 1) 2000 else 1600, 1100)
                        small(bodySize, 1100, "卡片说明")
                        // 卡片高度跟着字走：字少就矮一点，不留一大截空白
                        val need = top0 + bodies.maxOf { lines(it, bodySize, bodyW) } * (bodySize / 100.0 * 12700 * 1.35).toLong() + 300000
                        val ch = need.coerceIn(minOf(2800000L, room), room)
                        val yTop = top + (room * rowsN + (rowsN - 1) * gap - (ch * rowsN + (rowsN - 1) * gap)) / 2
                        parts.forEachIndexed { i, (head, rest) ->
                            val x = MX + (i % cols) * (cw + gap); val y = yTop + (i / cols) * (ch + gap)
                            val ids = mutableListOf<Int>()
                            val card = next(); ids += card
                            out.append(shape(card, x, y, cw, ch, "roundRect", t.card, line = if (t.dark) null else t.soft, adj = "6000", shadow = !t.dark, name = "Card ${i + 1}"))
                            val iconSize = if (rowsN == 1) 760000L else 560000L
                            var ty = y + 260000
                            iconAt(i)?.let { ic ->
                                val b = next(); ids += b
                                out.append(shape(b, x + 200000, ty, iconSize, iconSize, "ellipse", if (t.dark) t.soft else t.soft, name = "Icon bg ${i + 1}"))
                                val ii = next(); ids += ii
                                out.append(icon(ii, ic, x + 200000 + iconSize / 4, ty + iconSize / 4, iconSize / 2, t.accent))
                                ty += iconSize + 160000
                            } ?: run {
                                val b = next(); ids += b
                                out.append(shape(b, x + 200000, ty, 520000, 520000, "ellipse", t.primary,
                                    body = body(listOf(para(listOf(Run("${i + 1}", 1800, onPrimary, true)), "ctr")), "ctr", 0), name = "Number ${i + 1}"))
                                ty += 520000 + 160000
                            }
                            val hid = next(); ids += hid
                            val headSize = fit(listOf(head), bodyW, 520000, 2200, 1400)
                            out.append(text(hid, x + 150000, ty, cw - 300000, 520000, listOf(para(listOf(Run(head, headSize, t.title, true)))), "t", "Card title ${i + 1}"))
                            if (rest.isNotEmpty()) {
                                val bid = next(); ids += bid
                                out.append(text(bid, x + 150000, ty + 520000, cw - 300000, y + ch - ty - 600000, listOf(para(listOf(Run(rest, bodySize, t.text)))), "t", "Card text ${i + 1}"))
                            }
                            steps += Step(ids.map { it to -1 })
                        }
                    }
                    "stats" -> {
                        val list = clip(items, 4, "数字")
                        val n = list.size.coerceAtLeast(1)
                        val gap = 300000L
                        val cw = (W - 2 * MX - (n - 1) * gap) / n
                        val ch = 3600000L; val top = 1550000L + (H - 1550000L - 600000 - ch) / 2
                        val parts = list.map(::split)
                        val numSize = fitEach(parts.map { it.first }, cw - 200000, 1400000, if (n <= 2) 8000 else 6400, 3200)
                        parts.forEachIndexed { i, (num, label) ->
                            val x = MX + i * (cw + gap)
                            val ids = mutableListOf<Int>()
                            val card = next(); ids += card
                            out.append(shape(card, x, top, cw, ch, "roundRect", t.card, line = if (t.dark) null else t.soft, adj = "5000", shadow = !t.dark, name = "Stat ${i + 1}"))
                            iconAt(i)?.let { ic -> val ii = next(); ids += ii; out.append(icon(ii, ic, x + cw / 2 - 230000, top + 300000, 460000, t.accent)) }
                            val nid = next(); ids += nid
                            out.append(text(nid, x + 100000, top + 800000, cw - 200000, 1400000, listOf(para(listOf(Run(num, numSize, t.primary.takeIf { !t.dark } ?: t.accent, true)), "ctr")), "ctr", "Number ${i + 1}"))
                            if (label.isNotEmpty()) {
                                val lid = next(); ids += lid
                                val ls = fit(listOf(label), cw - 300000, 1100000, 2000, 1200)
                                out.append(text(lid, x + 150000, top + 2300000, cw - 300000, 1100000, listOf(para(listOf(Run(label, ls, t.text)), "ctr")), "t", "Label ${i + 1}"))
                            }
                            steps += Step(ids.map { it to -1 })
                        }
                    }
                    "process" -> {
                        val list = clip(items, 5, "步骤")
                        val n = list.size.coerceAtLeast(1)
                        val top = 2300000L
                        val aw = (W - 2 * MX) / n
                        val ah = 1000000L
                        val parts = list.map(::split)
                        val bodySize = fitEach(parts.map { it.second }, aw - 200000, 2400000, 1900, 1100)
                        small(bodySize, 1100, "步骤说明")
                        parts.forEachIndexed { i, (head, rest) ->
                            val x = MX + i * aw
                            val ids = mutableListOf<Int>()
                            val a = next(); ids += a
                            val color = if (i % 2 == 0) t.primary else t.accent
                            val hs = fit(listOf(head), aw - 600000, ah - 100000, 2200, 1200)
                            out.append(shape(a, x, top, aw + 120000, ah, if (i == 0) "homePlate" else "chevron", color, adj = "30000",
                                body = body(listOf(para(listOf(Run(head, hs, onPrimary, true)), "ctr")), "ctr", 280000), name = "Step ${i + 1}"))
                            if (rest.isNotEmpty()) {
                                val b = next(); ids += b
                                out.append(text(b, x + 60000, top + ah + 250000, aw - 120000, 2500000, listOf(para(listOf(Run(rest, bodySize, t.text)), "ctr")), "t", "Step text ${i + 1}"))
                            }
                            steps += Step(ids.map { it to -1 })
                        }
                    }
                    else -> { // timeline
                        val list = clip(items, 6, "节点")
                        val n = list.size.coerceAtLeast(1)
                        val lineY = 3700000L
                        out.append(shape(next(), MX, lineY - 22000, W - 2 * MX, 44000, "rect", t.soft.takeIf { !t.dark } ?: t.muted, name = "Line"))
                        val seg = (W - 2 * MX) / n
                        val parts = list.map(::split)
                        val bodySize = fitEach(parts.map { it.second }, seg - 120000, 1300000, 1800, 1000)
                        small(bodySize, 1000, "时间线说明")
                        parts.forEachIndexed { i, (time, rest) ->
                            val cx = MX + seg * i + seg / 2
                            val ids = mutableListOf<Int>()
                            val dot = next(); ids += dot
                            out.append(shape(dot, cx - 130000, lineY - 130000, 260000, 260000, "ellipse", t.accent, line = t.bg, lineW = 38100, name = "Dot ${i + 1}"))
                            val up = i % 2 == 0
                            val tid = next(); ids += tid
                            out.append(text(tid, cx - seg / 2 + 40000, if (up) lineY - 900000 else lineY + 230000, seg - 80000, 700000,
                                listOf(para(listOf(Run(time, fit(listOf(time), seg - 120000, 600000, 2600, 1300), t.primary.takeIf { !t.dark } ?: t.accent, true)), "ctr")), if (up) "b" else "t", "Time ${i + 1}"))
                            if (rest.isNotEmpty()) {
                                val bid = next(); ids += bid
                                out.append(text(bid, cx - seg / 2 + 40000, if (up) lineY - 2300000 else lineY + 950000, seg - 80000, 1400000,
                                    listOf(para(listOf(Run(rest, bodySize, t.text)), "ctr")), if (up) "b" else "t", "Event ${i + 1}"))
                            }
                            steps += Step(ids.map { it to -1 })
                        }
                    }
                }
                pageNo()
            }
            "two_column" -> {
                bg = t.bg; decor()
                header(s.title)
                // 「## 标题」开一栏；没写就对半分
                val groups = mutableListOf<Pair<String, MutableList<String>>>()
                items.forEach { v -> if (v.startsWith("#")) groups += v.trimStart('#').trim() to mutableListOf() else (groups.lastOrNull()?.second ?: run { groups += "" to mutableListOf(); groups.last().second }) += v }
                val cols = if (groups.size >= 2) groups.take(2) else {
                    val all = groups.flatMap { it.second }; val h = (all.size + 1) / 2
                    listOf("" to all.take(h).toMutableList(), "" to all.drop(h).toMutableList())
                }
                if (groups.size > 2) warn += "第 $number 页（two_column）只放两栏，多出的没放"
                val gap = 420000L; val top = 1600000L
                val cw = (W - 2 * MX - gap) / 2; val room = H - top - 650000
                val listSize = cols.minOf { (_, l) -> fit(l.ifEmpty { listOf("") }, cw - 500000, room - 900000, 2200, 1200, spacing = 0.6) }
                small(listSize, 1200, "两栏内容")
                val lineH = (listSize / 100.0 * 12700 * 1.4).toLong()
                val need = 1100000 + cols.maxOf { (_, l) -> l.sumOf { lines(it, listSize, cw - 500000) } * lineH + l.size * 114300 }
                val ch = need.coerceIn(minOf(3000000L, room), room)
                val yTop = top + (room - ch) / 2
                cols.forEachIndexed { i, (head, list) ->
                    val x = MX + i * (cw + gap)
                    val color = if (i == 0) t.primary else t.accent
                    val ids = mutableListOf<Int>()
                    val card = next(); ids += card
                    out.append(shape(card, x, yTop, cw, ch, "roundRect", t.card, line = if (t.dark) null else t.soft, adj = "4000", shadow = !t.dark, name = "Column ${i + 1}"))
                    val hb = next(); ids += hb
                    out.append(shape(hb, x, yTop, cw, 760000, "round2SameRect", color, adj = "22000",
                        body = body(listOf(para(listOf(Run(head.ifBlank { if (i == 0) "A" else "B" }, 2200, onPrimary, true)), "ctr")), "ctr"), name = "Column title ${i + 1}"))
                    iconAt(i)?.let { ic -> val ii = next(); ids += ii; out.append(icon(ii, ic, x + 220000, yTop + 190000, 380000, onPrimary)) }
                    val lid = next(); ids += lid
                    out.append(text(lid, x + 200000, yTop + 900000, cw - 400000, ch - 1000000,
                        list.mapIndexed { j, v -> para(listOf(Run(v.trim(), listSize, t.text)), bullet = "•", bulletColor = color, before = if (j == 0) 0 else 900) }, "t", "Column text ${i + 1}"))
                    steps += Step(ids.map { it to -1 })
                }
                pageNo()
            }
            "chart" -> {
                bg = t.bg; decor()
                header(s.title)
                val problems = mutableListOf<String>()
                chart = s.chart?.let { PptChart.clean(it, problems) }
                problems.forEach { warn += "第 $number 页（chart）：$it" }
                if (s.chart == null) warn += "第 $number 页是 chart 版式，但没给 chart 数据"
                // 右边有要点时图占六成多，没有就整页
                val list = clip(items, 4, "要点")
                val chartW = if (list.isEmpty()) W - 2 * MX else (W * 0.6).toLong()
                val top = 1500000L; val chartH = H - top - 700000
                chart?.let { c ->
                    val gid = next(); graphics += gid
                    out.append("""<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="$gid" name="Chart $gid"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp="1"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>""" +
                        """<p:xfrm><a:off x="$MX" y="$top"/><a:ext cx="$chartW" cy="$chartH"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">""" +
                        """<c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId3"/></a:graphicData></a:graphic></p:graphicFrame>""")
                    steps += Step(listOf(gid to -1))
                    // 单位放在图的右上角，不压标题下的短线
                    if (c.unit.isNotEmpty()) out.append(text(next(), MX, top - 260000, chartW, 300000, listOf(para(listOf(Run("单位：${c.unit}", 1200, t.muted)), "r")), name = "Unit"))
                }
                if (list.isNotEmpty()) {
                    val x = MX + chartW + 300000
                    val w = W - x - MX
                    val card = next()
                    val size = fit(list, w - 500000, chartH - 500000, 2000, 1300, spacing = 0.7)
                    small(size, 1300, "图表旁的要点")
                    // 卡片高度跟着字走，和图竖着居中
                    val lineH = (size / 100.0 * 12700 * 1.4).toLong()
                    val ch = (500000 + list.sumOf { lines(it, size, w - 500000) } * lineH + (list.size - 1) * lineH * 7 / 10).coerceIn(minOf(1800000L, chartH), chartH)
                    val cy = top + (chartH - ch) / 2
                    out.append(shape(card, x, cy, w, ch, "roundRect", t.card, line = if (t.dark) null else t.soft, adj = "6000", shadow = !t.dark, name = "Takeaways"))
                    val tid = next()
                    out.append(text(tid, x + 200000, cy + 250000, w - 400000, ch - 500000, list.mapIndexed { j, v ->
                        val (head, rest) = split(v)
                        val runs = if (rest.isNotEmpty()) listOf(Run("$head：", size, t.title, true), Run(rest, size, t.text)) else listOf(Run(v, size, t.text))
                        para(runs, bullet = "•", bulletColor = t.accent, before = if (j == 0) 0 else 1000)
                    }, "t", "Takeaways text"))
                    byPara += tid
                    list.indices.forEach { steps += Step(listOf(tid to it)) }
                    steps.add(steps.size - list.size, Step(listOf(card to -1)))
                }
                pageNo()
            }
            "quote" -> {
                bg = if (t.dark) t.bg else t.soft
                out.append(text(next(), MX + 200000, 500000, 2000000, 2000000, listOf(para(listOf(Run("“", 16000, t.accent, true)))), name = "Quote mark"))
                val q = s.title
                val size = fit(listOf(q), W - 2 * MX - 1200000, 2800000, 3600, 2000)
                small(size, 2000, "引言")
                out.append(text(next(), MX + 600000, 1700000, W - 2 * MX - 1200000, 2800000, listOf(para(listOf(Run(q, size, t.title, true)), "ctr")), "ctr", "Quote"))
                items.firstOrNull()?.let {
                    out.append(text(next(), MX + 600000, 4700000, W - 2 * MX - 1200000, 700000, listOf(para(listOf(Run("—— " + it.removePrefix("——").trim(), 1800, t.muted)), "r")), name = "Source"))
                }
            }
            "image" -> {
                if (s.image != null) {
                    bg = "000000"
                    val pid = next(); pics += pid
                    out.append(picture(pid, "rId2", 0, 0, W, H, s.imageW, s.imageH, cover = true, round = false))
                    out.append(shape(next(), 0, 0, W, H, "rect", "000000", 42, name = "Shade"))
                } else bg = t.primary
                val size = fit(listOf(s.title), W - 2 * MX - 2000000, 1600000, 5000, 3000)
                out.append(text(next(), MX + 200000, H - 3100000, W - 2 * MX - 2000000, 1600000, listOf(para(listOf(Run(s.title, size, onPrimary, true)))), "b", "!!Title"))
                out.append(shape(next(), MX + 290000, H - 1400000, 900000, 60000, "rect", t.accent, name = "!!Title bar"))
                items.firstOrNull()?.let { out.append(text(next(), MX + 200000, H - 1250000, W - 2 * MX - 2000000, 800000, listOf(para(listOf(Run(it, 1800, "F3F4F6")))), name = "!!Subtitle")) }
            }
            else -> { // content
                bg = t.bg; decor()
                header(s.title, iconAt(0))
                val list = clip(items.ifEmpty { listOf(" ") }, 8, "要点")
                val withImage = s.image != null
                val textW = if (withImage) (W * 0.5).toLong() else W - 2 * MX
                val areaH = H - 1550000 - 700000
                val texts = list.map { it.trim() }
                val size = fit(texts, textW - 500000, areaH, if (withImage) 2200 else 2400, 1400, spacing = 0.6)
                small(size, 1400, "要点")
                val tid = next()
                out.append(text(tid, MX, 1550000, textW, areaH, list.mapIndexed { j, v ->
                    val level = if (v.startsWith("  ")) 1 else 0
                    val (head, rest) = split(v.trim().removePrefix("- ").removePrefix("• "))
                    val runs = if (rest.isNotEmpty() && head.length <= 12) listOf(Run("$head：", if (level == 1) size - 300 else size, t.title, true), Run(rest, if (level == 1) size - 300 else size, t.text))
                        else listOf(Run(v.trim().removePrefix("- ").removePrefix("• "), if (level == 1) size - 300 else size, t.text))
                    para(runs, bullet = if (level == 0) "•" else "–", bulletColor = t.accent, level = level, before = if (j == 0) 0 else if (level == 0) 1000 else 300)
                }, "t", "Body"))
                byPara += tid
                list.indices.forEach { steps += Step(listOf(tid to it)) }
                if (withImage) {
                    val pid = next(); pics += pid
                    out.append(picture(pid, "rId2", (W * 0.56).toLong(), 1550000, (W * 0.39).toLong(), H - 2350000, s.imageW, s.imageH, cover = false, round = true))
                    steps += Step(listOf(pid to -1))
                }
                pageNo()
            }
        }
        iconIds.remove()
        current.remove()
        return Rendered(bg, out.toString(), steps, byPara, pics.toSet(), warn, graphics, chart)
    }

    // ---------- 转场和动画 ----------

    fun transition(kind: String): String = when (kind) {
        "fade" -> """<p:transition spd="med"><p:fade/></p:transition>"""
        "push" -> """<p:transition spd="med"><p:push dir="u"/></p:transition>"""
        "wipe" -> """<p:transition spd="med"><p:wipe dir="r"/></p:transition>"""
        "split" -> """<p:transition spd="med"><p:split orient="vert" dir="out"/></p:transition>"""
        "cover" -> """<p:transition spd="med"><p:cover dir="l"/></p:transition>"""
        "zoom" -> """<p:transition spd="med"><p:zoom/></p:transition>"""
        // 平滑：前后两页同名（!! 开头）的形状自动移动、变形；老版本不认就退成淡入
        "morph" -> """<mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"><mc:Choice xmlns:p159="http://schemas.microsoft.com/office/powerpoint/2015/09/main" Requires="p159">""" +
            """<p:transition spd="slow" xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main" p14:dur="1250"><p159:morph option="byObject"/></p:transition></mc:Choice>""" +
            """<mc:Fallback><p:transition spd="slow"><p:fade/></p:transition></mc:Fallback></mc:AlternateContent>"""
        else -> ""
    }

    /** 点一下出现一步。fade 淡入、rise 淡入并往上浮一点、wipe 从左往右擦出来。 */
    fun timing(steps: List<Step>, kind: String, byParagraph: Set<Int>, noBuild: Set<Int>, graphics: Set<Int> = emptySet()): String {
        if (kind == "none" || kind !in ANIMATIONS || steps.isEmpty()) return ""
        var n = 2
        fun id() = ++n
        fun tgt(spid: Int, p: Int) = if (p < 0) """<p:spTgt spid="$spid"/>""" else """<p:spTgt spid="$spid"><p:txEl><p:pRg st="$p" end="$p"/></p:txEl></p:spTgt>"""
        val (preset, sub) = when (kind) { "rise" -> 42 to 0; "wipe" -> 22 to 8; else -> 10 to 0 }
        val body = steps.joinToString("") { step ->
            val outer = id(); val inner = id()
            val effects = step.targets.mapIndexed { i, (spid, p) ->
                val t = tgt(spid, p)
                val e = id(); val set = id(); val fx = id()
                val extra = when (kind) {
                    "rise" -> {
                        val a = id(); val b = id()
                        """<p:anim calcmode="lin" valueType="num"><p:cBhvr><p:cTn id="$a" dur="600" decel="100000"/><p:tgtEl>$t</p:tgtEl><p:attrNameLst><p:attrName>ppt_x</p:attrName></p:attrNameLst></p:cBhvr><p:tavLst><p:tav tm="0"><p:val><p:strVal val="#ppt_x"/></p:val></p:tav><p:tav tm="100000"><p:val><p:strVal val="#ppt_x"/></p:val></p:tav></p:tavLst></p:anim>""" +
                            """<p:anim calcmode="lin" valueType="num"><p:cBhvr><p:cTn id="$b" dur="600" decel="100000"/><p:tgtEl>$t</p:tgtEl><p:attrNameLst><p:attrName>ppt_y</p:attrName></p:attrNameLst></p:cBhvr><p:tavLst><p:tav tm="0"><p:val><p:strVal val="#ppt_y+.1"/></p:val></p:tav><p:tav tm="100000"><p:val><p:strVal val="#ppt_y"/></p:val></p:tav></p:tavLst></p:anim>"""
                    }
                    else -> ""
                }
                val filter = if (kind == "wipe") "wipe(left)" else "fade"
                """<p:par><p:cTn id="$e" presetID="$preset" presetClass="entr" presetSubtype="$sub" fill="hold" grpId="0" nodeType="${if (i == 0) "clickEffect" else "withEffect"}"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>""" +
                    """<p:set><p:cBhvr><p:cTn id="$set" dur="1" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst></p:cTn><p:tgtEl>$t</p:tgtEl><p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="visible"/></p:to></p:set>""" +
                    """<p:animEffect transition="in" filter="$filter"><p:cBhvr><p:cTn id="$fx" dur="${if (kind == "rise") 600 else 500}"/><p:tgtEl>$t</p:tgtEl></p:cBhvr></p:animEffect>$extra</p:childTnLst></p:cTn></p:par>"""
            }.joinToString("")
            """<p:par><p:cTn id="$outer" fill="hold"><p:stCondLst><p:cond delay="indefinite"/></p:stCondLst><p:childTnLst><p:par><p:cTn id="$inner" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>$effects</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>"""
        }
        val shapes = steps.flatMap { it.targets.map { t -> t.first } }.distinct()
        val bld = shapes.filter { it !in noBuild }.joinToString("") { spid ->
            when (spid) {
                in byParagraph -> """<p:bldP spid="$spid" grpId="0" build="p"/>"""
                in graphics -> """<p:bldGraphic spid="$spid" grpId="0"><p:bldAsOne/></p:bldGraphic>"""
                else -> """<p:bldP spid="$spid" grpId="0" animBg="1"/>"""
            }
        }
        return """<p:timing><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst><p:seq concurrent="1" nextAc="seek"><p:cTn id="2" dur="indefinite" nodeType="mainSeq"><p:childTnLst>$body</p:childTnLst></p:cTn>""" +
            """<p:prevCondLst><p:cond evt="onPrev" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:prevCondLst><p:nextCondLst><p:cond evt="onNext" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:nextCondLst></p:seq></p:childTnLst></p:cTn></p:par></p:tnLst>""" +
            (if (bld.isEmpty()) "" else "<p:bldLst>$bld</p:bldLst>") + "</p:timing>"
    }
}
