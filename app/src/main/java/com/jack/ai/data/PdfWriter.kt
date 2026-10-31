package com.jack.ai.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.io.OutputStream

/** 用安卓自带的 PdfDocument 把 Markdown 排成 A4 的 PDF。 */
object PdfWriter {
    private const val W = 595
    private const val H = 842
    private const val M = 56f
    private val ACCENT = Color.rgb(0xC4, 0x67, 0x3F)
    private val INK = Color.rgb(0x2B, 0x27, 0x22)
    private val GRAY = Color.rgb(0x6E, 0x66, 0x5D)
    private val SOFT = Color.rgb(0xF3, 0xEF, 0xEA)
    private val LINE = Color.rgb(0xDD, 0xD6, 0xCD)

    private class Ctx(val doc: PdfDocument) {
        var pageNo = 0
        var page: PdfDocument.Page? = null
        val canvas: Canvas get() = page!!.canvas
        var y = M
        val width = (W - 2 * M).toInt()

        fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }

        fun finishPage() {
            val p = page ?: return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = GRAY; textAlign = Paint.Align.CENTER }
            p.canvas.drawText("$pageNo", W / 2f, H - 28f, paint)
            doc.finishPage(p)
            page = null
        }

        val bottom get() = H - M
    }

    private fun paint(size: Float, color: Int = INK, bold: Boolean = false, italic: Boolean = false, mono: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, style)
        }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, spacing: Float = 1.35f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    /** **粗体**、*斜体*、`代码`、[文字](链接) 转成带样式的文字。 */
    private fun inline(src: String): CharSequence {
        val sb = SpannableStringBuilder()
        val re = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)|(?<![*\w])\*([^*\s][^*]*?)\*(?![*\w])""")
        var last = 0
        for (m in re.findAll(src)) {
            sb.append(src, last, m.range.first)
            val start = sb.length
            when {
                m.groups[1] != null -> {
                    sb.append(m.groupValues[1])
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groups[2] != null -> {
                    sb.append(m.groupValues[2])
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(SOFT), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groups[3] != null -> sb.append(m.groupValues[3])
                else -> {
                    sb.append(m.groupValues[5])
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        sb.append(src, last, src.length)
        return sb
    }

    /** 把一段排好的文字放到页面上，放不下就自动翻页。 */
    private fun Ctx.place(l: StaticLayout, indent: Float = 0f, before: Float = 0f, after: Float = 0f, bg: Int? = null, bar: Int? = null) {
        if (y > M) y += before
        var line = 0
        while (line < l.lineCount) {
            val firstH = l.getLineBottom(line) - l.getLineTop(line)
            if (y + firstH > bottom) newPage()
            val top = l.getLineTop(line)
            var end = line
            while (end < l.lineCount && y + (l.getLineBottom(end) - top) <= bottom) end++
            if (end == line) end = line + 1
            val h = (l.getLineBottom(end - 1) - top).toFloat()
            val x = M + indent
            bg?.let { canvas.drawRect(x - 8, y - 5, x + l.width + 8, y + h + 5, Paint().apply { color = it }) }
            bar?.let { canvas.drawRect(x - 10, y, x - 7, y + h, Paint().apply { color = it }) }
            canvas.save()
            canvas.translate(x, y - top)
            canvas.clipRect(0f, top.toFloat(), l.width.toFloat(), top + h)
            l.draw(canvas)
            canvas.restore()
            y += h
            line = end
        }
        y += after
    }

    private fun Ctx.table(rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }
        val colW = width / cols
        val head = paint(10f, INK, bold = true)
        val body = paint(10f)
        val border = Paint().apply { color = LINE; style = Paint.Style.STROKE; strokeWidth = 0.7f }
        if (y > M) y += 6f
        rows.forEachIndexed { r, cells ->
            val ls = (0 until cols).map { c -> layout(inline(cells.getOrElse(c) { "" }), if (r == 0) head else body, colW - 12, 1.25f) }
            val rh = ls.maxOf { it.height } + 10f
            // 一行比一页还高时不能整行换页了事（超出的部分会被裁掉）：按页切开画，每页画一段，边框和表头底色跟着切
            var drawn = 0f
            while (drawn < rh) {
                if (bottom - y <= 0f) { newPage(); continue }
                val slice = minOf(bottom - y, rh - drawn)
                if (r == 0) canvas.drawRect(M, y, M + colW * cols, y + slice, Paint().apply { color = SOFT })
                ls.forEachIndexed { c, l ->
                    val x = M + c * colW
                    canvas.drawRect(x, y, x + colW, y + slice, border)
                    canvas.save()
                    canvas.clipRect(x, y, x + colW, y + slice)
                    canvas.translate(x + 6, y + 5 - drawn)
                    l.draw(canvas)
                    canvas.restore()
                }
                drawn += slice
                y += slice
                if (drawn < rh) newPage()
            }
        }
        y += 8f
    }

    fun write(markdown: String, out: OutputStream) {
        val doc = PdfDocument()
        val ctx = Ctx(doc)
        ctx.newPage()
        val bodyP = paint(11f)
        val lines = markdown.replace("\r", "").lines()
        var titleDone = false
        var i = 0
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotBlank()) ctx.place(ctx.run { layout(inline(para.toString().trim()), bodyP, width, 1.5f) }, before = 2f, after = 8f)
            para.clear()
        }
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            when {
                t.isEmpty() -> flushPara()
                t.startsWith("```") -> {
                    flushPara()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                    ctx.place(layout(code.toString().trimEnd(), paint(9.5f, INK, mono = true), ctx.width - 16, 1.3f),
                        indent = 8f, before = 6f, after = 12f, bg = SOFT)
                }
                t.startsWith("|") -> {
                    flushPara()
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                        i++
                    }
                    i--
                    ctx.table(rows)
                }
                Regex("^(#{1,6})\\s+(.*)").matchEntire(t) != null -> {
                    flushPara()
                    val m = Regex("^(#{1,6})\\s+(.*)").matchEntire(t)!!
                    val level = m.groupValues[1].length
                    val text = inline(m.groupValues[2])
                    if (level == 1 && !titleDone) {
                        titleDone = true
                        ctx.place(layout(text, paint(22f, INK, bold = true), ctx.width, 1.25f), after = 6f)
                        ctx.canvas.drawRect(M, ctx.y, M + 48, ctx.y + 3, Paint().apply { color = ACCENT })
                        ctx.y += 18f
                    } else {
                        val (size, color) = when (level) {
                            1, 2 -> 16f to ACCENT
                            3 -> 13f to INK
                            else -> 11.5f to INK
                        }
                        ctx.place(layout(text, paint(size, color, bold = true), ctx.width, 1.25f), before = if (level <= 2) 14f else 8f, after = 6f)
                    }
                }
                t == "---" || t == "***" -> {
                    flushPara()
                    if (ctx.y + 12 > ctx.bottom) ctx.newPage()
                    ctx.y += 6
                    ctx.canvas.drawRect(M, ctx.y, W - M, ctx.y + 0.7f, Paint().apply { color = LINE })
                    ctx.y += 10
                }
                t.startsWith(">") -> {
                    flushPara()
                    ctx.place(layout(inline(t.trimStart('>').trim()), paint(11f, GRAY, italic = true), ctx.width - 14, 1.45f),
                        indent = 14f, before = 4f, after = 8f, bar = ACCENT)
                }
                Regex("^([-*+]|\\d+[.)])\\s+.*").matches(t) -> {
                    flushPara()
                    val depth = (raw.length - raw.trimStart().length) / 2
                    val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)").matchEntire(t)!!
                    val marker = if (m.groupValues[1].first().isDigit()) m.groupValues[1] + " " else "•  "
                    val indent = 10f + depth * 16f
                    val sb = SpannableStringBuilder(marker).append(inline(m.groupValues[2]))
                    ctx.place(layout(sb, bodyP, (ctx.width - indent).toInt(), 1.45f), indent = indent, after = 4f)
                }
                else -> para.append(t).append(' ')
            }
            i++
        }
        flushPara()
        ctx.finishPage()
        doc.writeTo(out)
        doc.close()
    }
}
