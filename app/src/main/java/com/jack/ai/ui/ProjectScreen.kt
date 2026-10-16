package com.jack.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Conversation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    vm: ChatViewModel,
    state: UiState,
    projectId: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val project = state.project(projectId)
    if (project == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var renamingProject by remember { mutableStateOf(false) }
    var editingInstructions by remember { mutableStateOf(false) }
    var deletingProject by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var memoryOf by remember { mutableStateOf<Conversation?>(null) }
    val chats = state.conversations.filter { it.projectId == projectId && it.messages.isNotEmpty() }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addProjectFile(projectId, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { renamingProject = true }) { Icon(Icons.Default.Edit, "重命名项目") }
                    IconButton(onClick = { deletingProject = true }) { Icon(Icons.Default.Delete, "删除项目") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Button(
                    onClick = { vm.newChat(projectId); onOpenChat() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.size(6.dp))
                    Text("在这个项目里新建对话")
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("项目说明", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editingInstructions = true }) { Text("编辑") }
                        }
                        Text(
                            project.instructions.ifBlank { "还没有说明。写下这个项目的背景、目标、要求，项目里的每个对话 AI 都会记住。" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (project.instructions.isBlank()) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("项目记忆", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        ProjectMemory(vm, project.id)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("项目资料", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            if (state.importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        if (project.files.isEmpty()) {
                        }
                        project.files.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.File, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${f.text.length} 字", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    vm.saveProject(project.copy(files = project.files.filterNot { it.id == f.id }))
                                }) { Icon(Icons.Default.Close, "移除") }
                            }
                        }
                        OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !state.importing) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.size(4.dp))
                            Text("添加资料")
                        }
                    }
                }
            }
            item {
                Text("项目里的对话", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp))
                if (chats.isEmpty()) {
                    Text("还没有对话", color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(chats, key = { it.id }) { c ->
                ChatItem(
                    c, false,
                    onClick = { vm.select(c.id); onOpenChat() },
                    onRename = { renaming = c },
                    onDelete = { deleting = c },
                    onPin = { vm.togglePin(c.id) },
                    onMemory = { memoryOf = c },
                )
            }
        }
    }

    memoryOf?.let { c -> ConversationMemoryDialog(vm, c) { memoryOf = null } }
    if (renamingProject) {
        RenameDialog(project.name, "项目名称", onDismiss = { renamingProject = false }) {
            vm.saveProject(project.copy(name = it)); renamingProject = false
        }
    }
    if (editingInstructions) {
        var text by remember { mutableStateOf(project.instructions) }
        AlertDialog(
            onDismissRequest = { editingInstructions = false },
            title = { Text("项目说明") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("例如：这是我的小说项目，主角叫…，风格偏…，每次回答都要…") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveProject(project.copy(instructions = text.trim())); editingInstructions = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingInstructions = false }) { Text("取消") } },
        )
    }
    if (deletingProject) {
        ConfirmDeleteDialog(
            "删除项目？",
            "项目「${project.name}」和里面的 ${chats.size} 个对话都会被永久删除。",
            onDismiss = { deletingProject = false },
        ) {
            deletingProject = false
            vm.deleteProject(projectId)
            onBack()
        }
    }
    renaming?.let { c ->
        RenameDialog(c.title, onDismiss = { renaming = null }) { vm.rename(c.id, it); renaming = null }
    }
    deleting?.let { c ->
        ConfirmDeleteDialog("删除对话？", "「${c.title}」会被永久删除。", onDismiss = { deleting = null }) {
            vm.delete(c.id); deleting = null
        }
    }
}
