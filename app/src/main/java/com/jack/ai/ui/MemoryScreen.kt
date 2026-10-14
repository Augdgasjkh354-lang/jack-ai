package com.jack.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.DreamEntry
import com.jack.ai.data.MemoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val settings = state.settings
    LaunchedEffect(Unit) {
        vm.reloadMemory()
        vm.markDreamsSeen()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆与梦") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("记忆", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(settings.memoryEnabled, { on -> vm.updateSettings { it.copy(memoryEnabled = on) } })
                        }
                        Text("开着时，AI 每次聊天都会参考下面这些关于你的记忆。你说“记住…”时它也会存进来。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dream（每天 5:30 做梦）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(settings.dreamEnabled, { on -> vm.updateSettings { it.copy(dreamEnabled = on) } })
                        }
                        Text("每天凌晨整理前一天的聊天，补充和精简记忆，并写一篇梦。手机关机或没网时会顺延，下次打开 App 时补做。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = vm::dreamNow, enabled = !state.dreaming) { Text("现在就做梦") }
                            if (state.dreaming) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("正在做梦…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("记住的事（${state.memories.size}）", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "添加记忆") }
                    if (state.memories.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("清空") }
                }
                if (state.memories.isEmpty()) {
                    Text("还没有记忆。多聊聊，或者等它做一次梦。", color = MaterialTheme.colorScheme.outline)
                }
            }
            items(state.memories, key = { it.id }) { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• " + m.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { editing = m }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, "修改", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { vm.deleteMemory(m.id) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp))
                    }
                }
            }

            item {
                Text("梦境日记", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                if (state.dreams.isEmpty()) {
                    Text("还没有做过梦。", color = MaterialTheme.colorScheme.outline)
                }
            }
            items(state.dreams, key = { it.id }) { d -> DreamCard(d, initiallyOpen = d == state.dreams.first()) }
        }
    }

    if (adding || editing != null) {
        var text by remember(editing?.id) { mutableStateOf(editing?.text ?: "") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing != null) "修改记忆" else "添加记忆") },
            text = {
                OutlinedTextField(text, { text = it }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：用户喜欢简短直接的回答") })
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val e = editing
                    if (e != null) vm.updateMemory(e.id, text) else vm.addMemory(text)
                    adding = false; editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        ConfirmDeleteDialog("清空全部记忆？", "所有记住的事都会被删除，无法恢复。", onDismiss = { confirmClear = false }) {
            vm.clearMemories(); confirmClear = false
        }
    }
}

private val dreamDate = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)

@Composable
fun DreamCard(d: DreamEntry, initiallyOpen: Boolean, onClick: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().clickable { if (onClick != null) onClick() else open = !open },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Moon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(6.dp))
                Text(dreamDate.format(Date(d.createdAt)) + " 的梦", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                d.dream,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (d.note.isNotBlank()) {
                Row {
                    Text("“", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        d.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                    )
                }
            }
            AnimatedVisibility(open && onClick == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (d.summary.isNotBlank()) {
                        Text("聊过的事", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(d.summary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (d.changes.isNotBlank()) {
                        Text("记忆的变化", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(d.changes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
