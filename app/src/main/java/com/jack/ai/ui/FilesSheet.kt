package com.jack.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Conversation
import com.jack.ai.data.GeneratedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 对话文件夹：你发的附件和 AI 生成的文件都在这里。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesSheet(vm: ChatViewModel, conv: Conversation, inProject: Boolean, onDismiss: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val files by produceState(emptyList<File>(), conv.id, conv.messages.size, version) {
        value = withContext(Dispatchers.IO) { vm.workspaceFiles(conv) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addWorkspaceFiles(conv, uris) { version++ }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("文件夹", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (inProject) "这个项目里的对话共用这个文件夹" else "你发的文件和 AI 做的文件都在这里，AI 能直接读和改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Add, null)
                    Text("添加")
                }
            }
            if (files.isEmpty()) {
                Text(
                    "还没有文件。",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 520.dp).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(files, key = { it.absolutePath }) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                GeneratedFileCard(GeneratedFile(f.name, f.absolutePath, ChatViewModel.mimeOf(f.name), f.length()))
                            }
                            IconButton(onClick = { deleting = f }) {
                                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${f.name}」？") },
            text = { Text("删除后 AI 也找不到它了，聊天里的文件卡片会显示“已删除”。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteWorkspaceFile(f) { version++ }; deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
