package com.jack.ai.ui

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

private fun buildMarkwon(ctx: Context, textColor: Int, codeBg: Int, textSizePx: Float, accent: Int, muted: Int): Markwon =
    Markwon.builder(ctx)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(JLatexMathPlugin.create(textSizePx) { builder ->
            builder.inlinesEnabled(true)
            builder.theme().textColor(textColor)
        })
        .usePlugin(TablePlugin.create(ctx))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                val dp = ctx.resources.displayMetrics.density
                builder.codeBackgroundColor(codeBg)
                    .codeBlockBackgroundColor(codeBg)
                    .codeTextColor(textColor)
                    .codeBlockTextColor(textColor)
                    .codeBlockMargin((12 * dp).toInt())
                    .codeTextSize((textSizePx * 0.86f).toInt())
                    // 聊天里标题不用太大：比正文大一点、加粗就够了
                    .headingTextSizeMultipliers(floatArrayOf(1.3f, 1.2f, 1.1f, 1.0f, 1.0f, 1.0f))
                    .headingBreakHeight(0)
                    .linkColor(accent)
                    .isLinkUnderlined(true)
                    .listItemColor(accent)
                    .bulletWidth((5 * dp).toInt())
                    .blockQuoteColor(muted)
                    .blockQuoteWidth((3 * dp).toInt())
                    .thematicBreakColor(muted)
            }
        })
        .build()

private val BLOCK_LATEX = Regex("""\\\[(.+?)\\]""", RegexOption.DOT_MATCHES_ALL)
private val INLINE_LATEX = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val DOLLAR_BLOCK = Regex("""(?<!\$)\$\$(?!\$)(.+?)(?<!\$)\$\$(?!\$)""", RegexOption.DOT_MATCHES_ALL)
// 结尾的 $ 后面紧跟数字时不算公式结束（$5-$10 这种价格区间），和 Pandoc 的规则一样
private val DOLLAR_INLINE = Regex("""(?<![\$\\])\$(?!\$)([^\$\n]+?)(?<![\$\\])\$(?![\$\d])""")
private val CODE = Regex("""```[\s\S]*?(```|$)|`[^`\n]*`""")

/**
 * DeepSeek 输出公式常用 \( \) \[ \] 和 $ $，而 Markwon 只认 $$ $$。
 * 这里统一转换，代码块里的内容不动。
 */
fun normalizeLatex(src: String): String {
    val sb = StringBuilder()
    var last = 0
    for (m in CODE.findAll(src)) {
        sb.append(convertMath(src.substring(last, m.range.first)))
        sb.append(m.value)
        last = m.range.last + 1
    }
    sb.append(convertMath(src.substring(last)))
    return sb.toString()
}

private fun convertMath(s: String): String {
    if (!s.contains('\\') && !s.contains('$')) return s
    return s
        .replace(BLOCK_LATEX) { "\n$$\n" + it.groupValues[1].trim() + "\n$$\n" }
        .replace(DOLLAR_BLOCK) { m ->
            val body = m.groupValues[1].trim()
            if (body.contains('\n')) "\n$$\n$body\n$$\n" else "\n$$\n$body\n$$\n"
        }
        .replace(INLINE_LATEX) { "$$" + it.groupValues[1].trim() + "$$" }
        .replace(DOLLAR_INLINE) { m ->
            val body = m.groupValues[1]
            // 像 "$5 和 $10" 这种价格不当成公式
            if (body.first().isWhitespace() || body.last().isWhitespace()) m.value else "$$$body$$"
        }
}

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 16f,
) {
    val ctx = LocalContext.current
    val argb = color.toArgb()
    val bg = codeBackground.toArgb()
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary.toArgb()
    val muted = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.toArgb()
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { textSizeSp.sp.toPx() }
    val markwon = remember(argb, bg, px, accent, muted) { buildMarkwon(ctx, argb, bg, px, accent, muted) }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextView(c).apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                // 中文正文行高大约 1.6 倍字号读着最松快；字距稍微放开一点
                setLineSpacing(0f, 1.38f)
                letterSpacing = 0.02f
            }
        },
        update = { tv ->
            tv.setTextColor(argb)
            tv.setLinkTextColor(accent)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            markwon.setMarkdown(tv, normalizeLatex(markdown))
        },
    )
}

/**
 * 长回答按段落分开渲染：每段一个 MarkdownText，写完的段落内容不变就不会重新排版，
 * 流式输出时只有正在写的最后一段在刷新（不然每来几个字整条回答都要重排一遍，滑动会卡）。
 */
@Composable
fun MarkdownBlocks(
    markdown: String,
    color: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 16f,
) {
    val blocks = remember(markdown) { splitBlocks(markdown).flatMap { splitTables(it) } }
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { i, b ->
            androidx.compose.runtime.key(i) {
                val table = remember(b) { parseTable(b) }
                if (table != null) MarkdownTable(table, Modifier.fillMaxWidth())
                else MarkdownText(b, color, codeBackground, Modifier.fillMaxWidth(), textSizeSp)
            }
        }
    }
}

/** 按空行切段；代码块（```）和公式块（$$）里的空行不切，列表、表格本身不含空行，不受影响。 */
internal fun splitBlocks(md: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var fence = false
    var math = false
    for (line in md.split('\n')) {
        val t = line.trim()
        if (t.startsWith("```") || t.startsWith("~~~")) fence = !fence
        else if (!fence && t == "$$") math = !math
        if (t.isEmpty() && !fence && !math) {
            if (cur.isNotBlank()) out += cur.toString().trimEnd('\n')
            cur.clear()
        } else cur.append(line).append('\n')
    }
    if (cur.isNotBlank()) out += cur.toString().trimEnd('\n')
    return out
}
