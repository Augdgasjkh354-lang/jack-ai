package com.jack.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Attachment
import com.jack.ai.data.Conversation
import com.jack.ai.data.Project
import com.jack.ai.data.memory.ChatMemory

/** 项目页的三个标签：聊天、资料（项目说明以外的背景材料）、记忆（Kavi 自己维护的设定、决定、进度、待办）。 */
private enum class ProjectTab(val label: String) { CHATS("聊天"), SOURCES("资料"), MEMORY("记忆") }

/**
 * 项目页：顶上图标和名字、右上角菜单（置顶、编辑项目、编辑指令、记忆方式、删除），下面三个标签，
 * 最底下的输入框直接在这个项目里开新对话。
 */
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
    val colors = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(ProjectTab.CHATS) }
    var menu by remember { mutableStateOf(false) }
    var editingProject by remember { mutableStateOf(false) }
    var editingInstructions by remember { mutableStateOf(false) }
    var deletingProject by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var memoryOf by remember { mutableStateOf<Conversation?>(null) }
    var draft by remember { mutableStateOf("") }
    val chats = state.conversations.filter { it.projectId == projectId && it.messages.isNotEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(project.iconOrDefault(), fontSize = 20.sp)
                        Spacer(Modifier.size(8.dp))
                        Text(project.name + if (project.isolated) " 🔒" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "项目菜单") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(if (project.pinned) "取消置顶" else "置顶") },
                                onClick = { menu = false; vm.saveProject(project.copy(pinned = !project.pinned)) })
                            DropdownMenuItem(text = { Text("编辑项目") }, onClick = { menu = false; editingProject = true })
                            DropdownMenuItem(text = { Text("编辑指令") }, onClick = { menu = false; editingInstructions = true })
                            DropdownMenuItem(text = { Text("删除项目", color = colors.error) }, onClick = { menu = false; deletingProject = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            // 直接在这个项目里开新对话
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant).padding(start = 16.dp, end = 4.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft, onValueChange = { draft = it }, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) Text("给「${project.name}」发消息", color = colors.outline, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                IconButton(enabled = draft.isNotBlank(), onClick = {
                    vm.newChat(projectId)
                    if (vm.send(draft)) { draft = ""; onOpenChat() }
                }) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (draft.isNotBlank()) colors.primary else colors.outlineVariant),
                        contentAlignment = Alignment.Center) {
                        Icon(AppIcons.ArrowUp, "发送", Modifier.size(18.dp), tint = colors.onPrimary)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectTab.entries.forEach { t ->
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                }
            }
            when (tab) {
                ProjectTab.CHATS -> if (chats.isEmpty()) EmptyHint(
                    "还没有聊天",
                    if (project.instructions.isBlank()) "在下面直接说。想让 Kavi 在这个项目里一直按某种方式做，右上角「编辑指令」写下来。"
                    else "在下面直接说，项目指令、资料和记忆每个对话都会带上。",
                ) else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
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
                ProjectTab.SOURCES -> ProjectSources(vm, state, project)
                ProjectTab.MEMORY -> ProjectMemoryTab(vm, project)
            }
        }
    }

    memoryOf?.let { c -> ConversationMemoryDialog(vm, c) { memoryOf = null } }
    if (editingProject) {
        ProjectDialog(project, onDismiss = { editingProject = false }) { vm.saveProject(it); editingProject = false }
    }
    if (editingInstructions) {
        var text by remember { mutableStateOf(project.instructions) }
        AlertDialog(
            onDismissRequest = { editingInstructions = false },
            title = { Text("指令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("让 Kavi 在这个项目里专注某件事，或者一直按某种方式回答。项目里的每个对话都会带上。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("例如：这是我的小说，主角叫林舟，风格偏冷峻；每章三千字左右，对话多一些。") },
                        minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveProject(project.copy(instructions = text.trim())); editingInstructions = false }) { Text("保存") }
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

@Composable
private fun EmptyHint(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.size(6.dp)); it() }
    }
}

/** 资料：项目里每个对话都能用的背景材料。可以加文件，也可以直接贴一段文字。 */
@Composable
private fun ProjectSources(vm: ChatViewModel, state: UiState, project: Project) {
    val colors = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addProjectFile(project.id, uri)
    }
    val addButton: @Composable () -> Unit = {
        Box {
            OutlinedButton(onClick = { adding = true }, enabled = !state.importing) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("添加")
            }
            DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                DropdownMenuItem(text = { Text("添加文件") }, onClick = { adding = false; pickFile.launch(arrayOf("*/*")) })
                DropdownMenuItem(text = { Text("添加文字") }, onClick = { adding = false; pasting = true })
            }
        }
    }
    if (project.files.isEmpty()) {
        EmptyHint("给 Kavi 更多背景资料", "加上文件或一段文字（资料、设定、参考文章），项目里的每个对话都能用；太长的会在需要时搜着看。", addButton)
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(project.files, key = { it.id }) { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.File, null, Modifier.size(20.dp), tint = colors.primary)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.text.length} 字", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = { vm.saveProject(project.copy(files = project.files.filterNot { it.id == f.id })) }) {
                    Icon(Icons.Default.Close, "移除")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                addButton()
                if (state.importing) { Spacer(Modifier.size(12.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            }
        }
    }
    if (pasting) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("添加文字") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, placeholder = { Text("贴进来一段资料、设定或参考文字") },
                        minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val title = name.trim().ifBlank { text.trim().lineSequence().first().take(16) }
                    vm.saveProject(project.copy(files = project.files + Attachment(kind = "file", name = "$title.txt", text = text.trim())))
                    pasting = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("取消") } },
        )
    }
}

/**
 * 记忆：Kavi 在这个项目里自己记、自己整理的设定、决定、进度、待办（见 MemoryReview），新对话开头都带着。
 * 这里能看、删，也能自己加一条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectMemoryTab(vm: ChatViewModel, project: Project) {
    val colors = MaterialTheme.colorScheme
    var items by remember(project.id) { mutableStateOf(vm.projectMemory(project.id)) }
    var adding by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(ChatMemory.SECTIONS.last()) }
    fun save(next: List<String>) { items = next; vm.setProjectMemory(project.id, next) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            (if (project.isolated) "仅项目记忆：和外面完全隔开，只用这里的记忆。" else "Kavi 聊着聊着会自己更新这里，") +
                "离开一个对话时也会补记一次，下个对话就从这里接着做。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
        if (items.isEmpty()) Text("还没有。聊几轮后，定下的事、做到哪了、还剩什么会出现在这里。", color = colors.outline,
            style = MaterialTheme.typography.bodyMedium)
        val groups = items.groupBy { ChatMemory.sectionOf(it) ?: "其他" }
        (ChatMemory.SECTIONS + "其他").forEach { sec ->
            val list = groups[sec] ?: return@forEach
            Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(sec, style = MaterialTheme.typography.titleSmall, color = colors.primary)
                    list.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.removePrefix("【$sec】"), Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { save(items - item) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "删除", Modifier.size(16.dp), tint = colors.outline)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChatMemory.SECTIONS.forEach { s -> FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(adding, { adding = it }, placeholder = { Text("加一条$section") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = adding.isNotBlank(), onClick = {
                val entry = "【$section】" + adding.trim()
                if (entry !in items) save(items + entry)
                adding = ""
            }) { Text("添加") }
        }
    }
}

/** 新建项目时一点就填好名字和图标的几类。 */
private val PROJECT_KINDS = listOf("✍️" to "写作", "💼" to "生意", "🎬" to "视频", "📚" to "学习", "❤️" to "健康", "✈️" to "旅行")

/** 项目图标能选的。 */
private val PROJECT_ICONS = listOf("📁", "✍️", "💼", "🎬", "📚", "❤️", "✈️", "💡", "🧪", "🏠", "💰", "🎨", "🎮", "🍜", "🛒", "📈", "🧠", "⭐")

fun Project.iconOrDefault(): String = icon.ifBlank { "📁" }

/**
 * 新建或编辑项目：图标、名字、常见的几类一点就填好；记忆方式（默认 / 仅项目）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectDialog(initial: Project?, onDismiss: () -> Unit, onSave: (Project) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon.orEmpty()) }
    var isolated by remember { mutableStateOf(initial?.isolated ?: false) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建项目" else "编辑项目") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initial == null) Text("把相关的对话和资料放在一起，Kavi 在它们之间共用指令、资料和记忆。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Surface(onClick = { picking = true }, shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant, modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(icon.ifBlank { "📁" }, fontSize = 24.sp) }
                        }
                        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                            FlowRow(Modifier.padding(8.dp).size(width = 220.dp, height = 140.dp)) {
                                PROJECT_ICONS.forEach { e ->
                                    Text(e, fontSize = 24.sp, modifier = Modifier.clickable { icon = e; picking = false }.padding(6.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    OutlinedTextField(name, { name = it }, placeholder = { Text("项目名称") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (initial == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PROJECT_KINDS.forEach { (e, n) ->
                        FilterChip(selected = name == n && icon == e, onClick = { name = n; icon = e }, label = { Text("$e $n") })
                    }
                }
                Text("记忆", style = MaterialTheme.typography.titleSmall)
                MemoryModeOption("默认", "用全局记忆，也会往里记关于你的事；外面的对话也翻得到这里的聊天。", !isolated) { isolated = false }
                MemoryModeOption("仅项目", "只用项目自己的记忆，和外面完全隔开：不读不写全局记忆，外面的对话翻不到这里。", isolated) { isolated = true }
                if (initial != null && isolated && !initial.isolated) Text("改之前已经记进全局记忆的内容不会自动删掉，需要的话到「记忆」里删。",
                    style = MaterialTheme.typography.bodySmall, color = colors.outline)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave((initial ?: Project(name = "")).copy(name = name.trim(), icon = icon, isolated = isolated))
            }) { Text(if (initial == null) "创建项目" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MemoryModeOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
