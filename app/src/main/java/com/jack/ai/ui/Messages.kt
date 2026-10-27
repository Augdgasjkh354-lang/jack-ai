package com.jack.ai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.jack.ai.data.GeneratedFile
import androidx.core.content.FileProvider
import java.io.File
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
internal fun ImageViewer(path: String, onDismiss: () -> Unit) {
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
fun UserBubble(msg: ChatMessage, onDelete: () -> Unit, onEdit: ((String) -> Unit)? = null, laterCount: Int = 0) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
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
        var reading by remember { mutableStateOf<com.jack.ai.data.Attachment?>(null) }
        reading?.let { a ->
            // 文本文件读原文件；PDF、Word 这类看的是提取出来、交给 AI 的那份文字
            val file = File(a.path)
            val raw = com.jack.ai.data.TextPreview.isText(a.name) && a.path.isNotEmpty() && file.exists()
            TextViewer(a.name, load = { if (raw) com.jack.ai.data.TextPreview.read(file) else a.text to false },
                onDismiss = { reading = null }, subtitle = if (raw) null else "提取出的文字")
        }
        files.forEach { f ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 280.dp).padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).clickable { reading = f },
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
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(22.dp, 8.dp, 22.dp, 22.dp),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(22.dp, 8.dp, 22.dp, 22.dp))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    msg.content,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            TextButton(onClick = { menu = true }) { Text("…", color = MaterialTheme.colorScheme.outline) }
        }
        // 长按：就地弹一个小菜单，不再弹对话框
        Box {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (msg.content.isNotEmpty()) DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(AppIcons.Copy, null) }, onClick = {
                    clipboard.setText(AnnotatedString(msg.content))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    menu = false
                })
                if (onEdit != null) DropdownMenuItem(text = { Text("编辑后重发") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { menu = false; editing = true })
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true },
                )
            }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
    if (confirmDelete) DeleteFromDialog(laterCount, onDismiss = { confirmDelete = false }) { confirmDelete = false; onDelete() }
    if (editing && onEdit != null) {
        var text by remember { mutableStateOf(msg.content) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("编辑消息") },
            text = {
                Column {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 10,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() || msg.attachments.isNotEmpty(), onClick = { onEdit(text); editing = false }) {
                    Text("发送")
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
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
    onReview: (() -> Unit)? = null,
    /** Work：任务被打断（出错、停止、清单没做完）时“接着做”。 */
    onContinue: (() -> Unit)? = null,
    /** 回答被长度上限截断时“接着写”。 */
    onContinueWriting: (() -> Unit)? = null,
    /** 「这条有问题」：把这一轮的现场存下来，之后导出。 */
    onReport: ((String) -> Unit)? = null,
    /** 小工具出错时「让 Kavi 修」：发一条消息给 AI。 */
    onFixApp: ((String) -> Unit)? = null,
    /** 删除这条会连带删掉后面几条（删除前确认时告诉用户）。 */
    laterCount: Int = 0,
    /** Work 的回答：工具记录显示成带时间的执行记录。 */
    work: Boolean = false,
) {
    var more by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) DeleteFromDialog(laterCount, onDismiss = { confirmDelete = false }) { confirmDelete = false; onDelete() }
    var reporting by remember { mutableStateOf(false) }
    if (reporting && onReport != null) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("这条有问题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会存下这一轮的问题、回答和工具调用过程，之后在「统计」页最下面导出。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = note, onValueChange = { note = it }, placeholder = { Text("哪里不对？可以不填") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { onReport(note); reporting = false }) { Text("记下") } },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("取消") } },
        )
    }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (msg.plan.isNotEmpty()) {
            PlanCard(msg.plan, msg.cost, msg.criteria)
            Spacer(Modifier.height(10.dp))
        }
        // 回答正文出来之前都算「还在忙」：思考框、工具框在这段时间里保持同一个样子，
        // 不随每次工具调用开始/结束来回切换（以前切一次高度就变一次，屏幕跟着一闪一闪）
        val busy = streaming && msg.content.isEmpty()
        if (msg.searches.isNotEmpty()) {
            if (work) ToolTimeline(msg.searches, busy = busy) else SearchBox(msg.searches, busy = busy)
            Spacer(Modifier.height(14.dp))
        }
        // Kavi 在操作网页：回答写完之前，实时画面就放在这条回答里；每一步的截图排成一行
        val browsing = msg.searches.any { it.query.startsWith("浏览器") }
        if (browsing && busy) {
            BrowserLive()
            Spacer(Modifier.height(8.dp))
        }
        val shots = msg.searches.filter { it.shot.isNotEmpty() }
        if (shots.isNotEmpty()) {
            BrowserSteps(shots)
            Spacer(Modifier.height(8.dp))
        }
        if (msg.reasoning.isNotEmpty()) {
            ThinkingBox(msg.reasoning, thinking = busy)
            Spacer(Modifier.height(14.dp))
        }
        // 回答按打字的节奏柔和地放出来（思考框照原速）；按段渲染，写完的段落不再重排
        val shown = rememberRevealedText(msg.id, msg.content, streaming)
        if (shown.isNotEmpty()) {
            MarkdownBlocks(
                markdown = shown,
                color = colors.onSurface,
                codeBackground = colors.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (streaming && msg.reasoning.isEmpty() && msg.searches.isEmpty()) {
            // 只在什么都还没有的最开始转圈；有了工具框，转圈挪到工具框的标题上，不再一会儿出一会儿没
            CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
        }
        msg.notices.forEach { n ->
            Spacer(Modifier.height(8.dp))
            Surface(color = colors.primaryContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val lines = n.lines()
                    Text("✏️ " + lines.first(), style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                    if (lines.size > 1) Text(lines.drop(1).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimaryContainer.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
                    Text("可以在「记忆与梦」里恢复上一版", style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimaryContainer.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        msg.files.forEach { f ->
            Spacer(Modifier.height(8.dp))
            when {
                f.mime.startsWith("image/") -> ChartImage(f)
                f.name.endsWith(".html", true) -> AppCard(f, onFixApp)
                else -> GeneratedFileCard(f)
            }
        }
        msg.error?.let { err ->
            Spacer(Modifier.height(6.dp))
            Surface(color = colors.errorContainer.copy(alpha = 0.6f), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Icon(Icons.Default.Warning, null, Modifier.padding(top = 2.dp).size(16.dp), tint = colors.error)
                    Spacer(Modifier.size(8.dp))
                    Text(err, color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)) {
                if (msg.content.isNotEmpty()) {
                    SmallAction(AppIcons.Copy, "复制") {
                        clipboard.setText(AnnotatedString(msg.content))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                if (isLast) SmallAction(Icons.Default.Refresh, "重新回答", onRegenerate)
                if (isLast && msg.truncated && onContinueWriting != null) ActionPill("接着写", onContinueWriting)
                if (isLast && onContinue != null) ActionPill("接着做", onContinue)
                if (isLast && onReview != null && msg.content.isNotEmpty()) ActionPill("复核", onReview)
                // 不常用的收进「⋯」：朗读、这条有问题、删除（删除要确认，它会连带删掉后面的消息）
                Box {
                    SmallAction(Icons.Default.MoreVert, "更多") { more = true }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (msg.content.isNotEmpty()) {
                            val speaking = Speaker.speakingId == msg.id
                            DropdownMenuItem(
                                text = { Text(if (speaking) "停止朗读" else "朗读") },
                                leadingIcon = { Icon(if (speaking) AppIcons.Stop else AppIcons.Speaker, null) },
                                onClick = { more = false; Speaker.toggle(ctx, msg.id, msg.content) },
                            )
                        }
                        if (onReport != null) DropdownMenuItem(
                            text = { Text("这条有问题") },
                            leadingIcon = { Icon(Icons.Default.Warning, null) },
                            onClick = { more = false; reporting = true },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = colors.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = colors.error) },
                            onClick = { more = false; confirmDelete = true },
                        )
                    }
                }
                if (msg.model.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Text(modelLabel(msg.model), style = MaterialTheme.typography.labelSmall, color = colors.outline.copy(alpha = 0.8f),
                        modifier = Modifier.padding(end = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ChartImage(f: GeneratedFile) {
    val ctx = LocalContext.current
    var viewing by remember { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, f.path) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(f.path, 1400) }
    }
    Column(Modifier.widthIn(max = 420.dp)) {
        bmp?.let {
            Image(
                it.asImageBitmap(), f.name,
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable { viewing = true },
                contentScale = ContentScale.FillWidth,
            )
        } ?: Text("图表文件已被删除", color = MaterialTheme.colorScheme.outline)
        Row {
            TextButton(onClick = { viewing = true }) { Text("查看大图") }
            TextButton(onClick = { shareFile(ctx, f) }) { Text("分享") }
        }
    }
    if (viewing) ImageViewer(f.path) { viewing = false }
}

@Composable
internal fun GeneratedFileCard(f: GeneratedFile) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // 网页（小工具）在 App 里全屏打开，能直接用
    var viewingApp by remember { mutableStateOf(false) }
    val isApp = f.name.endsWith(".html", true)
    if (viewingApp) AppViewer(File(f.path)) { viewingApp = false }
    // txt、md、json、代码这类直接在 App 里看（手机上多半没有能打开它们的 App）
    var viewingText by remember { mutableStateOf(false) }
    if (viewingText) TextViewer(f.name, load = { com.jack.ai.data.TextPreview.read(File(f.path)) }, onDismiss = { viewingText = false },
        onShare = { shareFile(ctx, f) }, onOpenWith = { openFile(ctx, f) })
    val open = {
        when {
            isApp && File(f.path).exists() -> viewingApp = true
            com.jack.ai.data.TextPreview.isText(f.name) && File(f.path).exists() -> viewingText = true
            else -> openFile(ctx, f)
        }
    }
    val (tag, tagColor) = when (f.name.substringAfterLast('.').lowercase()) {
        "docx" -> "W" to Color(0xFF2B579A)
        "pptx" -> "P" to Color(0xFFD24726)
        "xlsx" -> "X" to Color(0xFF217346)
        "pdf" -> "PDF" to Color(0xFFB3261E)
        "png", "jpg", "jpeg", "webp", "gif" -> "图" to Color(0xFF4BA3A8)
        "txt", "md", "csv", "json" -> "TXT" to Color(0xFF7A7A7A)
        "html", "htm", "js", "py", "kt", "java", "css", "ts" -> "</>" to Color(0xFF7B5BA6)
        else -> "F" to colors.primary
    }
    val path = File(f.path)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { open() },
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(tagColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(tag, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (tag.length > 1) 12.sp else 19.sp)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (path.exists()) formatSize(f.size) + " · " + fileTime(path.lastModified()) else "文件已被删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Surface(onClick = { open() }, shape = RoundedCornerShape(12.dp), color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
                Text("打开", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            IconButton(onClick = { shareFile(ctx, f) }, modifier = Modifier.size(40.dp)) {
                Icon(AppIcons.Share, "分享", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** 文件卡上的时间：今天的只写几点，别的写日期。 */
private fun fileTime(ms: Long): String {
    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
    val today = day.format(java.util.Date()) == day.format(java.util.Date(ms))
    return java.text.SimpleDateFormat(if (today) "今天 HH:mm" else "M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun fileUri(ctx: Context, f: GeneratedFile) =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(f.path))

internal fun openFile(ctx: Context, f: GeneratedFile) {
    if (!File(f.path).exists()) {
        Toast.makeText(ctx, "文件已被删除", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileUri(ctx, f), f.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(intent, "用什么打开").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "手机上没有能打开这种文件的应用，可以装个 WPS 或微软 Office", Toast.LENGTH_LONG).show()
    }
}

internal fun shareFile(ctx: Context, f: GeneratedFile) {
    if (!File(f.path).exists()) {
        Toast.makeText(ctx, "文件已被删除", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType(f.mime)
        .putExtra(Intent.EXTRA_STREAM, fileUri(ctx, f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "分享到"))
}

/** 回答下面的小圆按钮：浅灰底，图标灰色。 */
@Composable
private fun SmallAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, desc, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 回答下面带字的小按钮（接着写、接着做、复核）：细边药丸。 */
@Composable
private fun ActionPill(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.CircleShape, color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.height(34.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 思考、工具记录收着时是一个小药丸，点开铺满一行。 */
@Composable
private fun CollapsiblePill(expanded: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (expanded) colors.surfaceContainerLow else colors.surfaceContainerLow,
        shape = RoundedCornerShape(if (expanded) 16.dp else 20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = if (expanded) Modifier.fillMaxWidth() else Modifier,
    ) { content() }
}

@Composable
private fun ThinkingBox(reasoning: String, thinking: Boolean) {
    // 默认收起：想看再点开。思考中只在标题下露一行最新的思路，不刷屏
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    // 思考中要露出最新一行，铺满一行；想完了收成小药丸
    CollapsiblePill(expanded || thinking) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Icon(AppIcons.Brain, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (thinking) "思考中…" else "已深度思考",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = expanded || thinking),
                )
                Spacer(Modifier.size(4.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    Modifier.size(18.dp), tint = colors.onSurfaceVariant,
                )
            }
            if (thinking && !expanded) {
                Text(
                    reasoning.trimEnd().substringAfterLast('\n').takeLast(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, start = 22.dp),
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
private fun SearchBox(searches: List<SearchRecord>, busy: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val searching = searches.any { it.pending }
    val spinning = searching || busy
    val sources = searches.flatMap { it.sources }
    // 忙着的时候铺满一行（标题会变），做完收成小药丸
    CollapsiblePill(expanded || spinning) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                if (spinning) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Globe, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    if (searching) "正在${searches.last { it.pending }.query.let { if (isOther(it)) it else "搜索：$it" }}"
                    else if (busy) "用了 ${searches.size} 次工具，接着想…"
                    else if (sources.isEmpty() || searches.all { isOther(it.query) }) "用了 ${searches.size} 次工具"
                    else "用了 ${searches.size} 次工具 · 参考 ${sources.size} 个来源",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = expanded || spinning),
                )
                Spacer(Modifier.size(4.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    Modifier.size(18.dp), tint = colors.onSurfaceVariant,
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
                                rec.query.startsWith("使用技能") -> "🧩 "
                                rec.query.startsWith("生成") -> "📎 "
                                rec.query.startsWith("运行") -> "🧮 "
                                rec.query.startsWith("修改") || rec.query.startsWith("写入") -> "✏️ "
                                rec.query.startsWith("查看文件夹") || (rec.query.startsWith("查看") && rec.query.contains('.')) -> "📁 "
                                rec.query.startsWith("设闹钟") || rec.query.startsWith("关闹钟") -> "⏰ "
                                rec.query.startsWith("设置") || rec.query.startsWith("查看") || rec.query.startsWith("删除") -> "⏰ "
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

private fun isOther(label: String) = listOf("读取", "翻阅", "记住", "使用技能", "生成", "运行", "设置", "查看", "删除", "写入", "修改", "设闹钟", "关闹钟", "读邮件", "发邮件", "已发邮件", "标为", "添加日程", "打开日历").any { label.startsWith(it) }

/** Work 模式的任务清单：做完的打勾变灰，正在做的整行高亮、转圈，没做的空心圆；右上角是进度和花费。 */
@Composable
private fun PlanCard(items: List<com.jack.ai.data.PlanItem>, cost: Double = 0.0, criteria: List<com.jack.ai.data.PlanItem> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    WorkCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text("任务清单", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "${items.count { it.status == "done" || it.status == "skipped" }}/${items.size}" + if (cost > 0) " · 已花 ¥%.2f".format(cost) else "",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
            items.forEachIndexed { i, item ->
                val doing = item.status == "doing"
                val done = item.status == "done"
                val skipped = item.status == "skipped"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (doing) colors.surfaceContainerHigh else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                ) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        when {
                            done -> Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.outline),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = colors.surface)
                            }
                            doing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurface)
                            skipped -> Box(Modifier.size(19.dp).border(1.5.dp, colors.outlineVariant, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center) { Box(Modifier.size(width = 8.dp, height = 1.5.dp).background(colors.outline)) }
                            else -> Box(Modifier.size(19.dp).border(1.5.dp, colors.outlineVariant, androidx.compose.foundation.shape.CircleShape))
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "${i + 1}. ${item.text}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        ),
                        color = when { done || skipped -> colors.outline; doing -> colors.onSurface; else -> colors.onSurfaceVariant },
                        fontWeight = if (doing) FontWeight.SemiBold else null,
                        modifier = Modifier.weight(1f),
                    )
                    if (doing) Text("正在进行…", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp))
                    if (skipped) Text("跳过", style = MaterialTheme.typography.labelSmall, color = colors.outline,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (criteria.isNotEmpty()) CriteriaList(criteria)
        }
    }
}

/** 验收标准：做完要满足的条件，核对过的打勾，做不到的标叉。 */
@Composable
private fun CriteriaList(criteria: List<com.jack.ai.data.PlanItem>) {
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = colors.outlineVariant)
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("验收标准", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${criteria.count { it.status == "met" }}/${criteria.size} 已满足", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
    criteria.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when (c.status) {
                    "met" -> Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = colors.onSurface)
                    "failed" -> Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = colors.error)
                    else -> Box(Modifier.size(14.dp).border(1.5.dp, colors.outlineVariant, RoundedCornerShape(4.dp)))
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(c.text, style = MaterialTheme.typography.bodySmall,
                color = when (c.status) { "met" -> colors.onSurface; "failed" -> colors.error; else -> colors.onSurfaceVariant },
                modifier = Modifier.weight(1f))
        }
    }
}

/** Work 页上的白卡片：细边、大圆角。 */
@Composable
private fun WorkCard(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLowest,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

/** 一步工具用什么图标、什么颜色（按显示的名字认）。 */
private fun toolLook(label: String): Pair<ImageVector, Color> = when {
    label.startsWith("浏览器") -> AppIcons.Globe to Color(0xFF3B82F6)
    label.startsWith("生成") || label.startsWith("修改") || label.startsWith("写入") -> AppIcons.File to Color(0xFF8B5CF6)
    label.startsWith("读取") || label.startsWith("查看") || label.startsWith("翻") -> AppIcons.File to Color(0xFF6B7280)
    label.contains("表") || label.startsWith("查「") -> Icons.Default.List to Color(0xFF22A06B)
    label.contains("邮件") || label.contains("邮箱") -> Icons.Default.Email to Color(0xFFEA580C)
    label.contains("提醒") || label.contains("日程") || label.contains("闹钟") || label.contains("任务") || label.contains("监控") -> AppIcons.Calendar to Color(0xFFEF5A5A)
    label.startsWith("运行") || label.startsWith("计算") -> AppIcons.Code to Color(0xFF0EA5E9)
    label.startsWith("下载") || label.startsWith("存") -> AppIcons.Download to Color(0xFF0EA5E9)
    label.startsWith("使用技能") -> AppIcons.Brain to Color(0xFF6B7280)
    label.startsWith("💬") -> AppIcons.Chat to Color(0xFF6B7280)
    else -> AppIcons.Globe to Color(0xFF3B82F6)
}

/** Work 的执行记录：一行一步，时间、图标、做了什么；平时露最近 5 步，点开看全部。 */
@Composable
private fun ToolTimeline(searches: List<SearchRecord>, busy: Boolean) {
    var all by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val time = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
    val shown = if (all) searches else searches.takeLast(5)
    WorkCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text("执行记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (busy && searches.none { it.pending }) Text("接着想…", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            else Text("${searches.size} 步", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.forEach { rec ->
                val (icon, tint) = toolLook(rec.query)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rec.at > 0) time.format(java.util.Date(rec.at)) else "", style = MaterialTheme.typography.labelSmall,
                        color = colors.outline, modifier = Modifier.width(40.dp))
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                        if (rec.pending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = tint)
                        else Icon(icon, null, Modifier.size(16.dp), tint = tint)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        rec.query + (rec.error?.let { "（$it）" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rec.error != null) colors.error else colors.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
                if (all && rec.sources.isNotEmpty()) rec.sources.forEach { src ->
                    Text("· ${src.title}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(start = 78.dp).clickable { runCatching { uriHandler.openUri(src.url) } })
                }
            }
            if (searches.size > 5 || searches.any { it.sources.isNotEmpty() }) Text(
                if (all) "收起" else if (searches.size > 5) "展开全部 ${searches.size} 步" else "看参考的网页",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { all = !all }.padding(vertical = 2.dp),
            )
        }
    }
}


/** 删除一条消息会连带删掉它后面的消息和生成的文件，删之前说清楚。 */
@Composable
private fun DeleteFromDialog(laterCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条消息？") },
        text = {
            Text(if (laterCount > 0) "它后面的 $laterCount 条消息也会一起删掉，生成的文件也会删除，删了找不回来。" else "删了找不回来。")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
