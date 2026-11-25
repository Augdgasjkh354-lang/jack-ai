package com.jack.ai.ui

import com.jack.ai.api.webReady
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
    var flows by remember { mutableStateOf(false) }
    val packsUi = remember { PacksUi() }
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
            // 技能在上：装的、自己写的、Kavi 写的排最前，再是内置的做法；插件（自带的工具）放后面
            val (plugins, playbooks) = skills.partition { it.id in SkillIds.PLUGINS }
            val ordered = playbooks.sortedBy { if (it.builtin) 1 else 0 }
            item {
                // 顶栏已经写了「技能」，这里不再重复一个同名标题，只留说明
                SectionHeader(null, "做某一类活的方法（小红书运营、合同挑坑……）。你说要做什么，Kavi 会按意思挑出合适的技能照着做；" +
                    "也能在输入框「+」里直接点名。从 GitHub 装：在对话里说「从 GitHub 装技能 作者/项目」。")
            }
            items(ordered, key = { it.id }) { s -> SkillCard(s, settings, onOpen = { if (s.builtin) viewing = s else editing = s }, onToggle = { toggle(s, it) }) }
            item {
                OutlinedButton(onClick = { editing = Skill(name = "", description = "", instructions = "") }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("写一个自己的技能")
                }
            }
            item {
                SectionHeader("插件", "Kavi 自带的工具：开着就能用，用到时它自己会调。关掉的那样 Kavi 就不会用。")
            }
            items(plugins, key = { it.id }) { s ->
                SkillCard(s, settings, onOpen = { if (s.id == SkillIds.BROWSER) flows = true else viewing = s }, onToggle = { toggle(s, it) })
            }
            item {
                SectionHeader("工具包", "一小段代码加一份说明，装上以后 Kavi 多出几个工具。只能用说明里声明过的权限（访问哪些网站、碰不碰文件），装之前给你看。")
            }
            if (settings.packs.isEmpty()) item("packs-empty") {
                PacksEmptyCard(onSample = { packsUi.sampleAsk++ }, onInstall = { packsUi.choosing = true })
            } else {
                items(settings.packs, key = { "pack:" + it.id }) { p ->
                    PackCard(p, onOpen = { packsUi.openId = p.id }, onToggle = { on ->
                        vm.updateSettings { st -> st.copy(packs = st.packs.map { if (it.id == p.id) it.copy(enabled = on) else it }) }
                    })
                }
                item("packs-add") {
                    OutlinedButton(onClick = { packsUi.choosing = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("装工具包")
                    }
                }
            }
        }
    }

    PackDialogs(packsUi, vm, settings)

    if (flows) FlowsDialog(vm) { flows = false }

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
            properties = keepIfEdited(name != s.name || desc != s.description || ins != s.instructions),
            title = { Text(if (isNew) "新技能" else "编辑技能") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true,
                        modifier = if (isNew) Modifier.focusOnShow() else Modifier,
                        placeholder = { Text("比如：写周报") })
                    OutlinedTextField(desc, { desc = it }, label = { Text("简介") },
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

@Composable
private fun SectionHeader(title: String?, hint: String) {
    Column(Modifier.padding(top = if (title == null) 0.dp else 8.dp, bottom = 2.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 一个技能或插件：名字、来源、要先配什么，右边开关。 */
@Composable
private fun SkillCard(s: Skill, settings: Settings, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                val from = when {
                    s.builtin -> ""
                    s.byKavi -> "Kavi 自己总结的"
                    s.source.isNotEmpty() -> "来自 GitHub：" + com.jack.ai.data.SkillRepo.repoOf(s.source)
                    else -> "你自己写的"
                }
                if (from.isNotEmpty()) Text(from, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val need = when {
                    s.id == SkillIds.WEB && !settings.webReady() -> "需要先在 设置 → 连接 → 联网搜索 里填 Exa 或 Tavily 的 Key（或者填 DeepSeek Key）"
                    s.id == SkillIds.NOTION && settings.notionToken.isBlank() -> "需要先在设置里填 Notion 密钥"
                    s.id == SkillIds.MAIL && settings.mails.none { it.ready } -> "需要先在设置里填邮箱"
                    else -> ""
                }
                if (need.isNotEmpty()) Text(need, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (s.id == SkillIds.BROWSER) Text("点开查看、修改存好的流程", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.padding(4.dp))
            Switch(s.enabled, onToggle)
        }
    }
}
