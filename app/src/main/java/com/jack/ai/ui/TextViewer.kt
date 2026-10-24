package com.jack.ai.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.data.TextPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 读出来的内容：文字、是不是只读了开头、读失败的原因。 */
private data class Loaded(val text: String, val truncated: Boolean = false, val error: String? = null)

/**
 * 全屏看一个文本类文件：Markdown 排好版，代码和表格数据用等宽字，JSON 自动缩进；能选字复制。
 * load 在后台读内容（返回文字和是否截断）；onShare、onOpenWith 不给就不显示那一项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewer(
    name: String,
    load: suspend () -> Pair<String, Boolean>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onShare: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null,
    /** 菜单里另加的几项：(名字, 点了做什么)。 */
    extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val loaded by produceState<Loaded?>(null, name) {
        value = withContext(Dispatchers.IO) {
            runCatching { load() }.fold(
                { (t, cut) -> Loaded(if (TextPreview.ext(name) == "json") TextPreview.prettyJson(t) else t, cut) },
                { Loaded("", error = it.message ?: "读不出来") },
            )
        }
    }
    var menu by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val info = listOfNotNull(subtitle, loaded?.text?.let { "${it.length} 字" },
                                "只显示了开头".takeIf { loaded?.truncated == true }).joinToString(" · ")
                            if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") } },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("复制全部") }, enabled = !loaded?.text.isNullOrEmpty(), onClick = {
                                    menu = false
                                    clipboard.setText(AnnotatedString(loaded?.text.orEmpty()))
                                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                                })
                                onShare?.let { DropdownMenuItem(text = { Text("分享") }, onClick = { menu = false; it() }) }
                                onOpenWith?.let { DropdownMenuItem(text = { Text("用其他应用打开") }, onClick = { menu = false; it() }) }
                                extra.forEach { (label, go) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; go() }) }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            val l = loaded
            when {
                l == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                l.error != null -> Text("打不开：${l.error}", Modifier.padding(padding).padding(16.dp), color = colors.error)
                l.text.isBlank() -> Text("这个文件是空的。", Modifier.padding(padding).padding(16.dp), color = colors.onSurfaceVariant)
                TextPreview.isMarkdown(name) && l.text.length <= TextPreview.MARKDOWN_MAX ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        MarkdownBlocks(l.text, colors.onSurface, colors.surfaceVariant, Modifier.fillMaxWidth())
                    }
                else -> {
                    val code = TextPreview.isCode(name)
                    val parts = remember(l.text) { TextPreview.chunks(l.text) }
                    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        itemsIndexed(parts) { _, part ->
                            SelectionContainer {
                                Text(
                                    part,
                                    style = if (code) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                                    else MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
