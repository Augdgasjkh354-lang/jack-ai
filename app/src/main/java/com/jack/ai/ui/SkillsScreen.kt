package com.jack.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Settings
import com.jack.ai.data.Skill
import com.jack.ai.data.SkillIds
import com.jack.ai.data.allSkills

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(vm: ChatViewModel, settings: Settings, onBack: () -> Unit) {
    var viewing by remember { mutableStateOf<Skill?>(null) }
    var editing by remember { mutableStateOf<Skill?>(null) }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    val skills = settings.allSkills()

    fun toggle(s: Skill, on: Boolean) = vm.updateSettings { st ->
        when {
            s.id == SkillIds.WEB -> st.copy(webSearch = on)
            s.builtin -> st.copy(disabledSkills = if (on) st.disabledSkills - s.id else st.disabledSkills + s.id)
            else -> st.copy(customSkills = st.customSkills.map { if (it.id == s.id) it.copy(enabled = on) else it })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("技能") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "技能是一套做事的方法。AI 平时只知道每个技能的简介，需要时才读取详细做法，所以多开几个也不会让它变慢。" +
                        "让它“做个 PPT”“整理成 Excel”，它就会用对应的技能生成文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(skills, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { if (s.builtin) viewing = s else editing = s }) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name + if (s.builtin) "" else "（自定义）", style = MaterialTheme.typography.titleSmall)
                            Text(s.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (s.id == SkillIds.WEB && settings.tavilyKey.isBlank()) {
                                Text("需要先在设置里填 Tavily Key", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.padding(4.dp))
                        Switch(s.enabled, { on -> toggle(s, on) })
                    }
                }
            }
            item {
                OutlinedButton(onClick = { editing = Skill(name = "", description = "", instructions = "") }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("写一个自己的技能")
                }
                Text(
                    "自定义技能是一段说明：告诉 AI 遇到某类事情时该怎么做。比如“写周报”“回复客户邮件”“小红书文案”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    viewing?.let { s ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(s.name) },
            text = {
                Text(s.instructions, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("好的") } },
        )
    }

    editing?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name) }
        var desc by remember(s.id) { mutableStateOf(s.description) }
        var ins by remember(s.id) { mutableStateOf(s.instructions) }
        val isNew = settings.customSkills.none { it.id == s.id }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "新技能" else "编辑技能") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true,
                        placeholder = { Text("比如：写周报") })
                    OutlinedTextField(desc, { desc = it }, label = { Text("一句话简介（AI 靠它判断什么时候用）") },
                        placeholder = { Text("比如：把用户这周做的事整理成周报") }, minLines = 2, maxLines = 3)
                    OutlinedTextField(ins, { ins = it }, label = { Text("详细做法") },
                        placeholder = { Text("一步一步写清楚怎么做、格式是什么、要注意什么") }, minLines = 6, maxLines = 14)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && desc.isNotBlank() && ins.isNotBlank(),
                    onClick = {
                        val updated = s.copy(name = name.trim(), description = desc.trim(), instructions = ins.trim())
                        vm.updateSettings { st ->
                            st.copy(customSkills = if (isNew) st.customSkills + updated
                                else st.customSkills.map { if (it.id == s.id) updated else it })
                        }
                        editing = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (!isNew) TextButton(onClick = { deleting = s; editing = null }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }

    deleting?.let { s ->
        ConfirmDeleteDialog("删除技能？", "「${s.name}」会被删除。", onDismiss = { deleting = null }) {
            vm.updateSettings { st -> st.copy(customSkills = st.customSkills.filterNot { it.id == s.id }) }
            deleting = null
        }
    }
}
