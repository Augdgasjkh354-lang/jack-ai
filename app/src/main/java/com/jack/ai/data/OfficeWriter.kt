package com.jack.ai.data

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 不依赖任何第三方库，直接按 Office Open XML 规范生成 docx / pptx / xlsx。
 * 只用纯 Java/Kotlin，方便在手机上运行。
 */
object OfficeWriter {

    private const val FONT = "微软雅黑"

    internal fun escXml(s: String): String = esc(s)

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') append(' ') else append(ch)
        }
    }

    private fun zip(out: OutputStream, files: List<Pair<String, String>>, binaries: List<Pair<String, ByteArray>> = emptyList()) {
        ZipOutputStream(out).use { z ->
            for ((name, content) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            for ((name, bytes) in binaries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    // ======================= Word =======================

    /** 一段文字里的 **粗体** / *斜体* / `代码` 转成 Word 的 run。 */
    private fun runs(text: String, baseBold: Boolean = false, size: Int? = null): String {
        val sb = StringBuilder()
        val re = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*(?!\s)(.+?)\*""")
        var last = 0
        fun run(t: String, bold: Boolean, italic: Boolean, code: Boolean) {
            if (t.isEmpty()) return
            sb.append("<w:r><w:rPr>")
            if (code) sb.append("""<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>""")
            if (bold) sb.append("<w:b/>")
            if (italic) sb.append("<w:i/>")
            if (size != null) sb.append("""<w:sz w:val="$size"/><w:szCs w:val="$size"/>""")
            sb.append("""</w:rPr><w:t xml:space="preserve">""").append(esc(t)).append("</w:t></w:r>")
        }
        for (m in re.findAll(text)) {
            run(text.substring(last, m.range.first), baseBold, false, false)
            when {
                m.groups[1] != null -> run(m.groupValues[1], true, false, false)
                m.groups[2] != null -> run(m.groupValues[2], baseBold, false, true)
                else -> run(m.groupValues[3], baseBold, true, false)
            }
            last = m.range.last + 1
        }
        run(text.substring(last), baseBold, false, false)
        return sb.toString()
    }

    private fun para(text: String, style: String? = null, indentTwips: Int = 0, bold: Boolean = false): String {
        val ppr = buildString {
            if (style != null) append("""<w:pStyle w:val="$style"/>""")
            if (indentTwips > 0) append("""<w:ind w:left="$indentTwips" w:hanging="300"/>""")
        }
        return "<w:p>" + (if (ppr.isNotEmpty()) "<w:pPr>$ppr</w:pPr>" else "") + runs(text, bold) + "</w:p>"
    }

    private fun table(rows: List<List<String>>): String {
        val cols = rows.maxOf { it.size }
        val sb = StringBuilder("""<w:tbl><w:tblPr><w:tblStyle w:val="Grid"/><w:tblW w:w="5000" w:type="pct"/>""")
        sb.append("""<w:tblBorders>""")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("""<w:$side w:val="single" w:sz="4" w:space="0" w:color="999999"/>""")
        }
        sb.append("</w:tblBorders></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("""<w:gridCol w:w="${9000 / cols}"/>""") }
        sb.append("</w:tblGrid>")
        rows.forEachIndexed { r, row ->
            sb.append("<w:tr>")
            for (c in 0 until cols) {
                val cell = row.getOrElse(c) { "" }
                sb.append("<w:tc><w:tcPr>")
                if (r == 0) sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="E8ECF8"/>""")
                sb.append("</w:tcPr><w:p>").append(runs(cell, baseBold = r == 0)).append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl><w:p/>")
        return sb.toString()
    }

    fun writeDocx(markdown: String, out: OutputStream) {
        val body = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var i = 0
        var firstHeading = true
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        body.append(para(lines[i].ifEmpty { " " }, "Code"))
                        i++
                    }
                }
                t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("""\|?\s*:?-{2,}.*""")) -> {
                    val rows = mutableListOf<List<String>>()
                    rows += splitRow(t)
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows += splitRow(lines[i].trim()); i++
                    }
                    body.append(table(rows))
                    continue
                }
                t.startsWith("# ") -> {
                    body.append(para(t.removePrefix("# "), if (firstHeading) "Title" else "Heading1"))
                    firstHeading = false
                }
                t.startsWith("## ") -> body.append(para(t.removePrefix("## "), "Heading1"))
                t.startsWith("### ") -> body.append(para(t.removePrefix("### "), "Heading2"))
                t.startsWith("#### ") -> body.append(para(t.removePrefix("#### "), "Heading3"))
                t.matches(Regex("""[-*+]\s+.*""")) -> {
                    val level = (line.length - line.trimStart().length) / 2
                    body.append(para("•  " + t.substring(1).trim(), "ListPara", 420 + level * 420))
                }
                t.matches(Regex("""\d+[.)、]\s*.*""")) -> {
                    val level = (line.length - line.trimStart().length) / 2
                    body.append(para(t, "ListPara", 420 + level * 420))
                }
                t.startsWith(">") -> body.append(para(t.removePrefix(">").trim(), "Quote"))
                t.matches(Regex("""-{3,}|\*{3,}""")) -> body.append("<w:p/>")
                t.isEmpty() -> {}
                else -> body.append(para(t))
            }
            i++
        }

        val document = """$XML<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="851" w:footer="992" w:gutter="0"/></w:sectPr></w:body></w:document>"""

        fun pstyle(id: String, name: String, size: Int, bold: Boolean, color: String? = null, spaceBefore: Int = 0, spaceAfter: Int = 120, extraPPr: String = "", font: String? = null) =
            """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="$spaceBefore" w:after="$spaceAfter"/>$extraPPr</w:pPr><w:rPr>""" +
                (if (font != null) """<w:rFonts w:ascii="$font" w:hAnsi="$font"/>""" else "") +
                (if (bold) "<w:b/>" else "") + (if (color != null) """<w:color w:val="$color"/>""" else "") +
                """<w:sz w:val="$size"/><w:szCs w:val="$size"/></w:rPr></w:style>"""

        val styles = XML + """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="$FONT" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="360" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            pstyle("Title", "Title", 40, true, "1F3864", 0, 240, """<w:jc w:val="center"/>""") +
            pstyle("Heading1", "heading 1", 32, true, "2F5496", 360, 120) +
            pstyle("Heading2", "heading 2", 28, true, "2F5496", 240, 80) +
            pstyle("Heading3", "heading 3", 24, true, "404040", 200, 60) +
            pstyle("ListPara", "List Paragraph", 22, false, null, 0, 60) +
            pstyle("Quote", "Quote", 22, false, "595959", 60, 60, """<w:ind w:left="567"/>""") +
            pstyle("Code", "Code", 20, false, "333333", 0, 0, """<w:shd w:val="clear" w:color="auto" w:fill="F2F2F2"/>""", "Consolas") +
            """<w:style w:type="table" w:styleId="Grid"><w:name w:val="Table Grid"/></w:style></w:styles>"""

        zip(out, listOf(
            "[Content_Types].xml" to """$XML<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
            "_rels/.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "word/document.xml" to document,
            "word/styles.xml" to styles,
        ))
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("**", "") }

    // ======================= PPT =======================

    /** image 是配图（jpeg 或 png），imageW / imageH 是它的像素尺寸，用来保持比例。 */
    data class Slide(
        val layout: String,
        val title: String,
        val bullets: List<String>,
        val image: ByteArray? = null,
        val imageExt: String = "jpeg",
        val imageW: Int = 0,
        val imageH: Int = 0,
        /** 每个条目（或这页）配的图标名（Lucide），见 [PptDeck.ICONS]。 */
        val icons: List<String> = emptyList(),
        /** 这页的转场；空的话跟整份的设置走。 */
        val transition: String = "",
        /** 演讲稿：放映时演讲者看到的备注。 */
        val notes: String = "",
        /** chart 版式的图表数据。 */
        val chart: PptChart.Chart? = null,
    )

    private const val W = PptDeck.W
    private const val H = PptDeck.H
    private const val NS = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""

    /** PPT 的主题：把这份的配色和字体写进去，在 PowerPoint、WPS 里选颜色、加新页时也是这一套。 */
    private fun pptTheme(t: PptDeck.Theme): String = XML + """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Jack"><a:themeElements><a:clrScheme name="Jack"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="${t.title}"/></a:dk2><a:lt2><a:srgbClr val="${t.soft}"/></a:lt2><a:accent1><a:srgbClr val="${t.primary}"/></a:accent1><a:accent2><a:srgbClr val="${t.accent}"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme><a:fontScheme name="Jack"><a:majorFont><a:latin typeface="${esc(t.headLatin)}"/><a:ea typeface="${esc(t.headEa)}"/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="${esc(t.latin)}"/><a:ea typeface="${esc(t.ea)}"/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""

    private val EMPTY_TREE = """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>"""

    /**
     * 写 PPT。每页怎么排、转场和动画见 [PptDeck]。transition 是整份默认的转场，某页自己写了就用那页的；
     * animation 是条目怎么一个个出现（none 就一次全出来）。返回排版时发现的问题（字太多、图标没有……），给模型看。
     */
    fun writePptx(
        slides: List<Slide>, themeName: String, out: OutputStream, transition: String = "fade", animation: String = "fade",
        /** 照着用户的 PPT 读出来的配色和字体（[PptStyle]），给了就不用 themeName。 */
        custom: PptDeck.Theme? = null,
    ): List<String> {
        val t = custom ?: PptDeck.theme(themeName)
        val warnings = mutableListOf<String>()
        val list = slides.ifEmpty { listOf(Slide("title", "演示文稿", emptyList())) }
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val files = mutableListOf<Pair<String, String>>()

        files += "_rels/.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""
        files += "ppt/presProps.xml" to """$XML<p:presentationPr $NS/>"""
        files += "ppt/viewProps.xml" to """$XML<p:viewPr $NS><p:normalViewPr><p:restoredLeft sz="15620"/><p:restoredTop sz="94660"/></p:normalViewPr><p:gridSpacing cx="72008" cy="72008"/></p:viewPr>"""
        files += "ppt/tableStyles.xml" to """$XML<a:tblStyleLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>"""
        files += "ppt/theme/theme1.xml" to pptTheme(t)
        files += "ppt/slideMasters/slideMaster1.xml" to """$XML<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>$EMPTY_TREE</p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz="2400"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"""
        files += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$rel/theme" Target="../theme/theme1.xml"/></Relationships>"""
        files += "ppt/slideLayouts/slideLayout1.xml" to """$XML<p:sldLayout $NS type="blank" preserve="1"><p:cSld name="Blank">$EMPTY_TREE</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
        files += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
        val media = mutableListOf<Pair<String, ByteArray>>()
        /** 有图表的页、有演讲稿的页（页号从 1 数）。 */
        val charts = mutableListOf<Int>()
        val notes = mutableListOf<Int>()
        var section = 0
        list.forEachIndexed { i, raw ->
            // 只有正文页和大图页放配图
            val s = if (raw.layout == "content" || raw.layout == "image" || raw.layout !in PptDeck.LAYOUTS) raw else raw.copy(image = null)
            if (s.layout == "section") section++
            val r = PptDeck.render(s, t, i + 1, list.size, section)
            warnings += r.warnings
            // 封面、章节页、引言、结尾页不逐条出现，进来就是完整的
            val anim = if (s.layout in setOf("title", "section", "quote", "end", "image")) "none" else animation
            val trans = PptDeck.transition(s.transition.ifBlank { transition })
            files += "ppt/slides/slide${i + 1}.xml" to """$XML<p:sld $NS><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="${r.bg}"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>${r.shapes}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>$trans${PptDeck.timing(r.steps, anim, r.byParagraph, r.noBuild, r.graphics)}</p:sld>"""
            val n = i + 1
            val imageRel = s.image?.let { bytes ->
                val name = "image$n.${s.imageExt}"
                media += "ppt/media/$name" to bytes
                """<Relationship Id="rId2" Type="$rel/image" Target="../media/$name"/>"""
            }.orEmpty()
            // 图表：图表部件 + 内嵌的数据表，双击能改数
            val chartRel = r.chart?.let { c ->
                charts += n
                files += "ppt/charts/chart$n.xml" to PptChart.xml(c, t)
                media += "ppt/embeddings/Microsoft_Excel_Worksheet$n.xlsx" to PptChart.workbook(c)
                files += "ppt/charts/_rels/chart$n.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/package" Target="../embeddings/Microsoft_Excel_Worksheet$n.xlsx"/></Relationships>"""
                """<Relationship Id="rId3" Type="$rel/chart" Target="../charts/chart$n.xml"/>"""
            }.orEmpty()
            // 演讲稿：放映时演讲者视图里看得到
            val notesRel = s.notes.trim().takeIf { it.isNotEmpty() }?.let { text ->
                notes += n
                files += "ppt/notesSlides/notesSlide$n.xml" to notesSlide(text)
                files += "ppt/notesSlides/_rels/notesSlide$n.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/notesMaster" Target="../notesMasters/notesMaster1.xml"/><Relationship Id="rId2" Type="$rel/slide" Target="../slides/slide$n.xml"/></Relationships>"""
                """<Relationship Id="rId4" Type="$rel/notesSlide" Target="../notesSlides/notesSlide$n.xml"/>"""
            }.orEmpty()
            files += "ppt/slides/_rels/slide${i + 1}.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>$imageRel$chartRel$notesRel</Relationships>"""
        }
        if (notes.isNotEmpty()) {
            files += "ppt/notesMasters/notesMaster1.xml" to NOTES_MASTER
            files += "ppt/notesMasters/_rels/notesMaster1.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/theme" Target="../theme/theme2.xml"/></Relationships>"""
            files += "ppt/theme/theme2.xml" to pptTheme(t)
        }
        files += "[Content_Types].xml" to buildString {
            append("""$XML<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/><Default Extension="png" ContentType="image/png"/>""")
            append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
            append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
            append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
            append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
            append("""<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>""")
            append("""<Override PartName="/ppt/viewProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"/>""")
            append("""<Override PartName="/ppt/tableStyles.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"/>""")
            list.indices.forEach { append("""<Override PartName="/ppt/slides/slide${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""") }
            append("""<Default Extension="xlsx" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/>""")
            charts.forEach { append("""<Override PartName="/ppt/charts/chart$it.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>""") }
            if (notes.isNotEmpty()) {
                append("""<Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>""")
                append("""<Override PartName="/ppt/theme/theme2.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
                notes.forEach { append("""<Override PartName="/ppt/notesSlides/notesSlide$it.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"/>""") }
            }
            append("</Types>")
        }
        files += "ppt/presentation.xml" to buildString {
            append("""$XML<p:presentation $NS saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
            if (notes.isNotEmpty()) append("""<p:notesMasterIdLst><p:notesMasterId r:id="rId6"/></p:notesMasterIdLst>""")
            append("<p:sldIdLst>")
            list.indices.forEach { append("""<p:sldId id="${256 + it}" r:id="rId${10 + it}"/>""") }
            append("""</p:sldIdLst><p:sldSz cx="$W" cy="$H"/><p:notesSz cx="6858000" cy="9144000"/><p:defaultTextStyle><a:lvl1pPr><a:defRPr lang="zh-CN"/></a:lvl1pPr></p:defaultTextStyle></p:presentation>""")
        }
        files += "ppt/_rels/presentation.xml.rels" to buildString {
            append("""$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            append("""<Relationship Id="rId1" Type="$rel/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
            append("""<Relationship Id="rId2" Type="$rel/theme" Target="theme/theme1.xml"/>""")
            append("""<Relationship Id="rId3" Type="$rel/presProps" Target="presProps.xml"/>""")
            append("""<Relationship Id="rId4" Type="$rel/viewProps" Target="viewProps.xml"/>""")
            append("""<Relationship Id="rId5" Type="$rel/tableStyles" Target="tableStyles.xml"/>""")
            if (notes.isNotEmpty()) append("""<Relationship Id="rId6" Type="$rel/notesMaster" Target="notesMasters/notesMaster1.xml"/>""")
            list.indices.forEach { append("""<Relationship Id="rId${10 + it}" Type="$rel/slide" Target="slides/slide${it + 1}.xml"/>""") }
            append("</Relationships>")
        }
        zip(out, files, media)
        return warnings
    }

    /** 一页的演讲稿：上面是这页的缩略图，下面是备注文字，一段一行。 */
    private fun notesSlide(text: String): String {
        val ps = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("") { """<a:p><a:r><a:rPr lang="zh-CN" altLang="en-US" dirty="0"/><a:t>${esc(it)}</a:t></a:r></a:p>""" }
        return """$XML<p:notes $NS><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Slide Image Placeholder 1"/><p:cNvSpPr><a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/></p:cNvSpPr><p:nvPr><p:ph type="sldImg"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="3" name="Notes Placeholder 2"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>$ps</p:txBody></p:sp>""" +
            """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"""
    }

    /** 备注页的母版：缩略图在上、文字在下（竖版 A4 大小）。 */
    private val NOTES_MASTER = XML + """<p:notesMaster $NS><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
        """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Slide Image Placeholder 1"/><p:cNvSpPr><a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/></p:cNvSpPr><p:nvPr><p:ph type="sldImg" idx="2"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="381000" y="685800"/><a:ext cx="6096000" cy="3429000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln w="12700"><a:solidFill><a:prstClr val="black"/></a:solidFill></a:ln></p:spPr></p:sp>""" +
        """<p:sp><p:nvSpPr><p:cNvPr id="3" name="Notes Placeholder 2"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" sz="quarter" idx="3"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="685800" y="4400550"/><a:ext cx="5486400" cy="3600450"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0"/><a:lstStyle/><a:p><a:pPr lvl="0"/><a:r><a:rPr lang="zh-CN"/><a:t>备注</a:t></a:r></a:p></p:txBody></p:sp>""" +
        """</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
        """<p:notesStyle><a:lvl1pPr marL="0" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:defRPr sz="1400" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl1pPr></p:notesStyle></p:notesMaster>"""

    // ======================= Excel =======================

    data class Sheet(val name: String, val rows: List<List<String>>)

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private val NUMBER = Regex("""-?\d+(\.\d+)?([eE][-+]?\d+)?""")

    private fun displayWidth(s: String): Int = s.fold(0) { acc, c -> acc + if (c.code > 0x2E80) 2 else 1 }

    fun writeXlsx(sheets: List<Sheet>, out: OutputStream) {
        val list = sheets.ifEmpty { listOf(Sheet("Sheet1", emptyList())) }
        // 工作表名不能重复，不能有 []:*?/\ ，最长 31 字
        val names = mutableListOf<String>()
        list.forEachIndexed { i, s ->
            var n = s.name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().take(31).ifEmpty { "Sheet${i + 1}" }
            while (n in names) n = (n.take(28) + "_" + (i + 1))
            names += n
        }
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val files = mutableListOf<Pair<String, String>>()
        files += "[Content_Types].xml" to buildString {
            append("""$XML<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            list.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
            append("</Types>")
        }
        files += "_rels/.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        files += "xl/workbook.xml" to buildString {
            append("""$XML<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$rel"><sheets>""")
            names.forEachIndexed { i, n -> append("""<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
            append("</sheets></workbook>")
        }
        files += "xl/_rels/workbook.xml.rels" to buildString {
            append("""$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            list.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="$rel/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
            append("""<Relationship Id="rId${list.size + 1}" Type="$rel/styles" Target="styles.xml"/></Relationships>""")
        }
        // 样式 0：普通；1：表头（加粗、浅色底、边框）；2：普通带边框
        files += "xl/styles.xml" to """$XML<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="$FONT"/></font><font><b/><sz val="11"/><name val="$FONT"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8ECF8"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

        list.forEachIndexed { si, sheet ->
            val sb = StringBuilder("""$XML<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            if (sheet.rows.isNotEmpty()) sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            val cols = sheet.rows.maxOfOrNull { it.size } ?: 0
            if (cols > 0) {
                sb.append("<cols>")
                for (c in 0 until cols) {
                    val w = sheet.rows.maxOf { displayWidth(it.getOrElse(c) { "" }) }.coerceIn(6, 50) + 2
                    sb.append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
                }
                sb.append("</cols>")
            }
            sb.append("<sheetData>")
            sheet.rows.forEachIndexed { r, row ->
                sb.append("""<row r="${r + 1}">""")
                row.forEachIndexed { c, raw ->
                    val ref = colName(c) + (r + 1)
                    val style = if (r == 0) 1 else 2
                    val v = raw.trim()
                    when {
                        v.isEmpty() -> sb.append("""<c r="$ref" s="$style"/>""")
                        v.startsWith("=") && r > 0 -> sb.append("""<c r="$ref" s="$style"><f>${esc(v.substring(1))}</f></c>""")
                        r > 0 && NUMBER.matches(v) && !(v.length > 1 && v.startsWith("0") && !v.startsWith("0.")) ->
                            sb.append("""<c r="$ref" s="$style"><v>$v</v></c>""")
                        else -> sb.append("""<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>""")
                    }
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            files += "xl/worksheets/sheet${si + 1}.xml" to sb.toString()
        }
        zip(out, files)
    }
}
