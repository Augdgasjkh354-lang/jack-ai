package com.jack.ai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Warning
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
import com.jack.ai.data.Delivered
import com.jack.ai.data.Deliverables
import com.jack.ai.data.GeneratedFile
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.runtime.key
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.modelLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UserBubble(msg: ChatMessage, onDelete: () -> Unit, onEdit: (() -> Unit)? = null, editing: Boolean = false,
               onSwitch: ((Int) -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var viewing by remember { mutableStateOf<String?>(null) }
    val ctxOuter = LocalContext.current
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
        // PDF、Word 这类原文件还在就看原样；不在了再看提取出的文字
        reading?.takeIf { isViewableDoc(it.name) && it.path.isNotEmpty() && File(it.path).exists() }?.let { a ->
            DocViewer(File(a.path), onDismiss = { reading = null })
        } ?: reading?.let { a ->
            // 文本文件读原文件；PDF、Word 这类看的是提取出来、交给 AI 的那份文字
            val file = File(a.path)
            val raw = com.jack.ai.data.TextPreview.isText(a.name) && a.path.isNotEmpty() && file.exists()
            TextViewer(a.name, load = { if (raw) com.jack.ai.data.TextPreview.read(file) else a.text to false },
                onDismiss = { reading = null }, subtitle = if (raw) null else "提取出的文字")
        }
        files.forEach { f ->
            if (com.jack.ai.data.Attachments.isVideo(f.name) && f.path.isNotEmpty() && File(f.path).exists()) {
                VideoThumb(f.path, 200.dp, 260.dp, Modifier.padding(bottom = 6.dp)) { playVideo(ctxOuter, File(f.path)) }
                return@forEach
            }
            Surface(
                shape = Radii.s,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 280.dp).padding(bottom = 6.dp)
                    .clip(Radii.s).clickable { reading = f },
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
                // 正在输入框里改这条：气泡换成浅陶土色，看得出改的是哪条
                color = if (editing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = Radii.bubbleMine,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(Radii.bubbleMine)
                    .hairline(Radii.bubbleMine)
                    .combinedClickable(onClick = {}, onLongClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        menu = true
                    }),
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
        // 改过重发、重新回答过：「‹ 2/3 ›」切着看各个版本
        if (onSwitch != null && msg.alts.isNotEmpty()) BranchSwitch(msg.altIndex, com.jack.ai.data.Branches.count(msg), onSwitch)
        // 长按：就地弹一个小菜单，不再弹对话框
        Box {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (msg.content.isNotEmpty()) DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(AppIcons.Copy, null) }, onClick = {
                    clipboard.setText(AnnotatedString(msg.content))
                    copiedToast(ctx)
                    menu = false
                })
                if (onEdit != null) DropdownMenuItem(text = { Text("编辑后重发") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { menu = false; onEdit() })
                // 不再先确认：删了底下会出「撤销」
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** 分支切换：「‹ 2/3 ›」，[index] 从 0 起。 */
@Composable
private fun BranchSwitch(index: Int, total: Int, onSwitch: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        @Composable
        fun arrow(label: String, desc: String, enabled: Boolean, to: Int) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).clip(CircleShape)
                .then(if (enabled) Modifier.clickable(onClickLabel = desc) { onSwitch(to) } else Modifier)) {
                Text(label, style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) colors.onSurfaceVariant else colors.outlineVariant)
            }
        }
        arrow("‹", "上一个版本", index > 0, index - 1)
        Text("${index + 1} / $total", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        arrow("›", "下一个版本", index < total - 1, index + 1)
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
    /** Work 的回答：工具记录显示成带时间的执行记录。 */
    work: Boolean = false,
    /** 「改成我想要的说法」：用户改好的版本交给 Kavi，让它学进「说话方式」。 */
    onRestyle: ((String) -> Unit)? = null,
    /** 回答出来后思考摘要折叠起来（设置里改，默认展开）。 */
    stepsCollapsed: Boolean = false,
    /** 这条回答所在的对话：它在后台标签页里用浏览器时，实时画面放那一页。 */
    convId: String = "",
    /** 问题卡片上选好的答案：当成用户的下一条消息发出去。 */
    onAnswer: ((String) -> Unit)? = null,
) {
    var restyling by remember { mutableStateOf(false) }
    if (restyling && onRestyle != null) {
        var edited by remember { mutableStateOf(msg.content) }
        AlertDialog(
            onDismissRequest = { restyling = false },
            title = { Text("改成我想要的说法") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("直接在下面改：删掉绕的话、换成你会说的句子。Kavi 会对比两版，把规律记进它的「说话方式」。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = edited, onValueChange = { edited = it }, minLines = 6, maxLines = 14,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = edited.isNotBlank() && edited.trim() != msg.content.trim(),
                    onClick = { onRestyle(edited.trim()); restyling = false }) { Text("交给 Kavi") }
            },
            dismissButton = { TextButton(onClick = { restyling = false }) { Text("取消") } },
        )
    }
    var more by remember { mutableStateOf(false) }
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
        // 思考摘要和调工具前说的话：回答出来之前一直展开，之后按设置展开或折叠
        if (msg.steps.isNotEmpty()) {
            ProgressSteps(msg.steps, busy = busy, collapsed = stepsCollapsed)
            Spacer(Modifier.height(12.dp))
        }
        if (msg.searches.isNotEmpty()) {
            if (work) ToolTimeline(msg.searches, busy = busy) else SearchBox(msg.searches, busy = busy)
            Spacer(Modifier.height(14.dp))
        }
        // Kavi 在操作网页：回答写完之前，实时画面就放在这条回答里；每一步的截图排成一行
        val browsing = msg.searches.any { it.query.startsWith("浏览器") }
        if (browsing && busy) {
            BrowserLive(convId)
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
            // 只在什么都还没有的最开始亮这颗星；有了工具框，转圈挪到工具框的标题上，不再一会儿出一会儿没
            BreathingStar(size = 22.dp)
        }
        msg.notices.forEach { n ->
            Spacer(Modifier.height(8.dp))
            Surface(color = colors.primaryContainer.copy(alpha = 0.55f), shape = Radii.m, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val lines = n.lines()
                    Text((if (n.startsWith(com.jack.ai.data.InjectionGuard.NOTICE_PREFIX)) "🛡️ " else "✏️ ") + lines.first(), style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                    if (lines.size > 1) Text(lines.drop(1).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimaryContainer.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
                    // 后台回看记下的：不对就去记忆页改；Kavi 改自己设定的：能恢复上一版
                    Text(when {
                        n.startsWith(com.jack.ai.data.MemoryReview.NOTICE_PREFIX) -> "不对的话在「记忆」里改或删"
                        n.startsWith(com.jack.ai.data.KaviSkills.NOTICE_PREFIX) -> "在「技能」里能看、改、删"
                        n.startsWith(com.jack.ai.data.InjectionGuard.NOTICE_PREFIX) -> "拦下的记录在「统计」页最下面"
                        else -> "可以在「记忆」里恢复上一版"
                    }, style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimaryContainer.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (msg.asks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            AskCards(msg.id, msg.asks, active = isLast && !streaming && onAnswer != null) { onAnswer?.invoke(it) }
        }
        if (msg.delivered.isNotEmpty() && !streaming) {
            Spacer(Modifier.height(8.dp))
            DeliveredBox(msg.delivered)
        }
        // 设计图的底稿（.html）只在后台用，聊天里只显示出好的图
        val shownFiles = remember(msg.files) { msg.files.filterNot { f -> f.name.endsWith(".html", true) && com.jack.ai.data.design.DesignTemplates.isSource(java.io.File(f.path)) } }
        shownFiles.forEach { f ->
            Spacer(Modifier.height(8.dp))
            when {
                f.mime.startsWith("image/") -> ChartImage(f)
                f.name.endsWith(".html", true) -> AppCard(f, onFixApp)
                else -> GeneratedFileCard(f)
            }
        }
        msg.error?.let { err ->
            Spacer(Modifier.height(6.dp))
            Surface(color = colors.errorContainer.copy(alpha = 0.6f), shape = Radii.m) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Icon(Icons.Default.Warning, null, Modifier.padding(top = 2.dp).size(16.dp), tint = colors.error)
                    Spacer(Modifier.size(8.dp))
                    Text(err, color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // 正在写：正文下面那颗星一明一暗，写完原地换成下面那排按钮（一样高，不跳）
        if (streaming && shown.isNotEmpty()) {
            Box(Modifier.padding(top = 10.dp).height(34.dp), contentAlignment = Alignment.CenterStart) { BreathingStar() }
        }
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)) {
                if (msg.content.isNotEmpty()) {
                    // 复制后图标变成对勾一会儿，不再弹一条提示
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
                    SmallAction(if (copied) Icons.Default.Check else AppIcons.Copy, if (copied) "已复制" else "复制") {
                        clipboard.setText(AnnotatedString(msg.content))
                        copied = true
                    }
                }
                if (isLast) SmallAction(Icons.Default.Refresh, "重新回答", onRegenerate)
                if (isLast && msg.truncated && onContinueWriting != null) ActionPill("接着写", onContinueWriting)
                if (isLast && onContinue != null) ActionPill("接着做", onContinue)
                if (isLast && onReview != null && msg.content.isNotEmpty()) ActionPill("复核", onReview)
                // 不常用的收进「⋯」：朗读、这条有问题、删除（连带删掉后面的消息，删了可以撤销）
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
                        if (onRestyle != null && msg.content.isNotEmpty()) DropdownMenuItem(
                            text = { Text("改成我想要的说法") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { more = false; restyling = true },
                        )
                        if (onReport != null) DropdownMenuItem(
                            text = { Text("这条有问题") },
                            leadingIcon = { Icon(Icons.Default.Warning, null) },
                            onClick = { more = false; reporting = true },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = colors.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = colors.error) },
                            onClick = { more = false; onDelete() },
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

/** 回答下面的小圆按钮：浅灰底，图标灰色。 */
@Composable
private fun SmallAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.CircleShape, interactionSource = press,
        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(34.dp).pressScale(press, 0.9f)) {
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
internal fun CollapsiblePill(expanded: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow,
        shape = if (expanded) Radii.m else Radii.l,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        // 点开、收起时框的大小滑过去，不是一下子变
        modifier = (if (expanded) Modifier.fillMaxWidth() else Modifier)
            .animateContentSize(androidx.compose.animation.core.tween(Motion.NORMAL)),
    ) { content() }
}
