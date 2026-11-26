package com.jack.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// 引用 AI 的回答：长按回答里的字选一段，弹出的菜单里点「引用」，放到输入框上面；
// 发出去时带在消息前面（Markdown 的「> 」引用），Kavi 就知道你说的是哪一句。

/** 引用条最多带多少字：整条长回答也能引，但太长的截掉，免得一条消息全是引用。 */
internal const val QUOTE_MAX = 3000

/** 把引用和自己写的话拼成发出去的消息：引用每行前面加「> 」，空一行再接正文。 */
internal fun withQuote(quote: String?, text: String): String {
    val q = quote?.trim()?.let { if (it.length > QUOTE_MAX) it.take(QUOTE_MAX) + "…" else it }
    if (q.isNullOrEmpty()) return text
    val block = q.lines().joinToString("\n") { line -> if (line.isBlank()) ">" else "> $line" }
    return if (text.isBlank()) block else block + "\n\n" + text.trim()
}

/** 拆开一条消息开头的引用：(引用, 正文)。开头不是「>」的返回 (null, 原文)。 */
internal fun splitQuote(content: String): Pair<String?, String> {
    val lines = content.lines()
    val n = lines.indexOfFirst { !it.startsWith(">") }.let { if (it < 0) lines.size else it }
    if (n == 0) return null to content
    val quote = lines.take(n).joinToString("\n") { it.removePrefix(">").removePrefix(" ") }.trim()
    val rest = lines.drop(n).joinToString("\n").trim()
    return quote.ifEmpty { null } to rest
}

/** 输入框上面那条「引用」：左边一道竖线，引的话露两行，右边叉掉。点一下看全文。 */
@Composable
internal fun QuoteBar(quote: String, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    if (open) TextViewer("引用的内容", load = { quote to false }, onDismiss = { open = false })
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 4.dp).clip(Radii.s).clickable { open = true }
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuoteText(quote, colors.onSurfaceVariant, Modifier.weight(1f))
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "不引用了", onClick = onClear), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "不引用了", Modifier.size(16.dp), tint = colors.outline)
        }
    }
}

/** 引用的样子：左边一道竖线，字小一号、淡一点，最多两行。气泡里和输入框上面共用。 */
@Composable
internal fun QuoteText(quote: String, color: Color, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Row(modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(color.copy(alpha = 0.45f)))
        Spacer(Modifier.width(8.dp))
        Text(quote, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 回答正文的选字区外面套这个：长按选字弹出的菜单是「复制、引用、全选」。
 * 选中的字从哪来：点「引用」时让选字区照常「复制」，但这次的复制被这里接住交给 [onQuote]，不进系统剪贴板。
 */
@Composable
internal fun QuoteSelection(onQuote: ((String) -> Unit)?, content: @Composable () -> Unit) {
    if (onQuote == null) { content(); return }
    val view = androidx.compose.ui.platform.LocalView.current
    val real = androidx.compose.ui.platform.LocalClipboardManager.current
    val latest = androidx.compose.runtime.rememberUpdatedState(onQuote)
    val toolbar = remember(view) { QuoteToolbar(view) }
    val clipboard = remember(real, toolbar) {
        object : androidx.compose.ui.platform.ClipboardManager by real {
            override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) {
                if (toolbar.capturing) {
                    toolbar.capturing = false
                    annotatedString.text.trim().takeIf { it.isNotEmpty() }?.let { latest.value(it) }
                } else real.setText(annotatedString)
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalTextToolbar provides toolbar,
        androidx.compose.ui.platform.LocalClipboardManager provides clipboard,
        content = content,
    )
}

/** 选字时浮在字上面的那排按钮（系统的浮动菜单），比 Compose 自带的多一个「引用」。 */
internal class QuoteToolbar(private val view: android.view.View) : androidx.compose.ui.platform.TextToolbar {
    /** 正在为「引用」复制：下一次写剪贴板改成交给引用。 */
    var capturing = false
    private var mode: android.view.ActionMode? = null
    private var rect = androidx.compose.ui.geometry.Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null
    override var status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
        private set

    private val callback = object : android.view.ActionMode.Callback2() {
        override fun onCreateActionMode(m: android.view.ActionMode, menu: android.view.Menu): Boolean { fill(menu); return true }
        override fun onPrepareActionMode(m: android.view.ActionMode, menu: android.view.Menu): Boolean { menu.clear(); fill(menu); return true }
        override fun onActionItemClicked(m: android.view.ActionMode, item: android.view.MenuItem): Boolean {
            when (item.itemId) {
                COPY -> onCopy?.invoke()
                QUOTE -> quote()
                ALL -> { onSelectAll?.invoke(); return true }
            }
            m.finish()
            return true
        }
        override fun onDestroyActionMode(m: android.view.ActionMode) {
            mode = null
            status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
        }
        override fun onGetContentRect(m: android.view.ActionMode, v: android.view.View, out: android.graphics.Rect) {
            out.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    /** 点了「引用」：让选字区复制一次，这次复制被 [QuoteSelection] 接住。 */
    internal fun quote() { capturing = true; onCopy?.invoke(); capturing = false }

    private fun fill(menu: android.view.Menu) {
        if (onCopy != null) {
            menu.add(0, COPY, 0, "复制").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, QUOTE, 1, "引用").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (onSelectAll != null) menu.add(0, ALL, 2, "全选").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        onCopy = onCopyRequested
        onSelectAll = onSelectAllRequested
        val m = mode
        if (m == null) {
            status = androidx.compose.ui.platform.TextToolbarStatus.Shown
            mode = view.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
        } else {
            m.invalidate()
            m.invalidateContentRect()
        }
    }

    override fun hide() {
        status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
        mode?.finish()
        mode = null
    }

    private companion object { const val COPY = 1; const val QUOTE = 2; const val ALL = 3 }
}
