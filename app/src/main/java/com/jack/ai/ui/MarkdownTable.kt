package com.jack.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一张 Markdown 表格：表头、每列的对齐方式、数据行。 */
internal data class Table(val header: List<String>, val align: List<TextAlign>, val rows: List<List<String>>)

private val SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

/** 一行表格切成格子：去掉两头的竖线，转义的 \| 不算分隔。 */
private fun cells(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.drop(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    return t.split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }
}

/** 是不是一张完整的表格（第二行是 |---|---| 分隔行）。 */
internal fun parseTable(block: String): Table? {
    val lines = block.lines().filter { it.isNotBlank() }
    if (lines.size < 2 || '|' !in lines[0] || !SEPARATOR.matches(lines[1])) return null
    val header = cells(lines[0])
    val align = cells(lines[1]).map {
        when {
            it.startsWith(":") && it.endsWith(":") -> TextAlign.Center
            it.endsWith(":") -> TextAlign.End
            else -> TextAlign.Start
        }
    }
    val n = header.size
    val rows = lines.drop(2).filter { '|' in it }.map { r -> cells(r).let { c -> (c + List(n) { "" }).take(n) } }
    return Table(header, (align + List(n) { TextAlign.Start }).take(n), rows)
}

/**
 * 一段里表格和文字挨着写（中间没空行）时拆开：表格单独画，文字照常。
 * 表格从「含竖线的一行 + 分隔行」开始，到第一行不含竖线的为止。
 */
internal fun splitTables(block: String): List<String> {
    val lines = block.split('\n')
    if (lines.none { SEPARATOR.matches(it) }) return listOf(block)
    val out = mutableListOf<String>()
    val text = StringBuilder()
    var i = 0
    fun flush() { if (text.isNotBlank()) out += text.toString().trimEnd('\n'); text.clear() }
    while (i < lines.size) {
        if ('|' in lines[i] && i + 1 < lines.size && SEPARATOR.matches(lines[i + 1])) {
            flush()
            var j = i + 2
            while (j < lines.size && '|' in lines[j]) j++
            out += lines.subList(i, j).joinToString("\n")
            i = j
        } else {
            text.append(lines[i]).append('\n')
            i++
        }
    }
    flush()
    return out
}

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)|<br\s*/?>|~~(.+?)~~|\*(.+?)\*""")

/** 格子里的行内格式：加粗、代码、链接文字、换行；其余原样。 */
internal fun inline(text: String, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[3]) }
            g[4].isNotEmpty() -> append(g[4])
            g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) { append(g[6]) }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(g[7]) }
            else -> append('\n')
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** 估一列要多宽：中文按一个字 14dp、其他 7.5dp 算最长的一格，夹在 52～220dp 之间。 */
internal fun columnWidth(cells: List<String>): Float {
    val longest = cells.maxOfOrNull { c ->
        c.replace(Regex("""\*\*|`|<br\s*/?>"""), "").sumOf { ch -> if (ch.code > 0x2E80) 14.0 else 7.5 }
    } ?: 0.0
    return (longest + 26).toFloat().coerceIn(52f, 220f)
}

/**
 * 列宽排进屏幕：不够宽时按比例铺满；太宽时先把宽列压窄（字换行），压到 84dp 还放不下才横向滑动。
 */
internal fun fitColumns(widths: List<Float>, room: Float): List<Float> {
    val total = widths.sum()
    if (total <= room) return widths.map { it * room / total }
    val floor = 84f
    val shrinkable = widths.sumOf { (it - floor).coerceAtLeast(0f).toDouble() }.toFloat()
    if (shrinkable <= 0f) return widths
    val cut = ((total - room) / shrinkable).coerceAtMost(1f)
    return widths.map { if (it > floor) it - (it - floor) * cut else it }
}

/**
 * 回答里的表格：圆角边框、表头底色、行间细线、隔行浅底；列宽按内容估，太宽时横向滑动，不够宽时按比例铺满。
 * 字可以长按选中复制。
 */
@Composable
internal fun MarkdownTable(t: Table, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val widths = t.header.indices.map { c -> columnWidth(listOf(t.header[c]) + t.rows.map { it.getOrElse(c) { "" } }) }
    BoxWithConstraints(modifier) {
        val w: List<Dp> = fitColumns(widths, maxWidth.value - 2).map { it.dp }
        SelectionContainer {
            Column(
                Modifier
                    .clip(shape)
                    .border(1.dp, colors.outlineVariant, shape)
                    .horizontalScroll(rememberScrollState()),
            ) {
                TableRow(t.header, w, t.align, header = true, shade = colors.surfaceContainerHigh)
                t.rows.forEachIndexed { i, r ->
                    androidx.compose.foundation.layout.Box(Modifier.width(w.fold(0.dp) { a, b -> a + b }).height(1.dp).background(colors.outlineVariant.copy(alpha = 0.6f)))
                    TableRow(r, w, t.align, header = false, shade = if (i % 2 == 1) colors.surfaceContainerLow else colors.surface)
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, widths: List<Dp>, align: List<TextAlign>, header: Boolean, shade: androidx.compose.ui.graphics.Color) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.height(IntrinsicSize.Min).background(shade)) {
        cells.forEachIndexed { c, text ->
            Text(
                inline(text, colors.surfaceVariant),
                modifier = Modifier.width(widths[c]).fillMaxHeight().padding(horizontal = 12.dp, vertical = if (header) 9.dp else 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                fontWeight = if (header) FontWeight.SemiBold else null,
                color = if (header) colors.onSurface else colors.onSurface.copy(alpha = 0.92f),
                textAlign = align.getOrElse(c) { TextAlign.Start },
            )
        }
    }
}
