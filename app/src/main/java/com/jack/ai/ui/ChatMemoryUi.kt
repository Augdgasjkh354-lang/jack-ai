package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.ChatMemory
import com.jack.ai.data.Conversation

/** 记忆条目列表：每条可以删，传了 onMoveUp 时还能挪到全局；底下可以加一条。 */
@Composable
fun MemoryItems(
    items: List<String>,
    onChange: (List<String>) -> Unit,
    onMoveUp: ((String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf("") }
    Column {
        if (items.isEmpty()) Text("还没有。", color = colors.outline, modifier = Modifier.padding(vertical = 4.dp))
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
                if (onMoveUp != null) {
                    IconButton(onClick = { onMoveUp(item) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "挪到全局", Modifier.size(18.dp), tint = colors.primary)
                    }
                }
                IconButton(onClick = { onChange(items - item) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "删除", Modifier.size(16.dp), tint = colors.outline)
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(adding, { adding = it }, placeholder = { Text("加一条") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = adding.isNotBlank(), onClick = {
                if (adding.trim() !in items) onChange(items + adding.trim())
                adding = ""
            }) { Text("添加") }
        }
    }
}

/** 一个对话的记忆：本对话记忆，以及用不用全局记忆、参不参与做梦。 */
@Composable
fun ConversationMemoryDialog(vm: ChatViewModel, conv: Conversation, onDismiss: () -> Unit) {
    var items by remember(conv.id) { mutableStateOf(vm.chatMemory(conv)) }
    var useGlobal by remember(conv.id) { mutableStateOf(conv.useGlobalMemory) }
    var inDream by remember(conv.id) { mutableStateOf(conv.inDream) }
    // 隐私项目里的对话：两个开关都锁成关，说明原因
    val isolated = vm.state.value.project(conv.projectId)?.isolated == true
    val globalOn = useGlobal && !isolated && vm.state.value.settings.memoryEnabled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isolated) Text("这个对话在隐私项目里，和全局记忆、做梦都隔开了。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用全局记忆", Modifier.weight(1f))
                    Switch(useGlobal && !isolated, { useGlobal = it; vm.setMemoryFlags(conv.id, it, inDream) }, enabled = !isolated)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("参与做梦", Modifier.weight(1f))
                    Switch(inDream && !isolated, { inDream = it; vm.setMemoryFlags(conv.id, useGlobal, it) }, enabled = !isolated)
                }
                Text("本对话记忆 · ${ChatMemory.chatFileName(conv)}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                MemoryItems(
                    items,
                    onChange = { items = it; vm.setChatMemory(conv, it) },
                    onMoveUp = if (globalOn) { item -> items = items - item; vm.moveToGlobal(conv, item) } else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 项目页里的「项目记忆」。 */
@Composable
fun ProjectMemory(vm: ChatViewModel, projectId: String) {
    var items by remember(projectId) { mutableStateOf(vm.projectMemory(projectId)) }
    MemoryItems(items, onChange = { items = it; vm.setProjectMemory(projectId, it) })
}
