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

// 引用 AI 的回答：选一段（或整条）放到输入框上面，发出去时带在消息前面（Markdown 的「> 」引用），
// Kavi 就知道你说的是哪一句。

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

/**
 * 选要引用的那一段：全文铺开，长按拖着选，底下「引用选中的」；不选就是「引用整条」。
 * 用只读输入框显示，选中的范围看得见，系统的复制菜单照样能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuotePicker(text: String, onDismiss: () -> Unit, onQuote: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    val colors = MaterialTheme.colorScheme
    val sel = field.selection
    val picked = if (sel.collapsed) "" else text.substring(sel.min.coerceIn(0, text.length), sel.max.coerceIn(0, text.length)).trim()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("引用", style = MaterialTheme.typography.titleMedium)
                            Text("长按拖着选一段；不选就引用整条", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") } },
                )
            },
            bottomBar = {
                Surface(color = colors.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (picked.isNotEmpty()) Text("选了 ${picked.length} 字", style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (picked.isNotEmpty()) TextButton(onClick = { field = field.copy(selection = TextRange(0)) }) { Text("取消选择") }
                        Button(onClick = { onQuote(picked.ifEmpty { text.trim() }) }) { Text(if (picked.isNotEmpty()) "引用选中的" else "引用整条") }
                    }
                }
            },
        ) { padding ->
            BasicTextField(
                value = field,
                // 只读：字不会变，只接选中范围的变化
                onValueChange = { field = it.copy(text = text) },
                readOnly = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
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
