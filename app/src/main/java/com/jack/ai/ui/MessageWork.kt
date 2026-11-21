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

// 回答里做事的过程：思考进度、深度思考、搜索记录、任务清单、验收标准、工具时间线、交付清单（从 Messages.kt 拆出来）。

/**
 * 思考摘要：一行行小标题（思考到哪了）和 Kavi 调工具前说的话，按先后排。左边一条细线串起来，像 ChatGPT 思考时那样。
 * 还在想的时候一直展开、最后一行带转圈；回答出来后按设置展开或收成一行，点标题切换。
 */
@Composable
internal fun ProgressSteps(steps: List<com.jack.ai.data.ThinkStep>, busy: Boolean, collapsed: Boolean) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable(collapsed) { mutableStateOf(!collapsed) }
    val show = busy || open
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(Radii.xs).clickable(enabled = !busy) { open = !open }.padding(vertical = 2.dp),
        ) {
            Icon(AppIcons.Brain, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            Text(if (busy) "思考中" else "思考摘要 · ${steps.size} 步", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            if (!busy) Chevron(show, Modifier.size(18.dp), tint = colors.outline)
        }
        androidx.compose.animation.AnimatedVisibility(show) {
            Column(Modifier.padding(start = 7.dp, top = 6.dp)) {
                steps.forEachIndexed { i, s ->
                    val last = i == steps.lastIndex
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        // 左边的线和圆点：摘要是空心小点，Kavi 说的话是实心点
                        Box(Modifier.width(12.dp).fillMaxHeight()) {
                            Box(Modifier.align(Alignment.TopCenter).padding(top = if (i == 0) 7.dp else 0.dp)
                                .width(1.dp).fillMaxHeight().background(colors.outlineVariant))
                            if (busy && last) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 3.dp).size(10.dp), strokeWidth = 1.5.dp)
                            else Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(7.dp)
                                .background(if (s.kind == "note") colors.primary else colors.surface, CircleShape)
                                .border(1.dp, if (s.kind == "note") colors.primary else colors.outline, CircleShape))
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            s.text,
                            style = if (s.kind == "note") MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
                            color = if (s.kind == "note") colors.onSurface else colors.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = if (last) 0.dp else 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThinkingBox(reasoning: String, thinking: Boolean) {
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
                Chevron(expanded, Modifier.size(18.dp))
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
internal fun SearchBox(searches: List<SearchRecord>, busy: Boolean = false) {
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
                Chevron(expanded, Modifier.size(18.dp))
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var n = 0
                    // 几百步的长任务一次全画出来会卡：只列最近 100 步（来源编号照旧从头数）
                    val hidden = (searches.size - 100).coerceAtLeast(0)
                    searches.take(hidden).forEach { if (!isOther(it.query)) n += it.sources.size }
                    if (hidden > 0) Text("前面还有 $hidden 步没列出来", style = MaterialTheme.typography.bodySmall, color = colors.outline)
                    searches.drop(hidden).forEach { rec ->
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

private fun isOther(label: String) = listOf("读取", "下载", "翻阅", "记住", "使用技能", "生成", "运行", "设置", "查看", "删除", "写入", "修改", "设闹钟", "关闹钟", "读邮件", "发邮件", "已发邮件", "标为", "添加日程", "打开日历").any { label.startsWith(it) }

/** 任务清单（Kavi 列了清单时）：做完的打勾变灰，正在做的整行高亮、转圈，没做的空心圆；右上角是进度和花费。 */
@Composable
internal fun PlanCard(items: List<com.jack.ai.data.PlanItem>, cost: Double = 0.0, criteria: List<com.jack.ai.data.PlanItem> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    WorkCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text("任务清单", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "${items.count { it.status == "done" || it.status == "skipped" }}/${items.size}" + if (cost > 0) " · 已花 ¥%.2f".format(cost) else "",
                style = MaterialTheme.typography.labelMedium.tabular, color = colors.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
            items.forEachIndexed { i, item ->
                val doing = item.status == "doing"
                val done = item.status == "done"
                val skipped = item.status == "skipped"
                // 做到哪一条，高亮就滑到哪一条
                val rowBg by androidx.compose.animation.animateColorAsState(
                    if (doing) colors.surfaceContainerHigh else colors.surfaceContainerHigh.copy(alpha = 0f),
                    androidx.compose.animation.core.tween(Motion.NORMAL), label = "plan")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(Radii.s)
                        .background(rowBg)
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
        shape = Radii.l,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

/** 一步工具用什么图标、什么颜色（按显示的名字认）。 */
private fun toolLook(label: String): Pair<ImageVector, Hue> = when {
    label.startsWith("浏览器") -> AppIcons.Globe to Hue.BLUE
    label.startsWith("生成") || label.startsWith("修改") || label.startsWith("写入") -> AppIcons.File to Hue.VIOLET
    label.startsWith("读取") || label.startsWith("查看") || label.startsWith("翻") -> AppIcons.File to Hue.GRAY
    label.contains("表") || label.startsWith("查「") -> Icons.Default.List to Hue.GREEN
    label.contains("邮件") || label.contains("邮箱") -> Icons.Default.Email to Hue.ORANGE
    label.contains("提醒") || label.contains("日程") || label.contains("闹钟") || label.contains("任务") || label.contains("监控") -> AppIcons.Calendar to Hue.RED
    label.startsWith("运行") || label.startsWith("计算") -> AppIcons.Code to Hue.SKY
    label.startsWith("下载") || label.startsWith("存") -> AppIcons.Download to Hue.SKY
    label.startsWith("使用技能") -> AppIcons.Brain to Hue.GRAY
    label.startsWith("💬") -> AppIcons.Chat to Hue.GRAY
    else -> AppIcons.Globe to Hue.BLUE
}

/**
 * Work 的执行记录：一行一步，时间、图标、做了什么；平时露最近 5 步。
 * 长任务（几百步）顶上多一行总览；展开时一次只多画 50 步（一下子画几百行会卡），出错多时能只看出错的。
 */
@Composable
internal fun ToolTimeline(searches: List<SearchRecord>, busy: Boolean) {
    // 0 是收起（最近 5 步），否则展开最近 limit 步
    var limit by rememberSaveable { mutableStateOf(0) }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    val all = limit > 0
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val time = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
    val errors = searches.count { it.error != null }
    val pool = if (errorsOnly) searches.filter { it.error != null } else searches
    val shown = if (all) pool.takeLast(limit) else pool.takeLast(5)
    // 总览一分钟刷新一次就够（用时按分算）
    val digest = remember(searches.size, errors, searches.lastOrNull()?.pending, System.currentTimeMillis() / 60_000) { ToolDigest.line(searches) }
    WorkCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text("执行记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (busy && searches.none { it.pending }) Text("接着想…", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            else Text("${searches.size} 步", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        if (digest.isNotEmpty()) Text(digest, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.forEach { rec ->
                val (icon, hue) = toolLook(rec.query)
                val tint = hue.color
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rec.at > 0) time.format(java.util.Date(rec.at)) else "", style = MaterialTheme.typography.labelSmall.tabular,
                        color = colors.outline, modifier = Modifier.width(40.dp))
                    Box(Modifier.size(28.dp).clip(Radii.xs).background(hue.wash), contentAlignment = Alignment.Center) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                @Composable
                fun link(text: String, onClick: () -> Unit) = Text(text, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.clip(Radii.xs).clickable(onClick = onClick).padding(vertical = 2.dp))
                val earlier = pool.size - shown.size
                when {
                    !all && (pool.size > 5 || searches.any { it.sources.isNotEmpty() }) ->
                        link(if (pool.size > 5) "展开" + if (pool.size > 50) "最近 50 步" else "全部 ${pool.size} 步" else "看参考的网页") { limit = 50 }
                    all && earlier > 0 -> link("再往前 50 步（还有 $earlier 步）") { limit += 50 }
                }
                if (all) link("收起") { limit = 0 }
                if (errors >= 2 || errorsOnly) link(if (errorsOnly) "看全部步骤" else "只看出错的 $errors 步") { errorsOnly = !errorsOnly }
            }
        }
    }
}

/** Work 的交付清单：这一轮对话文件夹里新建、改动、删除了哪些文件。 */
@Composable
internal fun DeliveredBox(list: List<Delivered>) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceVariant.copy(alpha = 0.6f), shape = Radii.m, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("交付清单 · " + Deliverables.summary(list), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            list.forEach { d ->
                val mark = when (d.kind) { "new" -> "＋"; "deleted" -> "－"; else -> "✎" }
                val lines = when {
                    d.kind == "changed" && d.added >= 0 -> "  +${d.added} −${d.removed} 行"
                    d.kind == "new" && d.added > 0 -> "  ${d.added} 行"
                    else -> ""
                }
                Text("$mark ${d.name}$lines", style = MaterialTheme.typography.bodySmall,
                    color = if (d.kind == "deleted") colors.outline else colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
