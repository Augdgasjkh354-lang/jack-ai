package com.jack.ai.ui

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
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
    onSettings: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    // 项目里的对话放在项目页面里，这里只列普通对话
    val chats = state.conversations.filter { it.messages.isNotEmpty() && it.projectId == null }
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Jack AI",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Add, null) },
                label = { Text("新对话") },
                selected = false,
                onClick = onNew,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                item { SectionLabel("项目") }
                items(state.projects, key = { "p" + it.id }) { p ->
                    val count = state.conversations.count { it.projectId == p.id && it.messages.isNotEmpty() }
                    NavigationDrawerItem(
                        icon = { Icon(AppIcons.Folder, null) },
                        label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        badge = { if (count > 0) Text("$count") },
                        selected = state.currentProjectId == p.id,
                        onClick = { onOpenProject(p.id) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Add, null) },
                        label = { Text("新建项目", color = MaterialTheme.colorScheme.primary) },
                        selected = false,
                        onClick = onNewProject,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionLabel("聊天记录")
                    if (chats.isEmpty()) {
                        Text(
                            "还没有聊天记录",
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }
                }
                items(chats, key = { it.id }) { c ->
                    ChatItem(c, c.id == state.currentId, { onSelect(c.id) }, { renaming = c }, { deleting = c })
                }
            }
            HorizontalDivider()
            NavigationDrawerItem(
                icon = { Icon(AppIcons.Moon, null) },
                label = { Text("记忆与梦") },
                badge = { if (state.dreams.any { !it.seen }) Text("新梦", color = MaterialTheme.colorScheme.primary) },
                selected = false,
                onClick = onOpenMemory,
                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Build, null) },
                label = { Text("技能") },
                selected = false,
                onClick = onOpenSkills,
                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 4.dp),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Settings, null) },
                label = { Text("设置") },
                selected = false,
                onClick = onSettings,
                modifier = Modifier.padding(12.dp),
            )
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
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun ChatItem(c: Conversation, selected: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        badge = {
            Row {
                IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, "重命名", Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp))
                }
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
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
