package com.jack.ai.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
    // 项目里的对话放在项目页面里，这里只列普通对话
    val chats = state.conversations.filter { it.messages.isNotEmpty() && it.projectId == null }
    val colors = MaterialTheme.colorScheme
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
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
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
                items(chats, key = { it.id }) { c ->
                    ChatItem(c, c.id == state.currentId, { onSelect(c.id) }, { renaming = c }, { deleting = c })
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(vertical = 8.dp)) {
                DrawerRow(
                    icon = AppIcons.Moon, label = "记忆与梦",
                    trailing = if (state.dreams.any { !it.seen }) "新梦" else null,
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
fun ChatItem(c: Conversation, selected: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
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
            Text(
                c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            )
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, "更多", Modifier.size(18.dp), tint = colors.outline)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
