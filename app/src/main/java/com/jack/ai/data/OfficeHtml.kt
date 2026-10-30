package com.jack.ai.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Word、Excel、PPT 转成网页，在 App 里看（手机上多半没装 Office）。
 * 只求看得清：标题、粗斜体、列表、表格、图片、每张表、每页幻灯片；复杂排版（分栏、文本框位置、公式）不还原。
 */
object OfficeHtml {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** 表格最多显示这么多行、列；图片加起来最多这么大，再多就不内嵌了。 */
    const val MAX_ROWS = 1000
    const val MAX_COLS = 60
    const val MAX_IMAGES = 12_000_000

    fun supports(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("docx", "xlsx", "pptx")

    /** 转成 body 里的 HTML（不含 <html>，外面套样式的事交给 [page]）。 */
    fun body(file: File): String = ZipFile(file).use { z ->
        when (file.extension.lowercase()) {
            "docx" -> Docx(z).render()
            "xlsx" -> xlsx(z)
            "pptx" -> pptx(z)
            else -> throw IllegalArgumentException("不支持这种文件")
        }
    }

    /** 整页：按 App 的深浅色配好颜色。 */
    fun page(body: String, fg: String, bg: String, line: String, accent: String): String = """<!doctype html><html><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{margin:0;padding:16px;background:$bg;color:$fg;font:16px/1.65 sans-serif;word-wrap:break-word}
h1,h2,h3,h4{line-height:1.3;margin:1em 0 .5em}h1{font-size:1.5em}h2{font-size:1.3em}h3{font-size:1.15em}
p{margin:.45em 0}img{max-width:100%;height:auto}
a{color:$accent}
.wrap{overflow-x:auto;margin:.6em 0}
table{border-collapse:collapse;font-size:14px}
td,th{border:1px solid $line;padding:4px 8px;vertical-align:top;white-space:pre-wrap}
th{font-weight:600;background:rgba(127,127,127,.12)}
.sheet{font-weight:600;margin:1.2em 0 .4em}
.slide{border:1px solid $line;border-radius:12px;padding:12px 16px;margin:0 0 16px}
.no{font-size:12px;opacity:.6}
.note{opacity:.6;font-size:13px}
</style></head><body>$body</body></html>"""

    // ---------- 公共 ----------

    internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun xml(z: ZipFile, path: String): Element? {
        val e = z.getEntry(path) ?: return null
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        return z.getInputStream(e).use { f.newDocumentBuilder().parse(it).documentElement }
    }

    private fun Node.kids(): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    private fun Element.kid(ns: String, name: String) = kids().firstOrNull { it.namespaceURI == ns && it.localName == name }
    private fun Element.all(ns: String, name: String): List<Element> {
        val l = getElementsByTagNameNS(ns, name)
        return (0 until l.length).map { l.item(it) as Element }
    }
    private fun Element.attr(ns: String, name: String): String = getAttributeNS(ns, name)

    /** 一个部件的关系表：rId → 目标路径（相对 zip 根）。 */
    private fun rels(z: ZipFile, part: String): Map<String, String> {
        val dir = part.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val root = xml(z, relsPath) ?: return emptyMap()
        return root.kids().filter { it.namespaceURI == PKG_REL || it.localName == "Relationship" }.associate { r ->
            r.getAttribute("Id") to resolve(dir, r.getAttribute("Target"))
        }
    }

    internal fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (if (dir.isEmpty()) emptyList() else dir.split('/')).toMutableList()
        target.split('/').forEach { p ->
            when (p) { ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); ".", "" -> {}; else -> parts += p }
        }
        return parts.joinToString("/")
    }

    /** 图片内嵌成 data URI；超了总量的不再内嵌。 */
    private class Images(val z: ZipFile) {
        var used = 0
        fun uri(path: String): String? {
            val e = z.getEntry(path) ?: return null
            if (used + e.size > MAX_IMAGES) return null
            val mime = when (path.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"
                else -> return null  // emf、wmf 这类浏览器画不了
            }
            val bytes = z.getInputStream(e).use { it.readBytes() }
            used += bytes.size
            return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
        }
    }

    // ---------- Word ----------

    private class Docx(val z: ZipFile) {
        val rels = rels(z, "word/document.xml")
        val images = Images(z)
        /** 样式编号 → 标题级别（0 不是标题）。 */
        val headings: Map<String, Int> = run {
            val root = xml(z, "word/styles.xml") ?: return@run emptyMap()
            root.all(W, "style").associate { s ->
                val name = s.kid(W, "name")?.attr(W, "val").orEmpty().lowercase()
                val level = when {
                    name == "title" -> 1
                    name.startsWith("heading ") -> name.removePrefix("heading ").trim().toIntOrNull() ?: 0
                    else -> 0
                }
                s.attr(W, "styleId") to level
            }
        }
        val out = StringBuilder()

        fun render(): String {
            val body = xml(z, "word/document.xml")?.kid(W, "body") ?: return "<p class=note>这个文档是空的</p>"
            blocks(body)
            return out.toString().ifBlank { "<p class=note>这个文档是空的</p>" }
        }

        fun blocks(parent: Element) {
            var inList = false
            for (e in parent.kids()) {
                val isItem = e.localName == "p" && e.kid(W, "pPr")?.kid(W, "numPr") != null
                if (inList && !isItem) { out.append("</ul>"); inList = false }
                when (e.localName) {
                    "p" -> {
                        if (isItem && !inList) { out.append("<ul>"); inList = true }
                        paragraph(e, isItem)
                    }
                    "tbl" -> table(e)
                    "sdt" -> e.kid(W, "sdtContent")?.let { blocks(it) }
                }
            }
            if (inList) out.append("</ul>")
        }

        fun paragraph(p: Element, item: Boolean) {
            val pPr = p.kid(W, "pPr")
            val style = pPr?.kid(W, "pStyle")?.attr(W, "val").orEmpty()
            val level = (headings[style] ?: style.removePrefix("Heading").toIntOrNull()?.takeIf { style.startsWith("Heading") } ?: 0).coerceIn(0, 4)
            val align = when (pPr?.kid(W, "jc")?.attr(W, "val")) { "center" -> "center"; "right", "end" -> "right"; else -> null }
            val inner = StringBuilder().also { runs(p, it) }.toString()
            val tag = when { item -> "li"; level > 0 -> "h$level"; else -> "p" }
            if (inner.isBlank() && tag == "p") { out.append("<p>&nbsp;</p>"); return }
            out.append("<$tag").append(align?.let { " style=\"text-align:$it\"" } ?: "").append('>').append(inner).append("</$tag>")
        }

        fun runs(parent: Element, sb: StringBuilder) {
            for (e in parent.kids()) when (e.localName) {
                "r" -> run(e, sb)
                "hyperlink" -> {
                    val href = rels[e.attr(R, "id")]?.takeIf { it.startsWith("http") }
                    if (href != null) sb.append("<a href=\"").append(esc(href)).append("\">")
                    runs(e, sb)
                    if (href != null) sb.append("</a>")
                }
                "ins", "smartTag", "fldSimple", "sdt", "sdtContent" -> runs(e, sb)
            }
        }

        fun run(r: Element, sb: StringBuilder) {
            val pr = r.kid(W, "rPr")
            fun on(name: String) = pr?.kid(W, name)?.let { it.attr(W, "val") !in setOf("0", "false", "none") } == true
            val b = on("b"); val i = on("i"); val u = on("u"); val s = on("strike")
            val text = StringBuilder()
            for (e in r.kids()) when (e.localName) {
                "t" -> text.append(esc(e.textContent))
                "tab" -> text.append("&emsp;")
                "br", "cr" -> text.append("<br>")
                "drawing", "pict" -> e.all(A, "blip").forEach { blip ->
                    rels[blip.attr(R, "embed")]?.let(images::uri)?.let { text.append("<img src=\"").append(it).append("\">") }
                }
            }
            if (text.isEmpty()) return
            var t = text.toString()
            if (b) t = "<b>$t</b>"
            if (i) t = "<i>$t</i>"
            if (u) t = "<u>$t</u>"
            if (s) t = "<s>$t</s>"
            sb.append(t)
        }

        fun table(tbl: Element) {
            out.append("<div class=wrap><table>")
            tbl.kids().filter { it.localName == "tr" }.take(MAX_ROWS).forEach { tr ->
                out.append("<tr>")
                tr.kids().filter { it.localName == "tc" }.forEach { tc ->
                    val span = tc.kid(W, "tcPr")?.kid(W, "gridSpan")?.attr(W, "val")?.toIntOrNull()
                    out.append(if (span != null && span > 1) "<td colspan=$span>" else "<td>")
                    val before = out.length
                    blocks(tc)
                    // 单元格里只有一段就不要段落的上下空隙
                    val cell = out.substring(before)
                    if (cell.startsWith("<p>") && cell.endsWith("</p>") && cell.indexOf("<p>", 1) < 0) {
                        out.setLength(before); out.append(cell.removePrefix("<p>").removeSuffix("</p>"))
                    }
                    out.append("</td>")
                }
                out.append("</tr>")
            }
            out.append("</table></div>")
        }
    }

    // ---------- Excel ----------

    private fun xlsx(z: ZipFile): String {
        val shared = xml(z, "xl/sharedStrings.xml")?.kids()?.filter { it.localName == "si" }
            ?.map { si -> si.all(S, "t").joinToString("") { it.textContent } }.orEmpty()
        val wbRels = rels(z, "xl/workbook.xml")
        val sheets = xml(z, "xl/workbook.xml")?.kid(S, "sheets")?.kids().orEmpty().map { s ->
            s.getAttribute("name") to (wbRels[s.attr(R, "id")] ?: "")
        }.filter { it.second.isNotEmpty() }
        if (sheets.isEmpty()) return "<p class=note>这个表格是空的</p>"
        val sb = StringBuilder()
        for ((name, path) in sheets) {
            sb.append("<div class=sheet>").append(esc(name)).append("</div>")
            val data = xml(z, path)?.kid(S, "sheetData")
            val grid = sortedMapOf<Int, MutableMap<Int, String>>()
            var cut = false
            data?.kids()?.filter { it.localName == "row" }?.forEachIndexed { idx, row ->
                val r = row.getAttribute("r").toIntOrNull() ?: (idx + 1)
                if (grid.size >= MAX_ROWS) { cut = true; return@forEachIndexed }
                row.kids().filter { it.localName == "c" }.forEachIndexed { ci, c ->
                    val col = c.getAttribute("r").takeIf { it.isNotEmpty() }?.let(::column) ?: ci
                    if (col >= MAX_COLS) { cut = true; return@forEachIndexed }
                    val v = c.kid(S, "v")?.textContent.orEmpty()
                    val value = when (c.getAttribute("t")) {
                        "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> c.kid(S, "is")?.all(S, "t")?.joinToString("") { it.textContent }.orEmpty()
                        "b" -> if (v == "1") "TRUE" else "FALSE"
                        else -> v
                    }
                    if (value.isNotEmpty()) grid.getOrPut(r) { mutableMapOf() }[col] = value
                }
            }
            if (grid.isEmpty()) { sb.append("<p class=note>（空）</p>"); continue }
            val cols = (grid.values.flatMap { it.keys }.maxOrNull() ?: 0) + 1
            sb.append("<div class=wrap><table><tr><th></th>")
            (0 until cols).forEach { sb.append("<th>").append(columnName(it)).append("</th>") }
            sb.append("</tr>")
            grid.forEach { (r, cells) ->
                sb.append("<tr><th>").append(r).append("</th>")
                (0 until cols).forEach { c -> sb.append("<td>").append(esc(cells[c].orEmpty())).append("</td>") }
                sb.append("</tr>")
            }
            sb.append("</table></div>")
            if (cut) sb.append("<p class=note>只显示了前 $MAX_ROWS 行、$MAX_COLS 列</p>")
        }
        return sb.toString()
    }

    /** 「B3」→ 1（从 0 数）。 */
    internal fun column(ref: String): Int = ref.takeWhile { it.isLetter() }.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    internal fun columnName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.append('A' + m); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    // ---------- PPT ----------

    private fun pptx(z: ZipFile): String {
        val presRels = rels(z, "ppt/presentation.xml")
        val order = xml(z, "ppt/presentation.xml")?.kid(P, "sldIdLst")?.kids().orEmpty().mapNotNull { presRels[it.attr(R, "id")] }
        val slides = order.ifEmpty {
            z.entries().toList().map { it.name }.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        }
        if (slides.isEmpty()) return "<p class=note>这个演示文稿是空的</p>"
        val images = Images(z)
        val sb = StringBuilder()
        slides.forEachIndexed { i, path ->
            val root = xml(z, path) ?: return@forEachIndexed
            val rels = rels(z, path)
            sb.append("<div class=slide><div class=no>第 ${i + 1} 页</div>")
            val tree = root.kid(P, "cSld")?.kid(P, "spTree")
            if (tree != null) shapes(tree, rels, images, sb)
            sb.append("</div>")
        }
        return sb.toString()
    }

    private fun shapes(tree: Element, rels: Map<String, String>, images: Images, sb: StringBuilder) {
        for (e in tree.kids()) when (e.localName) {
            "sp" -> {
                val ph = e.all(P, "ph").firstOrNull()?.getAttribute("type").orEmpty()
                val title = ph == "title" || ph == "ctrTitle"
                val paras = e.kid(P, "txBody")?.kids()?.filter { it.localName == "p" }.orEmpty().map { p ->
                    p.all(A, "t").joinToString("") { esc(it.textContent) }
                }.filter { it.isNotBlank() }
                if (paras.isEmpty()) continue
                if (title) sb.append("<h3>").append(paras.joinToString("<br>")).append("</h3>")
                else if (paras.size == 1) sb.append("<p>").append(paras[0]).append("</p>")
                else { sb.append("<ul>"); paras.forEach { sb.append("<li>").append(it).append("</li>") }; sb.append("</ul>") }
            }
            "pic" -> e.all(A, "blip").forEach { b ->
                rels[b.attr(R, "embed")]?.let(images::uri)?.let { sb.append("<p><img src=\"").append(it).append("\"></p>") }
            }
            "grpSp" -> shapes(e, rels, images, sb)
            "graphicFrame" -> e.all(A, "tbl").forEach { t ->
                sb.append("<div class=wrap><table>")
                t.kids().filter { it.localName == "tr" }.forEach { tr ->
                    sb.append("<tr>")
                    tr.kids().filter { it.localName == "tc" }.forEach { tc -> sb.append("<td>").append(tc.all(A, "t").joinToString("") { esc(it.textContent) }).append("</td>") }
                    sb.append("</tr>")
                }
                sb.append("</table></div>")
            }
        }
    }
}
