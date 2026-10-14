package com.jack.ai.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.data.Attachments
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.modelLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Thumbnail(path: String, size: Dp, onClick: (() -> Unit)? = null) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(path, px * 2) }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ImageViewer(path: String, onDismiss: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(path, 2000) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UserBubble(msg: ChatMessage, onDelete: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        val images = msg.attachments.filter { it.kind == "image" }
        val files = msg.attachments.filter { it.kind == "file" }
        if (images.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEach { img -> Thumbnail(img.path, 96.dp) { viewing = img.path } }
            }
            Spacer(Modifier.height(6.dp))
        }
        files.forEach { f ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 280.dp).padding(bottom = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.File, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text("${f.text.length} 字", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (msg.content.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    msg.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            TextButton(onClick = { menu = true }) { Text("…", color = MaterialTheme.colorScheme.outline) }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text("这条消息") },
            text = { Text("复制内容，或删除这条及之后的所有消息。") },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(msg.content))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    menu = false
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { onDelete(); menu = false }) { Text("删除") }
            },
        )
    }
}

@Composable
fun AssistantMessage(
    msg: ChatMessage,
    streaming: Boolean,
    isLast: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (msg.searches.isNotEmpty()) {
            SearchBox(msg.searches)
            Spacer(Modifier.height(8.dp))
        }
        if (msg.reasoning.isNotEmpty()) {
            ThinkingBox(msg.reasoning, thinking = streaming && msg.content.isEmpty() && msg.searches.none { it.pending })
            Spacer(Modifier.height(8.dp))
        }
        if (msg.content.isNotEmpty()) {
            MarkdownText(
                markdown = msg.content,
                color = colors.onSurface,
                codeBackground = colors.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (streaming && msg.reasoning.isEmpty() && msg.searches.none { it.pending }) {
            CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
        }
        msg.error?.let { err ->
            Spacer(Modifier.height(4.dp))
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Text(
                    err,
                    color = colors.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (msg.content.isNotEmpty()) {
                    SmallAction(AppIcons.Copy, "复制") {
                        clipboard.setText(AnnotatedString(msg.content))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                if (isLast) SmallAction(Icons.Default.Refresh, "重新回答", onRegenerate)
                SmallAction(Icons.Default.Delete, "删除", onDelete)
                if (msg.model.isNotEmpty()) {
                    Text(modelLabel(msg.model), style = MaterialTheme.typography.labelSmall, color = colors.outline)
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, desc, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ThinkingBox(reasoning: String, thinking: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(thinking) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Icon(AppIcons.Brain, null, Modifier.size(16.dp), tint = colors.primary)
                Spacer(Modifier.size(6.dp))
                Text(
                    if (thinking) "思考中…" else "已深度思考",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(expanded) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchBox(searches: List<SearchRecord>) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val searching = searches.any { it.pending }
    val sources = searches.flatMap { it.sources }
    Surface(
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Globe, null, Modifier.size(16.dp), tint = colors.primary)
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    if (searching) "正在${searches.last { it.pending }.query.let { if (isOther(it)) it else "搜索：$it" }}"
                    else if (sources.isEmpty() || searches.all { isOther(it.query) }) "用了 ${searches.size} 次工具"
                    else "用了 ${searches.size} 次工具，参考 ${sources.size} 个网页",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var n = 0
                    searches.forEach { rec ->
                        Text(
                            (when {
                                rec.query.startsWith("读取") -> "📄 "
                                rec.query.startsWith("翻阅") -> "📜 "
                                rec.query.startsWith("记住") -> "📌 "
                                else -> "🔍 "
                            }) + rec.query +
                                (rec.error?.let { "（$it）" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rec.error != null) colors.error else colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!isOther(rec.query)) {
                            rec.sources.forEach { src ->
                                n++
                                val idx = n
                                Text(
                                    "[$idx] ${src.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { runCatching { uriHandler.openUri(src.url) } }
                                        .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isOther(label: String) = label.startsWith("读取") || label.startsWith("翻阅") || label.startsWith("记住")
