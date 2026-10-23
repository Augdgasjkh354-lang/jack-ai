package com.jack.ai.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.UiState
import com.jack.ai.data.Conversation

@Composable
fun HistoryDrawer(
    state: UiState,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String) -> Unit,
    onMemory: (Conversation) -> Unit,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTables: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenApps: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var query by remember { mutableStateOf("") }
    var showAllProjects by remember { mutableStateOf(false) }
    var showMoreTools by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 置顶的对话（包括项目里的）放最上面；其余项目里的对话放在项目页面里，这里只列普通对话
    val pinned = state.conversations.filter { it.messages.isNotEmpty() && it.pinned }
    val chats = state.conversations.filter { it.messages.isNotEmpty() && it.projectId == null && !it.pinned }
    // 停手 250 毫秒再搜，放到后台线程，聊天多了打字也不卡
    val convs = state.conversations
    val results by androidx.compose.runtime.produceState(emptyList<Pair<Conversation, String>>(), query, convs) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(250)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { searchConversations(convs, query) }
    }
    val colors = MaterialTheme.colorScheme
    fun chatRow(c: Conversation): @Composable () -> Unit = {
        // 「⋯」只在当前对话上显示，其他的长按出菜单，列表看着清爽些
        ChatItem(c, c.id == state.currentId, { onSelect(c.id) }, { renaming = c }, { deleting = c }, onPin = { onPin(c.id) },
            onMemory = { onMemory(c) }, showMore = c.id == state.currentId, answering = state.streaming && state.streamingId == c.id)
    }
    ModalDrawerSheet(drawerContainerColor = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 16.dp),
            ) {
                LogoMark(32.dp)
                Spacer(Modifier.size(10.dp))
                Text("Jack AI", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            }
            Surface(
                onClick = onNew,
                shape = RoundedCornerShape(16.dp),
                color = colors.primary,
                contentColor = colors.onPrimary,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.NewChat, null, Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("新对话", style = MaterialTheme.typography.titleSmall)
                }
            }
            SearchField(query, { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
            if (query.isNotBlank()) {
                LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                    item { SectionLabel(if (results.isEmpty()) "没有找到相关对话" else "找到 ${results.size} 个对话") }
                    items(results, key = { "s" + it.first.id }) { (c, snippet) ->
                        SearchResultRow(c.title, snippet, query, state.project(c.projectId)?.name) { onSelect(c.id) }
                    }
                }
            } else LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                // 常用功能：最常用的四个一行露在外面，其余的收进「更多」，侧边栏主要留给对话
                item {
                    val now = System.currentTimeMillis()
                    val due = state.cards.count { it.due <= now }
                    val main = listOf(
                        Tool(Icons.Default.Notifications, "提醒和任务", badge = state.routines.count { it.enabled }.takeIf { it > 0 }?.toString(), onClick = onOpenRoutines),
                        Tool(AppIcons.Moon, "记忆与梦", dot = state.dreams.any { !it.seen }, onClick = onOpenMemory),
                        Tool(AppIcons.File, "资料库", onClick = onOpenLibrary),
                        Tool(AppIcons.Globe, "浏览器", onClick = { ctx.startActivity(android.content.Intent(ctx, BrowserActivity::class.java)) }),
                    )
                    val more = listOf(
                        Tool(Icons.Default.List, "表格", onClick = onOpenTables),
                        Tool(Icons.Default.Star, "复习卡片", badge = due.takeIf { it > 0 }?.toString(), onClick = onOpenReview),
                        Tool(Icons.Default.Build, "技能", onClick = onOpenSkills),
                        Tool(AppIcons.Code, "小工具", onClick = onOpenApps),
                    )
                    ToolGrid(if (showMoreTools) main + more else main)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { showMoreTools = !showMoreTools }.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(if (showMoreTools) "收起" else "更多：表格、复习卡片、技能、小工具", Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, color = colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!showMoreTools && due > 0) Box(Modifier.padding(end = 6.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.primary))
                        Icon(if (showMoreTools) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
                            Modifier.size(16.dp), tint = colors.outline)
                    }
                }
                if (pinned.isNotEmpty()) {
                    item { SectionLabel("置顶") }
                    items(pinned, key = { "pin" + it.id }) { c -> chatRow(c)() }
                }
                item { SectionLabel("项目", action = "新建", onAction = onNewProject) }
                // 项目多了只露出最近的 3 个，其余收起来，免得把聊天记录挤到下面去
                val shownProjects = if (showAllProjects) state.projects else state.projects.take(PROJECTS_SHOWN)
                items(shownProjects, key = { "p" + it.id }) { p ->
                    val count = state.conversations.count { it.projectId == p.id && it.messages.isNotEmpty() }
                    DrawerRow(
                        icon = AppIcons.Folder,
                        label = p.name + if (p.isolated) " 🔒" else "",
                        selected = state.currentProjectId == p.id,
                        trailing = if (count > 0) "$count" else null,
                        onClick = { onOpenProject(p.id) },
                    )
                }
                if (state.projects.size > PROJECTS_SHOWN) {
                    item {
                        DrawerRow(
                            icon = if (showAllProjects) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            label = if (showAllProjects) "收起" else "全部 ${state.projects.size} 个项目",
                            onClick = { showAllProjects = !showAllProjects },
                        )
                    }
                }
                if (state.projects.isEmpty()) {
                    item { DrawerRow(icon = Icons.Default.Add, label = "新建项目", tint = colors.primary, onClick = onNewProject) }
                }
                if (chats.isEmpty()) {
                    item {
                        SectionLabel("最近")
                        Text(
                            "还没有聊天记录",
                            color = colors.outline,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }
                }
                // 按时间分组：今天、昨天、7 天内……一眼就能找到
                chats.groupBy { dayGroup(it.updatedAt) }.forEach { (group, list) ->
                    item(key = "g$group") { SectionLabel(group) }
                    items(list, key = { it.id }) { c -> chatRow(c)() }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            // 底部只留一行：名字和设置
            val name = state.settings.userName.trim()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(colors.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (name.isNotEmpty()) Text(name.take(1), style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
                    else Icon(Icons.Default.Settings, null, Modifier.size(16.dp), tint = colors.onSecondaryContainer)
                }
                Spacer(Modifier.size(12.dp))
                Text(name.ifEmpty { "设置" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Default.Settings, "设置", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
    }

    renaming?.let { c ->
        RenameDialog(c.title, onDismiss = { renaming = null }) { onRename(c.id, it); renaming = null }
    }
    deleting?.let { c ->
        ConfirmDeleteDialog("删除对话？", "「${c.title}」会被永久删除。", onDismiss = { deleting = null }) {
            onDelete(c.id); deleting = null
        }
    }
}

private const val PROJECTS_SHOWN = 3

private class Tool(val icon: ImageVector, val label: String, val badge: String? = null, val dot: Boolean = false, val onClick: () -> Unit)

/** 常用功能：四个一行的小方块，图标在上、字在下，有待办的右上角标数字或小红点。 */
@Composable
private fun ToolGrid(tools: List<Tool>) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        tools.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = t.onClick)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(colors.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) { Icon(t.icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant) }
                            Spacer(Modifier.size(5.dp))
                            Text(t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = colors.onSurface)
                        }
                        when {
                            t.badge != null -> Text(
                                t.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onPrimary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp)
                                    .clip(RoundedCornerShape(8.dp)).background(colors.primary).padding(horizontal = 5.dp),
                            )
                            t.dot -> Box(
                                Modifier.align(Alignment.TopEnd).padding(end = 12.dp).size(7.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(colors.primary),
                            )
                        }
                    }
                }
                // 最后一行不满时补空位，方块大小保持一致
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 对话按最后活动时间分组。 */
internal fun dayGroup(at: Long, now: Long = System.currentTimeMillis()): String {
    fun start(ms: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((start(now) - start(at)) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days < 7 -> "7 天内"
        days < 30 -> "30 天内"
        else -> java.text.SimpleDateFormat("yyyy年M月", java.util.Locale.CHINA).format(java.util.Date(at))
    }
}

@Composable
private fun SectionLabel(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        if (action != null && onAction != null) {
            Text(
                "+ $action",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatItem(
    c: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPin: (() -> Unit)? = null,
    onMemory: (() -> Unit)? = null,
    showMore: Boolean = true,
    /** 这个对话正在后台写回答：标题后面转个小圈。 */
    answering: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.padding(horizontal = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .heightIn(min = 44.dp)
                .padding(start = 16.dp, end = if (showMore) 4.dp else 16.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (c.pinned) {
                Icon(AppIcons.Pin, null, Modifier.padding(end = 6.dp).size(14.dp), tint = colors.primary)
            }
            Text(
                c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            )
            if (answering) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(start = 6.dp).size(12.dp), strokeWidth = 1.5.dp)
            }
            if (c.work) {
                Text(
                    "Work",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    modifier = Modifier.padding(start = 6.dp)
                        .border(1.dp, colors.primary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (showMore) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.size(18.dp), tint = colors.outline)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onPin != null) {
                DropdownMenuItem(
                    text = { Text(if (c.pinned) "取消置顶" else "置顶") },
                    leadingIcon = { Icon(AppIcons.Pin, null) },
                    onClick = { menu = false; onPin() },
                )
            }
            if (onMemory != null) {
                DropdownMenuItem(
                    text = { Text("记忆") },
                    leadingIcon = { Icon(AppIcons.Moon, null) },
                    onClick = { menu = false; onMemory() },
                )
            }
            DropdownMenuItem(
                text = { Text("用量") },
                leadingIcon = { Icon(Icons.Default.Info, null) },
                onClick = { menu = false; showUsage = true },
            )
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("删除", color = colors.error) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = colors.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
    if (showUsage) ConversationUsageDialog(c) { showUsage = false }
}

/** 一个对话用了多少：请求次数、token、缓存命中、花费、当前上下文。 */
@Composable
private fun ConversationUsageDialog(c: Conversation, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    @Composable
    fun line(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, Modifier.weight(1f), color = colors.onSurfaceVariant)
            Text(value)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用量 · ${c.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (c.requests == 0 && c.spentTokens == 0L) {
                    Text("还没有用量记录。", color = colors.onSurfaceVariant)
                } else {
                    if (c.requests > 0) {
                        line("请求次数", "${c.requests}")
                        line("输入", tokens(c.spentPrompt))
                        line("其中缓存命中", if (c.spentPrompt > 0) "${tokens(c.spentHit)}（${c.spentHit * 100 / c.spentPrompt}%）" else "0")
                        line("输出", tokens(c.spentCompletion))
                    }
                    line("合计 token", tokens(c.spentTokens))
                    line("花费", when {
                        c.spentCost > 0 -> yuan(c.spentCost) + if (c.unpriced) "（部分未计价）" else ""
                        c.unpriced -> "未计价"
                        c.requests == 0 -> "以前的没有记录"
                        else -> yuan(0.0)
                    })
                    if (c.lastPromptTokens > 0) line("当前上下文", tokens(c.lastPromptTokens.toLong()))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 按标题和消息内容搜索对话，返回 (对话, 命中的一小段原文)。标题命中的排前面。 */
internal fun searchConversations(all: List<Conversation>, query: String): List<Pair<Conversation, String>> {
    val q = query.trim()
    val out = mutableListOf<Triple<Conversation, String, Int>>()
    for (c in all) {
        if (c.messages.isEmpty()) continue
        val inTitle = c.title.contains(q, ignoreCase = true)
        val hit = c.messages.asReversed().firstOrNull { it.content.contains(q, ignoreCase = true) }
        if (!inTitle && hit == null) continue
        val snippet = hit?.let { m ->
            val i = m.content.indexOf(q, ignoreCase = true)
            val start = (i - 18).coerceAtLeast(0)
            val end = (i + q.length + 40).coerceAtMost(m.content.length)
            (if (start > 0) "…" else "") + m.content.substring(start, end).replace('\n', ' ') + (if (end < m.content.length) "…" else "")
        } ?: ""
        out += Triple(c, snippet, if (inTitle) 0 else 1)
    }
    return out.sortedWith(compareBy<Triple<Conversation, String, Int>> { it.third }.thenByDescending { it.first.updatedAt })
        .take(100).map { it.first to it.second }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                if (value.isEmpty()) Text("搜索对话", style = MaterialTheme.typography.bodyMedium, color = colors.outline)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "清空", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(title: String, snippet: String, query: String, project: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(highlight(title, query, colors.primary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        val sub = listOfNotNull(project?.let { "项目：$it" }, snippet.takeIf { it.isNotEmpty() }).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(highlight(sub, query, colors.primary), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** 把命中的关键词标成主题色。 */
private fun highlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    var i = 0
    while (q.isNotEmpty()) {
        val j = text.indexOf(q, i, ignoreCase = true)
        if (j < 0) break
        append(text.substring(i, j))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(j, j + q.length)) }
        i = j + q.length
    }
    append(text.substring(i))
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    trailing: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.size(14.dp))
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = if (tint == colors.primary) tint else colors.onSurface)
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
    }
}

/** App 的小标志：陶土色圆角方块里一个对话气泡。 */
@Composable
fun LogoMark(size: Dp) {
    // 和桌面图标同一张图：陶土底 + 圆润的 J。前景图四周留了安全边，放大一点再裁成圆角方块
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Color(0xFFC7673C)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.jack.ai.R.mipmap.ic_launcher_foreground), null,
            Modifier.requiredSize(size * 1.5f),
        )
    }
}

@Composable
fun RenameDialog(initial: String, title: String = "重命名", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConfirmDeleteDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
