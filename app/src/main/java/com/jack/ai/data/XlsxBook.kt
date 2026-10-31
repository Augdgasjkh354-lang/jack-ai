package com.jack.ai.data

import java.io.OutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Excel：表头主色白字、冻结首行、自动筛选、隔行浅底；数字按写法给格式（1,234、12%、¥12.50、2026-10-14 都存成真的数，能排序、能算），
 * 可以加合计行、条件格式（超标标红、前几名标绿、数据条）和原生图表（直接指着表里的格子，改了数图跟着变）。
 */
object XlsxBook {
    /** 条件格式：column 是表头名或列字母；op 是 > < >= <= = != contains top bottom databar；color 是 red / green / yellow。 */
    data class Highlight(val column: String, val op: String, val value: String = "", val color: String = "red")

    /** 表里的图：分类取哪一列、数取哪几列（表头名或列字母）。 */
    data class SheetChart(val type: String, val title: String, val categoryColumn: String, val valueColumns: List<String>, val unit: String = "")

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val FONT = "微软雅黑"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun esc(s: String) = OfficeWriter.escXml(s)

    internal fun colName(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { sb.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun displayWidth(s: String) = s.fold(0) { acc, c -> acc + if (c.code > 0x2E80) 2 else 1 }

    // ---------- 一个格子 ----------

    /** 数字格式：general 原样、int 千分位、dec 千分位两位小数、pct 百分比、pct1 一位小数的百分比、money 人民币、date 日期。 */
    private val FORMATS = linkedMapOf("general" to 0, "int" to 3, "dec" to 4, "pct" to 9, "pct1" to 165, "money" to 166, "date" to 164)

    internal class Cell(val number: Double? = null, val format: String = "general", val text: String? = null, val formula: String? = null)

    private val NUM = Regex("""^[-+]?\d+(\.\d+)?([eE][-+]?\d+)?$""")
    private val GROUPED = Regex("""^[-+]?\d{1,3}(,\d{3})+(\.\d+)?$""")
    private val DATE = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")

    /** 公式里不能出现的：外部工作簿引用（[工作簿]）、DDE（cmd|'...'!A1 的竖线）、外联取数类函数。跨表引用 Sheet2!A1 照常放行。 */
    private val DANGEROUS_FORMULA = Regex("(?i)\\[|\\||\\b(WEBSERVICE|HYPERLINK|IMPORTDATA|IMPORTXML|IMPORTFEED|DDE|EXEC|REGISTER|CALL|FILTERXML)\\b")

    /** 把模型写的字看成数、日期、公式还是文字。以 0 开头的整数（编号、电话）当文字，不丢前导 0。 */
    internal fun parse(raw: String): Cell {
        val v = raw.trim()
        if (v.isEmpty()) return Cell(text = "")
        if (v.startsWith("=")) {
            // 以 = 开头的格子默认当公式是表格工具的老行为，但网页、聊天记录里抄来的内容也可能以 = 开头；
            // 带外部引用、危险函数的，一律当文字，不进公式，避免打开文件就触发外联或命令。
            val body = v.substring(1)
            if (!DANGEROUS_FORMULA.containsMatchIn(body)) return Cell(formula = body)
        }
        DATE.find(v)?.let { m ->
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()?.let { d ->
                return Cell(ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d).toDouble(), "date")
            }
        }
        if (v.endsWith("%")) {
            val n = v.removeSuffix("%").replace(",", "")
            if (NUM.matches(n)) {
                val d = n.toDouble() / 100
                if (d.isFinite()) return Cell(d, if (n.contains('.')) "pct1" else "pct")
            }
        }
        val money = v.startsWith("¥") || v.startsWith("￥")
        val body = v.removePrefix("¥").removePrefix("￥").trim()
        if (GROUPED.matches(body)) {
            val d = body.replace(",", "").toDouble()
            if (d.isFinite()) return Cell(d, if (money) "money" else if (body.contains('.')) "dec" else "int")
        }
        if (NUM.matches(body) && !(body.length > 1 && body.startsWith("0") && !body.startsWith("0."))) {
            val d = body.toDouble()
            // 1e999 这类数 toDouble() 是 Infinity，写进 XML 会让 Office 判文件损坏：算不出来的数当文字留着
            if (d.isFinite()) return Cell(d, if (money) "money" else "general")
        }
        return Cell(text = v)
    }

    private fun num(v: Double) = if (!v.isFinite()) "0" else if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    // ---------- 整本 ----------

    fun write(sheets: List<OfficeWriter.Sheet>, out: OutputStream, themeName: String = "blue"): List<String> {
        val t = PptDeck.theme(themeName).let { if (it.dark) PptDeck.theme("blue").copy(primary = it.accent) else it }
        val warnings = mutableListOf<String>()
        val list = sheets.ifEmpty { listOf(OfficeWriter.Sheet("Sheet1", emptyList())) }
        // 工作表名不能重复，不能有 []:*?/\ ，最长 31 字
        val names = mutableListOf<String>()
        list.forEachIndexed { i, s ->
            var n = s.name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().take(31).ifEmpty { "Sheet${i + 1}" }
            // 去重后缀必须每次都不一样：原来固定用 (i + 1)，28 个 A + "_2" 这种名字会原地不动死循环
            var k = i + 1
            while (n in names) {
                val suf = "_$k"
                n = n.take(31 - suf.length) + suf
                k++
            }
            names += n
        }

        // 样式按需要登记：(格式, 表头, 隔行底色, 合计行) → 编号
        val xfs = linkedMapOf<String, Int>()
        fun xf(format: String, header: Boolean = false, band: Boolean = false, total: Boolean = false): Int =
            xfs.getOrPut("$format|$header|$band|$total") { xfs.size }
        xf("general")  // 0 号是默认

        val files = mutableListOf<Pair<String, String>>()
        val chartFiles = mutableListOf<Pair<String, String>>()
        val drawings = mutableListOf<Int>()
        var chartNo = 0
        list.forEachIndexed { si, sheet ->
            val rows = sheet.rows
            val cols = rows.maxOfOrNull { it.size } ?: 0
            val header = rows.firstOrNull().orEmpty()
            val data = rows.drop(1)
            val cells = data.map { r -> (0 until cols).map { c -> parse(r.getOrElse(c) { "" }) } }
            // 每列多数格子是什么格式：公式和合计用这个
            val colFormat = (0 until cols).map { c ->
                cells.mapNotNull { it[c].takeIf { x -> x.number != null }?.format }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "general"
            }
            val numericCol = (0 until cols).map { c ->
                val filled = cells.count { it[c].text?.isEmpty() != true }
                filled > 0 && cells.count { it[c].number != null || it[c].formula != null } * 2 >= filled
            }
            fun colIndex(ref: String): Int? {
                val r = ref.trim()
                header.indexOfFirst { it.trim() == r }.takeIf { it >= 0 }?.let { return it }
                if (r.matches(Regex("[A-Za-z]{1,2}"))) return r.uppercase().fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
                return header.indexOfFirst { it.contains(r) }.takeIf { it >= 0 }
            }
            val lastData = data.size + 1
            val withTotal = sheet.totalRow && data.isNotEmpty() && numericCol.drop(1).any { it }

            val sb = StringBuilder("""$XML<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$REL">""")
            if (rows.isNotEmpty()) sb.append("""<sheetViews><sheetView workbookViewId="0"${if (si == 0) " tabSelected=\"1\"" else ""}><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            sb.append("""<sheetFormatPr defaultRowHeight="18"/>""")
            if (cols > 0) {
                sb.append("<cols>")
                for (c in 0 until cols) {
                    val w = rows.maxOf { displayWidth(it.getOrElse(c) { "" }) }.coerceIn(6, 50) + 3
                    sb.append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
                }
                sb.append("</cols>")
            }
            sb.append("<sheetData>")
            if (header.isNotEmpty()) {
                sb.append("""<row r="1" ht="24" customHeight="1">""")
                for (c in 0 until cols) sb.append("""<c r="${colName(c)}1" s="${xf("general", header = true)}" t="inlineStr"><is><t xml:space="preserve">${esc(header.getOrElse(c) { "" }.trim())}</t></is></c>""")
                sb.append("</row>")
            }
            cells.forEachIndexed { k, row ->
                val r = k + 2
                val band = k % 2 == 1
                sb.append("""<row r="$r">""")
                row.forEachIndexed { c, cell ->
                    val ref = colName(c) + r
                    when {
                        cell.formula != null -> sb.append("""<c r="$ref" s="${xf(colFormat[c], band = band)}"><f>${esc(cell.formula)}</f></c>""")
                        cell.number != null -> sb.append("""<c r="$ref" s="${xf(cell.format, band = band)}"><v>${num(cell.number)}</v></c>""")
                        else -> sb.append("""<c r="$ref" s="${xf("general", band = band)}" t="inlineStr"><is><t xml:space="preserve">${esc(cell.text.orEmpty())}</t></is></c>""")
                    }
                }
                sb.append("</row>")
            }
            if (withTotal) {
                val r = lastData + 1
                sb.append("""<row r="$r">""")
                for (c in 0 until cols) {
                    val ref = colName(c) + r
                    when {
                        c == 0 -> sb.append("""<c r="$ref" s="${xf("general", total = true)}" t="inlineStr"><is><t>合计</t></is></c>""")
                        numericCol[c] && colFormat[c] !in setOf("pct", "pct1", "date") ->
                            sb.append("""<c r="$ref" s="${xf(colFormat[c], total = true)}"><f>SUM(${colName(c)}2:${colName(c)}$lastData)</f></c>""")
                        else -> sb.append("""<c r="$ref" s="${xf("general", total = true)}"/>""")
                    }
                }
                sb.append("</row>")
            }
            sb.append("</sheetData>")
            if (rows.size > 1 && cols > 0) sb.append("""<autoFilter ref="A1:${colName(cols - 1)}$lastData"/>""")

            // 条件格式
            var priority = 1
            sheet.highlights.forEach { h ->
                val c = colIndex(h.column)
                if (c == null || c >= cols) { warnings += "「${names[si]}」：条件格式的列「${h.column}」没找到"; return@forEach }
                if (data.isEmpty()) return@forEach
                val range = "${colName(c)}2:${colName(c)}$lastData"
                val dxf = when (h.color.lowercase()) { "green", "绿" -> 1; "yellow", "黄" -> 2; else -> 0 }
                val v = h.value.trim()
                // 公式里的字符串要先 doubling 再 XML 转义：顺序反了，Excel 读到的引号就不配对。
                val vq = v.replace("\"", "\"\"")
                val formulaValue = if (NUM.matches(v.replace(",", ""))) v.replace(",", "") else "\"" + esc(vq) + "\""
                val op = mapOf(">" to "greaterThan", "<" to "lessThan", ">=" to "greaterThanOrEqual", "<=" to "lessThanOrEqual", "=" to "equal", "==" to "equal", "!=" to "notEqual")
                val rule = when (h.op.trim().lowercase()) {
                    in op.keys -> """<cfRule type="cellIs" dxfId="$dxf" priority="${priority++}" operator="${op[h.op.trim()]}"><formula>$formulaValue</formula></cfRule>"""
                    "contains" -> """<cfRule type="containsText" dxfId="$dxf" priority="${priority++}" operator="containsText" text="${esc(v)}"><formula>NOT(ISERROR(SEARCH("${esc(vq)}",${colName(c)}2)))</formula></cfRule>"""
                    "top", "bottom" -> """<cfRule type="top10" dxfId="$dxf" priority="${priority++}" rank="${v.toIntOrNull()?.coerceIn(1, 500) ?: 3}"${if (h.op.trim().lowercase() == "bottom") " bottom=\"1\"" else ""}/>"""
                    "databar" -> """<cfRule type="dataBar" priority="${priority++}"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF${t.accent}"/></dataBar></cfRule>"""
                    else -> { warnings += "「${names[si]}」：不认识的条件「${h.op}」（能用 > < >= <= = != contains top bottom databar）"; null }
                } ?: return@forEach
                sb.append("""<conditionalFormatting sqref="$range">$rule</conditionalFormatting>""")
            }
            sb.append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")

            // 图表：放在表格右边，一张接一张往下排
            val anchors = StringBuilder()
            val drawingRels = StringBuilder()
            sheet.charts.forEachIndexed { k, ch ->
                val catRaw = colIndex(ch.categoryColumn)
                // 分类列可以是「ZZ」这种字母：字母超出表宽时不能直接当下标用，否则下面 it[cat] 越界崩溃
                val cat = catRaw?.takeIf { it < cols } ?: 0
                if (catRaw != null && catRaw >= cols) warnings += "「${names[si]}」的图「${ch.title}」：分类列「${ch.categoryColumn}」超出表宽"
                val vals = ch.valueColumns.mapNotNull { name -> colIndex(name).also { if (it == null) warnings += "「${names[si]}」的图「${ch.title}」：列「$name」没找到" } }
                    .filter { it < cols && it != cat }.ifEmpty { (0 until cols).filter { it != cat && numericCol[it] }.take(3) }
                if (vals.isEmpty() || data.isEmpty()) { warnings += "「${names[si]}」的图「${ch.title}」没有能画的数"; return@forEachIndexed }
                val chart = PptChart.Chart(ch.type, cells.map { it[cat].text ?: it[cat].number?.let(::num).orEmpty() },
                    vals.map { c -> PptChart.Series(header.getOrElse(c) { colName(c) }, cells.map { it[c].number ?: 0.0 }) }, ch.unit, ch.title)
                val problems = mutableListOf<String>()
                val clean = PptChart.clean(chart, problems) ?: return@forEachIndexed
                problems.forEach { warnings += "「${names[si]}」的图「${ch.title}」：$it" }
                chartNo++
                val used = if (clean.series.size < vals.size) vals.take(clean.series.size) else vals
                // 图上的数用和那一列一样的格式（百分比列画出来也是百分比）
                val codes = mapOf("int" to "#,##0", "dec" to "#,##0.00", "pct" to "0%", "pct1" to "0.0%", "money" to "\"¥\"#,##0.00", "date" to "yyyy-mm-dd")
                val refs = PptChart.Refs(names[si], cat, used, 1, 2, lastData, used.map { codes[colFormat[it]] ?: "General" })
                chartFiles += "xl/charts/chart$chartNo.xml" to PptChart.xml(clean, t, refs)
                val rid = "rId${k + 1}"
                drawingRels.append("""<Relationship Id="$rid" Type="$REL/chart" Target="../charts/chart$chartNo.xml"/>""")
                val fromCol = cols + 1; val fromRow = 1 + k * 18
                anchors.append("""<xdr:twoCellAnchor editAs="oneCell"><xdr:from><xdr:col>$fromCol</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$fromRow</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>""" +
                    """<xdr:to><xdr:col>${fromCol + 8}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${fromRow + 16}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>""" +
                    """<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="${k + 2}" name="Chart ${k + 1}"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm>""" +
                    """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="$rid"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>""")
            }
            if (anchors.isNotEmpty()) {
                val d = si + 1
                drawings += d
                sb.append("""<drawing r:id="rId1"/>""")
                files += "xl/worksheets/_rels/sheet$d.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/drawing" Target="../drawings/drawing$d.xml"/></Relationships>"""
                files += "xl/drawings/drawing$d.xml" to """$XML<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$REL">$anchors</xdr:wsDr>"""
                files += "xl/drawings/_rels/drawing$d.xml.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$drawingRels</Relationships>"""
            }
            sb.append("</worksheet>")
            files += "xl/worksheets/sheet${si + 1}.xml" to sb.toString()
        }
        files += chartFiles

        // ---------- 样式表 ----------
        val line = "FFE5E7EB"
        val styles = StringBuilder("""$XML<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        styles.append("""<numFmts count="3"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd"/><numFmt numFmtId="165" formatCode="0.0%"/><numFmt numFmtId="166" formatCode="&quot;¥&quot;#,##0.00"/></numFmts>""")
        // 字体：0 普通、1 表头白色加粗、2 合计加粗
        styles.append("""<fonts count="3"><font><sz val="11"/><color rgb="FF${t.text}"/><name val="$FONT"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="$FONT"/></font><font><b/><sz val="11"/><color rgb="FF${t.title}"/><name val="$FONT"/></font></fonts>""")
        // 底色：2 表头主色、3 隔行浅底、4 合计浅色
        styles.append("""<fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF${t.primary}"/><bgColor indexed="64"/></patternFill></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF${t.card}"/><bgColor indexed="64"/></patternFill></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF${t.soft}"/><bgColor indexed="64"/></patternFill></fill></fills>""")
        // 边框：1 细横线、2 合计上面一条主色线
        styles.append("""<borders count="3"><border><left/><right/><top/><bottom/><diagonal/></border>""" +
            """<border><left/><right/><top/><bottom style="thin"><color rgb="$line"/></bottom><diagonal/></border>""" +
            """<border><left/><right/><top style="medium"><color rgb="FF${t.primary}"/></top><bottom style="thin"><color rgb="$line"/></bottom><diagonal/></border></borders>""")
        styles.append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="${xfs.size}">""")
        xfs.keys.forEachIndexed { idx, key ->
            if (idx == 0) { styles.append("""<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>"""); return@forEachIndexed }
            val (format, header, band, total) = key.split("|").let { listOf(it[0], it[1], it[2], it[3]) }
            val fmt = FORMATS.getValue(format)
            val font = when { header == "true" -> 1; total == "true" -> 2; else -> 0 }
            val fill = when { header == "true" -> 2; total == "true" -> 4; band == "true" -> 3; else -> 0 }
            val border = if (total == "true") 2 else 1
            val align = if (header == "true") """<alignment horizontal="center" vertical="center" wrapText="1"/>""" else """<alignment vertical="center"/>"""
            styles.append("""<xf numFmtId="$fmt" fontId="$font" fillId="$fill" borderId="$border" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">$align</xf>""")
        }
        styles.append("""</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
        // 条件格式的三种颜色：0 红、1 绿、2 黄（Excel 自带的那几种）
        styles.append("""<dxfs count="3"><dxf><font><color rgb="FF9C0006"/></font><fill><patternFill><bgColor rgb="FFFFC7CE"/></patternFill></fill></dxf>""" +
            """<dxf><font><color rgb="FF006100"/></font><fill><patternFill><bgColor rgb="FFC6EFCE"/></patternFill></fill></dxf>""" +
            """<dxf><font><color rgb="FF9C5700"/></font><fill><patternFill><bgColor rgb="FFFFEB9C"/></patternFill></fill></dxf></dxfs></styleSheet>""")

        val all = mutableListOf<Pair<String, String>>()
        all += "[Content_Types].xml" to buildString {
            append("""$XML<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            list.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
            drawings.forEach { append("""<Override PartName="/xl/drawings/drawing$it.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""") }
            (1..chartNo).forEach { append("""<Override PartName="/xl/charts/chart$it.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>""") }
            append("</Types>")
        }
        all += "_rels/.rels" to """$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        all += "xl/workbook.xml" to buildString {
            append("""$XML<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$REL"><bookViews><workbookView activeTab="0"/></bookViews><sheets>""")
            names.forEachIndexed { i, n -> append("""<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
            append("</sheets>")
            // 筛选要的隐藏名字；打开时把公式（合计、模型写的公式）都算一遍
            val filters = list.indices.mapNotNull { i ->
                val rows = list[i].rows; val cols = rows.maxOfOrNull { it.size } ?: 0
                if (rows.size > 1 && cols > 0) """<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">'${esc(names[i].replace("'", "''"))}'!${'$'}A${'$'}1:${'$'}${colName(cols - 1)}${'$'}${rows.size}</definedName>""" else null
            }
            if (filters.isNotEmpty()) append("<definedNames>").append(filters.joinToString("")).append("</definedNames>")
            append("""<calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")
        }
        all += "xl/_rels/workbook.xml.rels" to buildString {
            append("""$XML<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            list.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="$REL/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
            append("""<Relationship Id="rId${list.size + 1}" Type="$REL/styles" Target="styles.xml"/></Relationships>""")
        }
        all += "xl/styles.xml" to styles.toString()
        all += files
        OfficeWriter.zipParts(out, all)
        return warnings
    }
}
