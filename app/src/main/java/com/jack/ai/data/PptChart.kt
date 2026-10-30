package com.jack.ai.data

import java.io.ByteArrayOutputStream

/**
 * PPT 里的原生图表：柱状、条形、折线、饼图、环形图。数据另存一份内嵌的 Excel，
 * 在 PowerPoint、WPS 里双击图表就能改数，图跟着变。颜色跟着 PPT 的配色走。
 */
object PptChart {
    val TYPES = listOf("column", "bar", "line", "pie", "doughnut")

    data class Series(val name: String, val values: List<Double>)

    data class Chart(val type: String, val categories: List<String>, val series: List<Series>, val unit: String = "")

    /** 系列的颜色：浅色主题主色打头，深色主题主色是背景色，从强调色开始。 */
    fun colors(t: PptDeck.Theme): List<String> =
        if (t.dark) listOf(t.accent, "A78BFA", "F472B6", "34D399", "FBBF24", "60A5FA", "F87171", "2DD4BF")
        else (listOf(t.primary, t.accent) + listOf("F59E0B", "10B981", "EF4444", "8B5CF6", "06B6D4", "EC4899")).distinct()

    /** 理一理模型给的数据：去掉空的、对齐长度、饼图只留第一组。返回 null 表示没法画；problems 写给模型看。 */
    fun clean(c: Chart, problems: MutableList<String>): Chart? {
        val type = c.type.takeIf { it in TYPES } ?: "column"
        val cats = c.categories.map { it.trim() }.take(24)
        var series = c.series.filter { it.values.isNotEmpty() }.take(8)
        if (cats.isEmpty() || series.isEmpty()) { problems += "图表没有数据（categories 或 series 是空的）"; return null }
        if (type in setOf("pie", "doughnut") && series.size > 1) { problems += "饼图、环形图只画第一组数据「${series[0].name}」"; series = series.take(1) }
        series = series.mapIndexed { i, s ->
            if (s.values.size != cats.size) problems += "「${s.name.ifBlank { "第 ${i + 1} 组" }}」有 ${s.values.size} 个数，分类有 ${cats.size} 个，已按分类对齐"
            Series(s.name.ifBlank { "系列${i + 1}" }, List(cats.size) { j -> s.values.getOrElse(j) { 0.0 } })
        }
        return Chart(type, cats, series, c.unit.trim())
    }

    private fun esc(s: String) = OfficeWriter.escXml(s)

    /**
     * 空标题，浮在图上不占地方：页面上已经有标题了。不写的话有的查看器会自己补一个「图表标题」或系列名。
     */
    private const val BLANK_TITLE = """<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="100"/></a:pPr><a:r><a:rPr lang="zh-CN" sz="100"/><a:t> </a:t></a:r></a:p></c:rich></c:tx><c:overlay val="1"/></c:title>"""

    private fun num(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun col(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { sb.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun solid(c: String) = """<a:solidFill><a:srgbClr val="$c"/></a:solidFill>"""

    private fun txPr(size: Int, color: String, bold: Boolean = false) =
        """<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="$size"${if (bold) " b=\"1\"" else ""}>${solid(color)}<a:latin typeface="微软雅黑"/><a:ea typeface="微软雅黑"/></a:defRPr></a:pPr><a:endParaRPr lang="zh-CN"/></a:p></c:txPr>"""

    /** 图表部件的 XML。workbook 关系用 rId1。 */
    fun xml(c: Chart, t: PptDeck.Theme): String {
        val colors = colors(t)
        val n = c.categories.size
        val pie = c.type == "pie" || c.type == "doughnut"
        val label = t.text
        val cat = """<c:cat><c:strRef><c:f>Sheet1!${'$'}A${'$'}2:${'$'}A${'$'}${n + 1}</c:f><c:strCache><c:ptCount val="$n"/>""" +
            c.categories.mapIndexed { i, v -> """<c:pt idx="$i"><c:v>${esc(v)}</c:v></c:pt>""" }.joinToString("") + "</c:strCache></c:strRef></c:cat>"
        val sers = c.series.mapIndexed { si, s ->
            val letter = col(si + 1)
            // 饼图的系列名哪儿都不显示（图例是分类），有的查看器却拿它当标题，所以留空
            val shown = if (pie) " " else s.name
            val tx = """<c:tx><c:strRef><c:f>Sheet1!${'$'}$letter${'$'}1</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>${esc(shown)}</c:v></c:pt></c:strCache></c:strRef></c:tx>"""
            val v = """<c:val><c:numRef><c:f>Sheet1!${'$'}$letter${'$'}2:${'$'}$letter${'$'}${n + 1}</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="$n"/>""" +
                s.values.mapIndexed { i, x -> """<c:pt idx="$i"><c:v>${num(x)}</c:v></c:pt>""" }.joinToString("") + "</c:numCache></c:numRef></c:val>"
            val color = colors[si % colors.size]
            when (c.type) {
                "line" -> """<c:ser><c:idx val="$si"/><c:order val="$si"/>$tx<c:spPr><a:ln w="34925" cap="rnd">${solid(color)}<a:round/></a:ln></c:spPr>""" +
                    """<c:marker><c:symbol val="circle"/><c:size val="7"/><c:spPr>${solid(color)}<a:ln w="19050">${solid(t.bg)}</a:ln></c:spPr></c:marker>""" +
                    """<c:dLbls><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>${txPr(1200, label)}<c:dLblPos val="t"/><c:showLegendKey val="0"/><c:showVal val="${if (c.series.size <= 2) 1 else 0}"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="0"/><c:showBubbleSize val="0"/></c:dLbls>""" +
                    """$cat$v<c:smooth val="0"/></c:ser>"""
                // 环形图不认 dLblPos，写了 PowerPoint 会报文件损坏
                "pie", "doughnut" -> {
                    val pts = (0 until n).joinToString("") { i -> """<c:dPt><c:idx val="$i"/><c:bubble3D val="0"/><c:spPr>${solid(colors[i % colors.size])}<a:ln w="19050">${solid(t.bg)}</a:ln></c:spPr></c:dPt>""" }
                    """<c:ser><c:idx val="$si"/><c:order val="$si"/>$tx$pts""" +
                        // 环形图的字压在色块上，用白字；饼图的字在外面，用正文色
                        """<c:dLbls><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>${txPr(1300, if (c.type == "doughnut") "FFFFFF" else label, bold = c.type == "doughnut")}${if (c.type == "pie") "<c:dLblPos val=\"outEnd\"/>" else ""}<c:showLegendKey val="0"/><c:showVal val="0"/><c:showCatName val="1"/><c:showSerName val="0"/><c:showPercent val="1"/><c:showBubbleSize val="0"/><c:separator>：</c:separator><c:showLeaderLines val="1"/></c:dLbls>""" +
                        """$cat$v</c:ser>"""
                }
                else -> """<c:ser><c:idx val="$si"/><c:order val="$si"/>$tx<c:spPr>${solid(color)}</c:spPr><c:invertIfNegative val="0"/>""" +
                    """<c:dLbls><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>${txPr(1200, label)}<c:dLblPos val="outEnd"/><c:showLegendKey val="0"/><c:showVal val="${if (c.series.size <= 3 && n <= 12) 1 else 0}"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="0"/><c:showBubbleSize val="0"/></c:dLbls>""" +
                    "$cat$v</c:ser>"
            }
        }.joinToString("")
        val axes = """<c:axId val="11"/><c:axId val="22"/>"""
        val plot = when (c.type) {
            "line" -> """<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>$sers<c:marker val="1"/>$axes</c:lineChart>"""
            "pie" -> """<c:pieChart><c:varyColors val="1"/>$sers<c:firstSliceAng val="0"/></c:pieChart>"""
            "doughnut" -> """<c:doughnutChart><c:varyColors val="1"/>$sers<c:firstSliceAng val="0"/><c:holeSize val="58"/></c:doughnutChart>"""
            else -> """<c:barChart><c:barDir val="${if (c.type == "bar") "bar" else "col"}"/><c:grouping val="clustered"/><c:varyColors val="0"/>$sers<c:gapWidth val="70"/>${if (c.series.size > 1) "<c:overlap val=\"-10\"/>" else ""}$axes</c:barChart>"""
        }
        val grid = if (t.dark) "334155" else "E5E7EB"
        val catPos = if (c.type == "bar") "l" else "b"
        val valPos = if (c.type == "bar") "b" else "l"
        val axisXml = if (pie) "" else
            """<c:catAx><c:axId val="11"/><c:scaling><c:orientation val="${if (c.type == "bar") "maxMin" else "minMax"}"/></c:scaling><c:delete val="0"/><c:axPos val="$catPos"/><c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:spPr><a:ln w="9525">${solid(grid)}</a:ln></c:spPr>${txPr(1300, label)}<c:crossAx val="22"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/></c:catAx>""" +
                """<c:valAx><c:axId val="22"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="$valPos"/><c:majorGridlines><c:spPr><a:ln w="6350">${solid(grid)}</a:ln></c:spPr></c:majorGridlines><c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:spPr><a:ln><a:noFill/></a:ln></c:spPr>${txPr(1200, t.muted)}<c:crossAx val="11"/><c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx>"""
        val legend = if (pie || c.series.size > 1) """<c:legend><c:legendPos val="${if (pie) "r" else "t"}"/><c:overlay val="0"/>${txPr(1300, label)}</c:legend>""" else ""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<c:date1904 val="0"/><c:lang val="zh-CN"/><c:roundedCorners val="0"/><c:chart>$BLANK_TITLE<c:autoTitleDeleted val="0"/><c:plotArea><c:layout/>$plot$axisXml<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr></c:plotArea>$legend<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart>""" +
            """<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>${txPr(1200, label)}<c:externalData r:id="rId1"><c:autoUpdate val="0"/></c:externalData></c:chartSpace>"""
    }

    /** 内嵌的数据表：A 列分类，后面每组一列，第一行是组名。 */
    fun workbook(c: Chart): ByteArray {
        val pie = c.type == "pie" || c.type == "doughnut"
        val rows = listOf(listOf(c.unit) + c.series.map { if (pie) " " else it.name }) +
            c.categories.mapIndexed { i, cat -> listOf(cat) + c.series.map { num(it.values[i]) } }
        return ByteArrayOutputStream().also { OfficeWriter.writeXlsx(listOf(OfficeWriter.Sheet("Sheet1", rows)), it) }.toByteArray()
    }
}
