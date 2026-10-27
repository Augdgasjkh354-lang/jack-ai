package com.jack.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 用 Compose 自己画 Markdown 的一段（不用 TextView）。
 *
 * 为什么：回答输出时文字每帧都在变。TextView 是嵌在 Compose 里的旧式控件，它先按新文字画、下一帧才把高度和位置报给 Compose，
 * 中间那一帧字会画出自己的框、压到下面的卡片和按钮上，接着又跳回去——就是「字和框叠在一起、上下乱跳」。
 * 用 Compose 画，量和画在同一帧，不会错位。带公式的段落 Compose 画不了，仍交给 Markwon。
 */
internal sealed interface MdPart {
    data class Para(val text: String) : MdPart
    data class Heading(val level: Int, val text: String) : MdPart
    data class Item(val marker: String, val ordered: Boolean, val depth: Int, val text: String) : MdPart
    data class Quote(val text: String) : MdPart
    data class Code(val code: String) : MdPart
    data object Rule : MdPart
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val ITEM = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val MATH = Regex("""\$\$|\\\(|\\\[|(?<![\$\\])\$(?!\$)[^\$\n]+?(?<![\$\\])\$(?![\$\d])""")

/** 有公式的段落交给 Markwon（它能画 LaTeX）。 */
internal fun hasMath(block: String) = MATH.containsMatchIn(block)

/** 把一段拆成小块：标题、列表项、引用、代码、分隔线、普通段落。没写完的代码块（输出中）也算代码块。 */
internal fun parseParts(block: String): List<MdPart> {
    val out = mutableListOf<MdPart>()
    val para = StringBuilder()
    val quote = StringBuilder()
    fun flushPara() { if (para.isNotBlank()) out += MdPart.Para(para.toString().trim('\n')); para.clear() }
    fun flushQuote() { if (quote.isNotBlank()) out += MdPart.Quote(quote.toString().trim('\n')); quote.clear() }
    val lines = block.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        if (t.startsWith("```") || t.startsWith("~~~")) {
            flushPara(); flushQuote()
            val fence = t.take(3)
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
            out += MdPart.Code(code.toString().trimEnd('\n'))
            i++ // 跳过结尾的 ```
            continue
        }
        if (t.startsWith(">")) {
            flushPara()
            quote.append(t.removePrefix(">").trimStart()).append('\n')
            i++; continue
        }
        flushQuote()
        val h = HEADING.matchEntire(t)
        val item = ITEM.matchEntire(line)
        when {
            h != null -> { flushPara(); out += MdPart.Heading(h.groupValues[1].length, h.groupValues[2].trim().trimEnd('#').trim()) }
            RULE.matches(t) && t.length >= 3 && item == null -> { flushPara(); out += MdPart.Rule }
            item != null -> {
                flushPara()
                val marker = item.groupValues[2]
                out += MdPart.Item(marker, marker.first().isDigit(), (item.groupValues[1].replace("\t", "    ").length / 2).coerceAtMost(3), item.groupValues[3])
            }
            // 列表项下面缩进的续行接在那一项后面
            t.isNotEmpty() && line.startsWith("  ") && out.lastOrNull() is MdPart.Item && para.isEmpty() -> {
                val last = out.removeAt(out.lastIndex) as MdPart.Item
                out += last.copy(text = last.text + "\n" + t)
            }
            else -> para.append(line).append('\n')
        }
        i++
    }
    flushPara(); flushQuote()
    return out
}

private val INLINE_MD = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|~~(.+?)~~|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w_])|<br\s*/?>|(https?://[^\s<>()（）「」，。；！？、]+)"""
)

/** 一行里的格式：加粗、斜体、代码、删除线、链接（能点）、裸链接、<br>。 */
internal fun inlineMarkdown(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var last = 0
    for (m in INLINE_MD.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.9.em)) { append(g[3]) }
            g[4].isNotEmpty() -> withLink(LinkAnnotation.Url(g[5], linkStyle)) { append(g[4]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }
            g[7].isNotEmpty() || g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[7].ifEmpty { g[8] }) }
            g[9].isNotEmpty() -> withLink(LinkAnnotation.Url(g[9], linkStyle)) { append(g[9]) }
            else -> append('\n')
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** 「**小标题**：说明」「**小标题：**说明」「**小标题**」换行「说明」，冒号或破折号、换行才算分开；「**结论**。后文」不拆。 */
private val ITEM_TITLE = Regex("""^\*\*([^*\n]{1,40}?)\*\*(?:\s*[：:—–]\s*|\s*-\s+|\s*\n\s*)(.+)$""", RegexOption.DOT_MATCHES_ALL)
private val ITEM_TITLE_COLON = Regex("""^\*\*([^*\n]{1,40}?[：:])\*\*\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)

/** 「**小标题**：说明」这样的列表项拆成（小标题，说明）；不是这个样子返回（null，原文）。 */
internal fun itemTitle(text: String): Pair<String?, String> {
    val t = text.trim()
    val m = ITEM_TITLE.matchEntire(t) ?: ITEM_TITLE_COLON.matchEntire(t) ?: return null to text
    val rest = m.groupValues[2].trim()
    return if (rest.isEmpty()) null to text else m.groupValues[1].trim().trimEnd('：', ':') to rest
}

/** 画一段 Markdown（不含表格、不含公式；那两种由 MarkdownBlocks 分给别处）。 */
@Composable
internal fun ComposeMarkdown(block: String, color: Color, codeBackground: Color, modifier: Modifier = Modifier, textSizeSp: Float = 16f) {
    val colors = MaterialTheme.colorScheme
    val parts = remember(block) { parseParts(block) }
    val body = TextStyle(color = color, fontSize = textSizeSp.sp, lineHeight = (textSizeSp * 1.6f).sp, letterSpacing = 0.02.em)
    val accent = colors.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { p ->
            when (p) {
                is MdPart.Para -> Text(inlineMarkdown(p.text, codeBackground, accent), style = body)
                is MdPart.Heading -> {
                    // 标题粗一点、大一点，和正文拉开层级（一级、二级用粗体）
                    val scale = listOf(1.35f, 1.2f, 1.08f, 1f, 1f, 1f)[p.level - 1]
                    Text(
                        inlineMarkdown(p.text, codeBackground, accent),
                        style = body.copy(fontSize = (textSizeSp * scale).sp, lineHeight = (textSizeSp * scale * 1.4f).sp,
                            fontWeight = if (p.level <= 2) FontWeight.Bold else FontWeight.SemiBold, letterSpacing = 0.sp),
                        modifier = Modifier.padding(top = if (p.level <= 2) 8.dp else 4.dp),
                    )
                }
                is MdPart.Item -> Row(Modifier.padding(start = (p.depth * 18).dp, top = if (p.ordered && p.depth == 0) 4.dp else 0.dp)) {
                    if (p.ordered && p.depth == 0) {
                        // 第一层编号放进浅灰小圆里
                        Box(
                            Modifier.padding(top = ((textSizeSp * 1.6f - 24f) / 2).coerceAtLeast(0f).dp).size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape).background(colors.surfaceContainerHigh),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Text(p.marker.trimEnd('.', ')'), style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                    } else Text(
                        if (p.ordered) p.marker else if (p.depth == 0) "•" else "◦",
                        style = body.copy(color = if (p.ordered) color else colors.outline, fontWeight = if (p.ordered) FontWeight.Medium else null),
                        modifier = Modifier.width(if (p.ordered) 26.dp else 18.dp),
                    )
                    val (title, rest) = itemTitle(p.text)
                    if (title != null) Column(Modifier.weight(1f)) {
                        // 「**小标题**：说明」：小标题单独一行加粗，说明换行用浅一点的字
                        Text(inlineMarkdown(title, codeBackground, accent), style = body.copy(fontWeight = FontWeight.SemiBold))
                        Text(inlineMarkdown(rest, codeBackground, accent), style = body.copy(color = colors.onSurfaceVariant))
                    } else Text(inlineMarkdown(p.text, codeBackground, accent), style = body, modifier = Modifier.weight(1f))
                }
                is MdPart.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.outlineVariant))
                    Text(inlineMarkdown(p.text, codeBackground, accent), style = body.copy(color = color.copy(alpha = 0.8f)),
                        modifier = Modifier.padding(start = 12.dp))
                }
                is MdPart.Code -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBackground)
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(p.code, style = body.copy(fontFamily = FontFamily.Monospace, fontSize = (textSizeSp * 0.86f).sp,
                        lineHeight = (textSizeSp * 1.35f).sp, letterSpacing = 0.sp), softWrap = false)
                }
                MdPart.Rule -> HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.outlineVariant)
            }
        }
    }
}
