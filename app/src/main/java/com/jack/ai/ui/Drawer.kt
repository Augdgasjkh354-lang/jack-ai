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
    onOpenReview: () -> Unit,
    onSettings: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var query by remember { mutableStateOf("") }
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
        ChatItem(c, c.id == state.currentId, { onSelect(c.id) }, { renaming = c }, { deleting = c }, onPin = { onPin(c.id) },
            onMemory = { onMemory(c) })
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
                if (pinned.isNotEmpty()) {
                    item { SectionLabel("置顶") }
                    items(pinned, key = { "pin" + it.id }) { c -> chatRow(c)() }
                }
                item { SectionLabel("项目") }
                items(state.projects, key = { "p" + it.id }) { p ->
                    val count = state.conversations.count { it.projectId == p.id && it.messages.isNotEmpty() }
                    DrawerRow(
                        icon = AppIcons.Folder,
                        label = p.name,
                        selected = state.currentProjectId == p.id,
                        trailing = if (count > 0) "$count" else null,
                        onClick = { onOpenProject(p.id) },
                    )
                }
                item {
                    DrawerRow(icon = Icons.Default.Add, label = "新建项目", tint = colors.primary, onClick = onNewProject)
                }
                item {
                    SectionLabel("最近")
                    if (chats.isEmpty()) {
                        Text(
                            "还没有聊天记录",
                            color = colors.outline,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }
                }
                items(chats, key = { it.id }) { c -> chatRow(c)() }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(vertical = 8.dp)) {
                DrawerRow(
                    icon = AppIcons.Moon, label = "记忆与梦",
                    trailing = if (state.dreams.any { !it.seen }) "新日记" else null,
                    onClick = onOpenMemory,
                )
                DrawerRow(icon = AppIcons.File, label = "资料库",
                    trailing = state.library.size.takeIf { it > 0 }?.toString(), onClick = onOpenLibrary)
                DrawerRow(icon = Icons.Default.Star, label = "复习卡片",
                    trailing = state.cards.count { it.due <= System.currentTimeMillis() }.takeIf { it > 0 }?.let { "$it 待复习" },
                    onClick = onOpenReview)
                DrawerRow(icon = Icons.Default.Build, label = "技能", onClick = onOpenSkills)
                DrawerRow(icon = Icons.Default.Notifications, label = "提醒和任务",
                    trailing = state.routines.count { it.enabled }.takeIf { it > 0 }?.toString(), onClick = onOpenRoutines)
                DrawerRow(icon = Icons.Default.Settings, label = "设置", onClick = onSettings)
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
    )
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
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (c.pinned) {
                Icon(AppIcons.Pin, null, Modifier.padding(end = 6.dp).size(14.dp), tint = colors.primary)
            }
            Text(
                c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            )
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
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, "更多", Modifier.size(18.dp), tint = colors.outline)
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
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Chat, null, Modifier.size(size * 0.58f), tint = MaterialTheme.colorScheme.onPrimary)
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
