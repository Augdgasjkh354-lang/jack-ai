package com.jack.ai.data

import java.io.OutputStream
import kotlin.math.ceil

/**
 * Word 文档：Markdown 进来，出来的是排好版的 .docx。
 * 可选封面、自动目录（带页码，Word 打开时会请求更新成准确的）、带编号的标题样式、页眉页脚（第 X 页 / 共 Y 页）、
 * 表头着色、隔行浅底的表格。配色和 PPT 用同一套（[PptDeck.THEMES]）。
 * 还能插原生图表（```chart 代码块写 JSON，双击能改数）和图片（![说明](来源)，来源由调用方先下好、读好放进 images）。
 */
object WordDoc {
    data class Cover(val title: String = "", val subtitle: String = "", val author: String = "", val organization: String = "", val date: String = "")

    data class Options(
        val theme: String = "blue",
        /** 有就加封面页。 */
        val cover: Cover? = null,
        /** 要不要目录；null 是自动：章节（##）有四个以上就加。 */
        val toc: Boolean? = null,
        /** 页眉文字；null 用文档标题，空字符串不要页眉。 */
        val header: String? = null,
        val pageNumbers: Boolean = true,
        /** 标题编号：none 不编号、decimal 是 1 / 1.1 / 1.1.1、chinese 是 一、/（一）/ 1.。 */
        val headingNumbers: String = "none",
        /** 正文首行缩进两个字（公文、论文常用）。 */
        val indent: Boolean = false,
        /** modern：全文微软雅黑；classic：正文宋体、标题黑体（正式文件常用）。 */
        val fontStyle: String = "modern",
    )

    /** 一张图：jpeg 或 png 的字节和像素尺寸。 */
    class Image(val bytes: ByteArray, val ext: String, val w: Int, val h: Int)

    /** Markdown 里单独一行的图片：![说明](来源)。 */
    val IMAGE_LINE = Regex("""^!\[([^\]]*)]\(([^)]+)\)$""")

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val NS = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""
    /** A4、上下左右各 2.54 厘米：正文宽 9026 缇。 */
    private const val TEXT_W = 9026
    /** 估页码用：一页大约多少行、一行大约多少个中文字（正文 11 号字、1.5 倍行距）。 */
    private const val PAGE_LINES = 40.0
    private const val LINE_CHARS = 40.0

    private fun esc(s: String) = OfficeWriter.escXml(s)

    // ---------- 一段字 ----------

    /** **粗体**、*斜体*、`代码` 转成 run。 */
    internal fun runs(text: String, bold: Boolean = false, color: String? = null, size: Int? = null): String {
        val sb = StringBuilder()
        val re = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*(?!\s)(.+?)\*""")
        var last = 0
        fun run(t: String, b: Boolean, i: Boolean, code: Boolean) {
            if (t.isEmpty()) return
            sb.append("<w:r><w:rPr>")
            if (code) sb.append("""<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:shd w:val="clear" w:color="auto" w:fill="F3F4F6"/>""")
            if (b) sb.append("<w:b/><w:bCs/>")
            if (i) sb.append("<w:i/>")
            if (color != null) sb.append("""<w:color w:val="$color"/>""")
            if (size != null) sb.append("""<w:sz w:val="$size"/><w:szCs w:val="$size"/>""")
            sb.append("""</w:rPr><w:t xml:space="preserve">""").append(esc(t)).append("</w:t></w:r>")
        }
        for (m in re.findAll(text)) {
            run(text.substring(last, m.range.first), bold, false, false)
            when {
                m.groups[1] != null -> run(m.groupValues[1], true, false, false)
                m.groups[2] != null -> run(m.groupValues[2], bold, false, true)
                else -> run(m.groupValues[3], bold, true, false)
            }
            last = m.range.last + 1
        }
        run(text.substring(last), bold, false, false)
        return sb.toString()
    }

    private fun p(content: String, style: String? = null, ppr: String = "") =
        "<w:p>" + (if (style != null || ppr.isNotEmpty()) "<w:pPr>" + (style?.let { """<w:pStyle w:val="$it"/>""" } ?: "") + ppr + "</w:pPr>" else "") + content + "</w:p>"

    private fun field(instr: String, cached: String) =
        """<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> $instr </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>${esc(cached)}</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>"""

    /** 估这段字占几行。 */
    private fun lines(text: String, perLine: Double = LINE_CHARS): Double {
        var w = 0.0
        for (ch in text) w += if (ch.code >= 0x2E80) 1.0 else 0.55
        return ceil(w / perLine).coerceAtLeast(1.0)
    }

    // ---------- 表格 ----------

    private fun table(rows: List<List<String>>, t: PptDeck.Theme): String {
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        // 列宽按字数分：长的列宽一点，但每列至少给一份
        val weights = (0 until cols).map { c -> rows.maxOf { r -> r.getOrElse(c) { "" }.fold(0) { a, ch -> a + if (ch.code >= 0x2E80) 2 else 1 } }.coerceIn(4, 40).toDouble() }
        val total = weights.sum()
        val widths = weights.map { (it / total * TEXT_W).toInt() }
        val numeric = Regex("""^[-+]?[¥$€]?\d[\d,]*(\.\d+)?%?$""")
        val line = "E5E7EB"
        val sb = StringBuilder("""<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>""")
        sb.append("""<w:top w:val="single" w:sz="8" w:space="0" w:color="${t.primary}"/><w:bottom w:val="single" w:sz="8" w:space="0" w:color="${t.primary}"/>""")
        sb.append("""<w:insideH w:val="single" w:sz="4" w:space="0" w:color="$line"/></w:tblBorders>""")
        sb.append("""<w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="80" w:type="dxa"/><w:left w:w="140" w:type="dxa"/><w:bottom w:w="80" w:type="dxa"/><w:right w:w="140" w:type="dxa"/></w:tblCellMar><w:tblLook w:val="04A0" w:firstRow="1" w:lastRow="0" w:firstColumn="0" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/></w:tblPr><w:tblGrid>""")
        widths.forEach { sb.append("""<w:gridCol w:w="$it"/>""") }
        sb.append("</w:tblGrid>")
        rows.forEachIndexed { r, row ->
            // 表头每页重复；隔行浅底
            sb.append("<w:tr>").append(if (r == 0) "<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>" else "<w:trPr><w:cantSplit/></w:trPr>")
            for (c in 0 until cols) {
                val cell = row.getOrElse(c) { "" }
                val fill = when { r == 0 -> t.primary; r % 2 == 0 -> t.card; else -> null }
                sb.append("""<w:tc><w:tcPr><w:tcW w:w="${widths[c]}" w:type="dxa"/>""")
                if (fill != null) sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="$fill"/>""")
                sb.append("""<w:vAlign w:val="center"/></w:tcPr>""")
                val align = when { r == 0 -> "center"; numeric.matches(cell.trim()) -> "right"; else -> "left" }
                sb.append(p(if (r == 0) runs(cell, bold = true, color = "FFFFFF") else runs(cell), "TableText", """<w:jc w:val="$align"/>"""))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        sb.append(p("", ppr = """<w:spacing w:before="0" w:after="120"/>"""))
        return sb.toString()
    }

    private fun splitRow(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    // ---------- 整份 ----------

    private class Heading(val level: Int, val text: String, val bookmark: String, val page: Int)

    /** 正文宽度能放多宽的图（EMU）：6 英寸左右，正好占满一行。 */
    private const val MAX_W = 5486400L

    /** 返回生成时发现的问题（图表数据不对、图片没找到），给模型看。 */
    fun write(markdown: String, out: OutputStream, o: Options = Options(), images: Map<String, Image> = emptyMap()): List<String> {
        val warnings = mutableListOf<String>()
        val charts = mutableListOf<PptChart.Chart>()
        val pics = mutableListOf<Image>()
        var figure = 0
        var drawingId = 0
        val t = PptDeck.theme(o.theme).let { if (it.dark) PptDeck.theme("blue").copy(primary = it.accent) else it }  // Word 是白纸，深色主题只借强调色
        val lines = markdown.replace("\r\n", "\n").split("\n")
        // 第一个「# 」是文档标题
        val titleIdx = lines.indexOfFirst { it.trim().startsWith("# ") }
        val docTitle = (if (titleIdx >= 0) lines[titleIdx].trim().removePrefix("# ").trim() else "").ifEmpty { o.cover?.title.orEmpty() }
        val headingCount = lines.count { val s = it.trim(); s.startsWith("## ") || (s.startsWith("# ") && lines.indexOf(it) != titleIdx) }
        val withToc = o.toc ?: (headingCount >= 4)
        val cover = o.cover
        // 没有封面但有目录：标题放在目录那页顶上，正文里就不再写一遍
        val titleOnToc = cover == null && withToc && docTitle.isNotEmpty()

        val body = StringBuilder()
        val headings = mutableListOf<Heading>()
        var used = 0.0
        val counters = IntArray(3)
        var bm = 0
        fun number(level: Int): String {
            counters[level - 1]++
            for (k in level until 3) counters[k] = 0
            return when (o.headingNumbers) {
                "decimal" -> counters.take(level).joinToString(".") + " "
                "chinese" -> when (level) { 1 -> chineseNumber(counters[0]) + "、"; 2 -> "（" + chineseNumber(counters[1]) + "）"; else -> "${counters[2]}. " }
                else -> ""
            }
        }
        fun heading(level: Int, raw: String) {
            val text = number(level) + raw.trim()
            bm++
            val name = "_Toc$bm"
            headings += Heading(level, text, name, (used / PAGE_LINES).toInt() + 1)
            body.append(p("""<w:bookmarkStart w:id="$bm" w:name="$name"/>""" + runs(text) + """<w:bookmarkEnd w:id="$bm"/>""", "Heading$level"))
            used += if (level == 1) 3.0 else 2.2
        }
        fun caption(text: String) {
            figure++
            body.append(p(runs("图 $figure" + (if (text.isNotBlank()) "  " + text.trim() else "")), "Caption"))
            used += 1.2
        }
        fun inline(cx: Long, cy: Long, graphic: String) = """<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:effectExtent l="0" t="0" r="0" b="0"/>""" +
            """<wp:docPr id="${++drawingId}" name="Drawing $drawingId"/><wp:cNvGraphicFramePr/><a:graphic>$graphic</a:graphic></wp:inline></w:drawing></w:r>"""
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val s = line.trim()
            when {
                // 图表：```chart 里写 JSON（type、title、categories、series、unit）
                s.startsWith("```chart") -> {
                    val json = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { json.append(lines[i]).append('\n'); i++ }
                    val raw = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json.toString()) as kotlinx.serialization.json.JsonObject }.getOrNull()
                    val problems = mutableListOf<String>()
                    val c = raw?.let { PptChart.fromJson(it) }?.let { PptChart.clean(it, problems) }
                    problems.forEach { warnings += "图表「${raw?.get("title")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()}」：$it" }
                    if (c == null) { if (raw == null) warnings += "有个 chart 代码块不是有效的 JSON，没画出来"; i++; continue }
                    charts += c
                    val cy = if (c.type in setOf("pie", "doughnut")) 3000000L else 3200400L
                    body.append(p(inline(MAX_W, cy, """<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rIdC${charts.size}"/></a:graphicData>"""),
                        "Figure"))
                    used += cy / 12700.0 / 17
                    caption(c.title + if (c.unit.isNotBlank()) "（单位：${c.unit}）" else "")
                }
                IMAGE_LINE.matches(s) -> {
                    val m = IMAGE_LINE.find(s)!!
                    val alt = m.groupValues[1]; val src = m.groupValues[2].trim()
                    val img = images[src]
                    if (img == null) warnings += "图片「${alt.ifBlank { src }}」没找到，没放进去"
                    else {
                        pics += img
                        val n = pics.size
                        val ratio = if (img.w > 0 && img.h > 0) img.h.toDouble() / img.w else 0.66
                        // 竖的图别太高：最高大半页
                        var cx = MAX_W; var cy = (cx * ratio).toLong()
                        if (cy > 5400000L) { cy = 5400000L; cx = (cy / ratio).toLong() }
                        body.append(p(inline(cx, cy, """<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$n" name="image$n.${img.ext}"/><pic:cNvPicPr/></pic:nvPicPr>""" +
                            """<pic:blipFill><a:blip r:embed="rIdI$n"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData>"""), "Figure"))
                        used += cy / 12700.0 / 17
                        if (alt.isNotBlank()) caption(alt)
                    }
                }
                i == titleIdx -> if (cover == null && !titleOnToc && docTitle.isNotEmpty()) { body.append(p(runs(docTitle), "Title")); used += 3 }
                s.startsWith("```") -> {
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { body.append(p(runs(lines[i].ifEmpty { " " }), "Code")); used += 1; i++ }
                }
                s.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("""\|?\s*:?-{2,}.*""")) -> {
                    val rows = mutableListOf(splitRow(s))
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += splitRow(lines[i].trim()); i++ }
                    body.append(table(rows, t))
                    used += rows.sumOf { r -> r.maxOf { lines(it, LINE_CHARS / r.size.coerceAtLeast(1)) } * 1.3 } + 1
                    continue
                }
                s.startsWith("# ") -> heading(1, s.removePrefix("# "))
                s.startsWith("## ") -> heading(1, s.removePrefix("## "))
                s.startsWith("### ") -> heading(2, s.removePrefix("### "))
                s.startsWith("#### ") -> heading(3, s.removePrefix("#### "))
                s.matches(Regex("""[-*+]\s+.*""")) -> {
                    val level = (line.length - line.trimStart().length) / 2
                    val text = s.substring(1).trim()
                    body.append(p("""<w:r><w:rPr><w:color w:val="${t.accent}"/></w:rPr><w:t xml:space="preserve">•</w:t></w:r><w:r><w:tab/></w:r>""" + runs(text), "ListPara",
                        """<w:tabs><w:tab w:val="left" w:pos="${420 + level * 420}"/></w:tabs><w:ind w:left="${420 + level * 420}" w:hanging="300"/>"""))
                    used += lines(text) + 0.2
                }
                s.matches(Regex("""\d+[.)、]\s*.*""")) -> {
                    val level = (line.length - line.trimStart().length) / 2
                    body.append(p(runs(s), "ListPara", """<w:ind w:left="${420 + level * 420}" w:hanging="420"/>"""))
                    used += lines(s) + 0.2
                }
                s.startsWith(">") -> { body.append(p(runs(s.removePrefix(">").trim()), "Quote")); used += lines(s) + 0.8 }
                s.matches(Regex("""-{3,}|\*{3,}""")) -> { body.append(p("", "Rule")); used += 1 }
                s.isEmpty() -> {}
                else -> { body.append(p(runs(s), if (o.indent) "BodyIndent" else null)); used += lines(s) + 0.35 }
            }
            i++
        }

        // ---------- 封面和目录 ----------
        val front = StringBuilder()
        if (cover != null) {
            val title = cover.title.ifBlank { docTitle }
            front.append(p("", ppr = """<w:spacing w:before="1600" w:after="0"/>"""))
            // 一整条主色的横幅，标题压在上面
            front.append("""<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders><w:top w:val="nil"/><w:left w:val="nil"/><w:bottom w:val="nil"/><w:right w:val="nil"/><w:insideH w:val="nil"/><w:insideV w:val="nil"/></w:tblBorders><w:tblCellMar><w:left w:w="480" w:type="dxa"/><w:right w:w="480" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="$TEXT_W"/></w:tblGrid>""")
            front.append("""<w:tr><w:trPr><w:trHeight w:val="3600" w:hRule="atLeast"/></w:trPr><w:tc><w:tcPr><w:tcW w:w="$TEXT_W" w:type="dxa"/><w:shd w:val="clear" w:color="auto" w:fill="${t.primary}"/><w:vAlign w:val="center"/></w:tcPr>""")
            front.append(p(runs(title, bold = true, color = "FFFFFF"), "CoverTitle"))
            if (cover.subtitle.isNotBlank()) front.append(p(runs(cover.subtitle, color = "FFFFFF"), "CoverSubtitle"))
            front.append("</w:tc></w:tr></w:tbl>")
            front.append(p("", ppr = """<w:pBdr><w:bottom w:val="single" w:sz="36" w:space="1" w:color="${t.accent}"/></w:pBdr><w:spacing w:before="0" w:after="0"/><w:ind w:right="${TEXT_W * 2 / 3}"/>"""))
            front.append(p("", ppr = """<w:spacing w:before="4200" w:after="0"/>"""))
            listOf(cover.author, cover.organization, cover.date).filter { it.isNotBlank() }.forEach { front.append(p(runs(it), "CoverInfo")) }
        }
        if (withToc && headings.isNotEmpty()) {
            if (cover != null) front.append(p("""<w:r><w:br w:type="page"/></w:r>"""))
            if (titleOnToc) front.append(p(runs(docTitle), "Title"))
            front.append(p(runs("目录"), "TOCHeading"))
            front.append(p("""<w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r><w:r><w:instrText xml:space="preserve"> TOC \o "1-3" \h \z \u </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>"""))
            headings.forEach { h ->
                front.append(p("""<w:hyperlink w:anchor="${h.bookmark}" w:history="1">""" + runs(h.text) +
                    """<w:r><w:tab/></w:r>""" + field("PAGEREF ${h.bookmark} \\h", "${h.page}") + "</w:hyperlink>", "TOC${h.level}"))
            }
            front.append(p("""<w:r><w:fldChar w:fldCharType="end"/></w:r>"""))
        }

        // 页眉页脚只在正文那一节：封面、目录不要，正文从第 1 页数起
        val headerText = o.header ?: docTitle
        val hasHeader = headerText.isNotBlank()
        val refs = (if (hasHeader) """<w:headerReference w:type="default" r:id="rId10"/>""" else "") +
            (if (o.pageNumbers) """<w:footerReference w:type="default" r:id="rId11"/>""" else "")
        val page = """<w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>"""
        val doc = StringBuilder()
        if (front.isNotEmpty()) {
            doc.append(front)
            doc.append(p("", ppr = """<w:sectPr>$page</w:sectPr>"""))
        }
        doc.append(body)
        val document = """$XML<w:document $NS><w:body>$doc<w:sectPr>$refs$page<w:pgNumType w:start="1"/></w:sectPr></w:body></w:document>"""

        val headerXml = """$XML<w:hdr $NS>""" + p(runs(headerText, color = "8C8C8C", size = 18), "Header",
            """<w:pBdr><w:bottom w:val="single" w:sz="4" w:space="4" w:color="${t.soft}"/></w:pBdr><w:jc w:val="right"/>""") + "</w:hdr>"
        // 有封面目录时正文从 1 数，「共几页」只有 Word 算得对（LibreOffice、WPS 不认 SECTIONPAGES），就只写「第 X 页」
        fun gray(text: String) = """<w:r><w:rPr><w:color w:val="8C8C8C"/><w:sz w:val="18"/></w:rPr><w:t xml:space="preserve">$text</w:t></w:r>"""
        val footerXml = """$XML<w:ftr $NS>""" + p(
            gray("第 ") + field("PAGE", "1") + (if (front.isEmpty()) gray(" 页 / 共 ") + field("NUMPAGES", "1") + gray(" 页") else gray(" 页")),
            "Footer", """<w:jc w:val="center"/>""") + "</w:ftr>"

        val classic = o.fontStyle == "classic"
        val bodyFont = if (classic) "宋体" else "微软雅黑"
        val headFont = if (classic) "黑体" else "微软雅黑"
        fun style(id: String, name: String, ppr: String, rpr: String, next: String = "Normal", outline: Int = -1) =
            """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="$next"/><w:qFormat/><w:pPr>$ppr${if (outline >= 0) """<w:outlineLvl w:val="$outline"/>""" else ""}</w:pPr><w:rPr>$rpr</w:rPr></w:style>"""
        val hf = """<w:rFonts w:ascii="$headFont" w:hAnsi="$headFont" w:eastAsia="$headFont"/>"""
        val styles = XML + """<w:styles $NS><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$bodyFont" w:hAnsi="$bodyFont" w:eastAsia="$bodyFont" w:cs="$bodyFont"/><w:color w:val="${t.text}"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="360" w:lineRule="auto"/><w:jc w:val="both"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            style("Title", "Title", """<w:keepNext/><w:spacing w:before="240" w:after="360"/><w:jc w:val="center"/>""", """$hf<w:b/><w:color w:val="${t.title}"/><w:sz w:val="40"/><w:szCs w:val="40"/>""") +
            style("Heading1", "heading 1", """<w:keepNext/><w:keepLines/><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="4" w:color="${t.soft}"/></w:pBdr><w:spacing w:before="480" w:after="200" w:line="240" w:lineRule="auto"/><w:jc w:val="left"/>""", """$hf<w:b/><w:color w:val="${t.primary}"/><w:sz w:val="32"/><w:szCs w:val="32"/>""", outline = 0) +
            style("Heading2", "heading 2", """<w:keepNext/><w:keepLines/><w:spacing w:before="320" w:after="120" w:line="240" w:lineRule="auto"/><w:jc w:val="left"/>""", """$hf<w:b/><w:color w:val="${t.title}"/><w:sz w:val="28"/><w:szCs w:val="28"/>""", outline = 1) +
            style("Heading3", "heading 3", """<w:keepNext/><w:keepLines/><w:spacing w:before="240" w:after="80" w:line="240" w:lineRule="auto"/><w:jc w:val="left"/>""", """$hf<w:b/><w:sz w:val="24"/><w:szCs w:val="24"/>""", outline = 2) +
            style("BodyIndent", "Body Text First Indent", """<w:ind w:firstLineChars="200" w:firstLine="440"/>""", "") +
            style("ListPara", "List Paragraph", """<w:spacing w:after="60"/><w:jc w:val="left"/>""", "") +
            style("Quote", "Quote", """<w:pBdr><w:left w:val="single" w:sz="24" w:space="8" w:color="${t.accent}"/></w:pBdr><w:shd w:val="clear" w:color="auto" w:fill="${t.card}"/><w:spacing w:before="120" w:after="120"/><w:ind w:left="240" w:right="240"/>""", """<w:color w:val="${t.muted}"/>""") +
            style("Code", "Code", """<w:shd w:val="clear" w:color="auto" w:fill="F3F4F6"/><w:spacing w:after="0" w:line="260" w:lineRule="auto"/><w:jc w:val="left"/>""", """<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="19"/><w:szCs w:val="19"/>""") +
            style("Rule", "Horizontal Line", """<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="${t.soft}"/></w:pBdr><w:spacing w:before="120" w:after="240"/>""", "") +
            style("Figure", "Figure", """<w:keepNext/><w:spacing w:before="120" w:after="60" w:line="240" w:lineRule="auto"/><w:jc w:val="center"/>""", "") +
            style("Caption", "caption", """<w:spacing w:before="0" w:after="200"/><w:jc w:val="center"/>""", """<w:color w:val="${t.muted}"/><w:sz w:val="19"/><w:szCs w:val="19"/>""") +
            style("TableText", "Table Text", """<w:spacing w:before="0" w:after="0" w:line="276" w:lineRule="auto"/>""", """<w:sz w:val="20"/><w:szCs w:val="20"/>""") +
            style("CoverTitle", "Cover Title", """<w:spacing w:before="240" w:after="240" w:line="240" w:lineRule="auto"/><w:jc w:val="left"/>""", """$hf<w:b/><w:sz w:val="60"/><w:szCs w:val="60"/>""") +
            style("CoverSubtitle", "Cover Subtitle", """<w:spacing w:before="0" w:after="240"/><w:jc w:val="left"/>""", """<w:sz w:val="28"/><w:szCs w:val="28"/>""") +
            style("CoverInfo", "Cover Info", """<w:spacing w:after="80"/><w:jc w:val="left"/>""", """<w:color w:val="${t.muted}"/><w:sz w:val="24"/><w:szCs w:val="24"/>""") +
            style("TOCHeading", "TOC Heading", """<w:keepNext/><w:spacing w:before="240" w:after="360"/><w:jc w:val="left"/>""", """$hf<w:b/><w:color w:val="${t.primary}"/><w:sz w:val="32"/><w:szCs w:val="32"/>""") +
            listOf(1, 2, 3).joinToString("") { lv ->
                style("TOC$lv", "toc $lv", """<w:tabs><w:tab w:val="right" w:leader="dot" w:pos="$TEXT_W"/></w:tabs><w:spacing w:before="${if (lv == 1) 120 else 40}" w:after="40"/><w:ind w:left="${(lv - 1) * 420}"/><w:jc w:val="left"/>""",
                    if (lv == 1) "<w:b/>" else "")
            } +
            style("Header", "header", """<w:spacing w:after="0"/>""", "") + style("Footer", "footer", """<w:spacing w:after="0"/>""", "") +
            """<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="${t.text}"/></w:rPr></w:style></w:styles>"""

        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val settings = """$XML<w:settings $NS>${if (withToc && headings.isNotEmpty()) "<w:updateFields w:val=\"true\"/>" else ""}<w:defaultTabStop w:val="420"/><w:characterSpacingControl w:val="compressPunctuation"/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>"""
        val core = """$XML<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(docTitle)}</dc:title>${o.cover?.author?.takeIf { it.isNotBlank() }?.let { "<dc:creator>${esc(it)}</dc:creator>" }.orEmpty()}</cp:coreProperties>"""
        val chartParts = charts.flatMapIndexed { k, c ->
            val n = k + 1
            listOf("word/charts/chart$n.xml" to PptChart.xml(c, t),
                "word/charts/_rels/chart$n.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/package" Target="../embeddings/Microsoft_Excel_Worksheet$n.xlsx"/></Relationships>""")
        }
        val binaries = charts.mapIndexed { k, c -> "word/embeddings/Microsoft_Excel_Worksheet${k + 1}.xlsx" to PptChart.workbook(c) } +
            pics.mapIndexed { k, img -> "word/media/image${k + 1}.${img.ext}" to img.bytes }
        val extraRels = charts.indices.joinToString("") { """<Relationship Id="rIdC${it + 1}" Type="$rel/chart" Target="charts/chart${it + 1}.xml"/>""" } +
            pics.indices.joinToString("") { """<Relationship Id="rIdI${it + 1}" Type="$rel/image" Target="media/image${it + 1}.${pics[it].ext}"/>""" }
        val extraTypes = """<Default Extension="xlsx" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/><Default Extension="jpeg" ContentType="image/jpeg"/><Default Extension="png" ContentType="image/png"/>""" +
            charts.indices.joinToString("") { """<Override PartName="/word/charts/chart${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>""" }
        OfficeWriter.zipParts(out, chartParts + listOf(
            "[Content_Types].xml" to """$XML<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>$extraTypes""" +
                """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
                """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""" +
                """<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""" +
                """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""",
            "_rels/.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/styles" Target="styles.xml"/><Relationship Id="rId2" Type="$rel/settings" Target="settings.xml"/><Relationship Id="rId10" Type="$rel/header" Target="header1.xml"/><Relationship Id="rId11" Type="$rel/footer" Target="footer1.xml"/>$extraRels</Relationships>""",
            "word/document.xml" to document,
            "word/styles.xml" to styles,
            "word/settings.xml" to settings,
            "word/header1.xml" to headerXml,
            "word/footer1.xml" to footerXml,
            "docProps/core.xml" to core,
        ), binaries)
        return warnings
    }

    /** 1 → 一，12 → 十二，23 → 二十三（标题编号够用就行）。 */
    internal fun chineseNumber(n: Int): String {
        val d = "零一二三四五六七八九"
        return when {
            n <= 0 -> n.toString()
            n < 10 -> d[n].toString()
            n < 20 -> "十" + (if (n % 10 == 0) "" else d[n % 10].toString())
            n < 100 -> d[n / 10] + "十" + (if (n % 10 == 0) "" else d[n % 10].toString())
            else -> n.toString()
        }
    }
}
