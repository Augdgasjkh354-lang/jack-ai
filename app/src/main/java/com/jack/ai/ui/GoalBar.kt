package com.jack.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Conversation

/** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal/，MIT。对话目标与本地控制，不消耗模型调用。 */
@Composable
fun GoalBar(vm: ChatViewModel, conv: Conversation) {
    val goal = conv.goal ?: return
    var open by remember(conv.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { open = true }) {
        Text("${goal.label} · ${goal.objective}", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("目标 · ${goal.label}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(goal.objective)
                Text("验收标准", style = MaterialTheme.typography.titleSmall)
                goal.criteria.forEach { Text("• $it") }
                if (goal.progress.isNotEmpty()) {
                    Text("进展", style = MaterialTheme.typography.titleSmall)
                    goal.progress.forEach { Text(it) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.goalAction(conv.id, "clear"); open = false }) { Text("清除") }
                if (goal.status != "complete") TextButton(onClick = {
                    vm.goalAction(conv.id, if (goal.status == "active") "pause" else "resume"); open = false
                }) { Text(if (goal.status == "active") "暂停" else "继续") }
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
    )
}
