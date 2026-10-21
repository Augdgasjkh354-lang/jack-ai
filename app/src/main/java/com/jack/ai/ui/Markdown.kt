package com.jack.ai.ui

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

private fun buildMarkwon(ctx: Context, textColor: Int, codeBg: Int, textSizePx: Float): Markwon =
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
                builder.codeBackgroundColor(codeBg)
                    .codeBlockBackgroundColor(codeBg)
                    .codeTextColor(textColor)
                    .codeBlockTextColor(textColor)
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
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { textSizeSp.sp.toPx() }
    val markwon = remember(argb, bg, px) { buildMarkwon(ctx, argb, bg, px) }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextView(c).apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.3f)
            }
        },
        update = { tv ->
            tv.setTextColor(argb)
            tv.setLinkTextColor(argb)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            markwon.setMarkdown(tv, normalizeLatex(markdown))
        },
    )
}
