package com.jack.ai.ui

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Attachment
import com.jack.ai.data.Conversation
import com.jack.ai.data.Project
import com.jack.ai.data.memory.ChatMemory

/** 项目页的三个标签：聊天、资料（项目说明以外的背景材料）、记忆（Kavi 自己维护的设定、决定、进度、待办）。 */
private enum class ProjectTab(val label: String) { CHATS("聊天"), FILES("文件"), SOURCES("资料"), MEMORY("记忆") }

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
    /** 在项目文件夹里开终端（参数是 Workspace 的 key）。 */
    onOpenTerminal: (String) -> Unit = {},
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
    var memoryOf by remember { mutableStateOf<Conversation?>(null) }
    var draft by remember { mutableStateOf("") }
    val chats = state.conversations.filter { it.projectId == projectId && it.messages.isNotEmpty() }
    // 删对话、移除资料都不先问：直接做，底下出一条「撤销」（和侧边栏一样）
    val undoBar = remember { androidx.compose.material3.SnackbarHostState() }
    val undoScope = androidx.compose.runtime.rememberCoroutineScope()
    fun offerUndo(label: String, undo: () -> Unit) {
        undoScope.launch {
            undoBar.currentSnackbarData?.dismiss()
            val r = undoBar.showSnackbar(label, actionLabel = "撤销", duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) undo()
        }
    }

    // 和聊天页一样：内容从毛玻璃的顶栏、底栏后面滚过去
    val glass = remember { dev.chrisbanes.haze.HazeState() }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides glass) {
    Scaffold(
        containerColor = colors.background,
        snackbarHost = { androidx.compose.material3.SnackbarHost(undoBar) },
        topBar = {
            Column(Modifier.glassBar(top = true)) {
                TopAppBar(
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(project.iconOrDefault(), fontSize = 20.sp)
                            Spacer(Modifier.size(8.dp))
                            Text(project.name + if (project.isolated) " 🔒" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = { CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.padding(start = 12.dp, end = 4.dp), onClick = onBack) },
                    actions = {
                        Box(Modifier.padding(end = 12.dp)) {
                            CircleIconButton(Icons.Default.MoreVert, "项目菜单") { menu = true }
                            RoundMenu(expanded = menu, onDismiss = { menu = false }) {
                                MenuRow(if (project.pinned) "取消置顶" else "置顶", icon = AppIcons.Pin) { menu = false; vm.saveProject(project.copy(pinned = !project.pinned)) }
                                MenuRow("编辑项目", icon = Icons.Default.Edit) { menu = false; editingProject = true }
                                MenuRow("编辑指令", icon = AppIcons.Brain) { menu = false; editingInstructions = true }
                                MenuRow("删除项目", icon = Icons.Default.Delete, danger = true) { menu = false; deletingProject = true }
                            }
                        }
                    },
                )
                // 聊天 / 资料 / 记忆：一条玻璃胶囊，选中的那块底滑着跟过去
                GlassTabs(ProjectTab.entries.map { it.label }, tab.ordinal, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) { tab = ProjectTab.entries[it] }
            }
        },
        bottomBar = {
            // 直接在这个项目里开新对话：和聊天页一样的毛玻璃胶囊
            Box(Modifier.glassBar(top = false)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
                        .glass(Radii.xl).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft, onValueChange = { draft = it }, maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) Text("给「${project.name}」发消息", color = colors.outline, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        },
                    )
                    val canSend = draft.isNotBlank()
                    Surface(
                        onClick = { vm.newChat(projectId); if (vm.send(draft)) { draft = ""; onOpenChat() } },
                        enabled = canSend, shape = CircleShape,
                        color = if (canSend) colors.primary else colors.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(AppIcons.ArrowUp, "发送", Modifier.size(20.dp), tint = if (canSend) colors.onPrimary else colors.outline)
                        }
                    }
                }
            }
        },
    ) { padding ->
        GlassSource(glass, Modifier.fillMaxSize()) {
            // 换页签时内容淡入淡出，不是硬切
            androidx.compose.animation.Crossfade(tab, animationSpec = androidx.compose.animation.core.tween(Motion.NORMAL), label = "projectTab") { t ->
                when (t) {
                    ProjectTab.CHATS -> if (chats.isEmpty()) Box(Modifier.padding(padding)) {
                        EmptyHint(
                            com.jack.ai.data.ProjectKind.of(project.kind).let { "${it.label} · 还没有聊天" },
                            if (project.instructions.isBlank()) "在下面直接说。想让 Kavi 在这个项目里一直按某种方式做，右上角「编辑指令」写下来。"
                            else "在下面直接说，项目指令、资料和记忆每个对话都会带上。",
                        )
                    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                        start = 8.dp, end = 8.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 8.dp)) {
                        items(chats, key = { it.id }) { c ->
                            Box(Modifier.animateItem()) {
                                ChatItem(
                                    c, false,
                                    onClick = { vm.select(c.id); onOpenChat() },
                                    onRename = { renaming = c },
                                    onDelete = { vm.delete(c.id); offerUndo("删除了「${c.title.take(12)}」") { vm.undoDeleteConversation() } },
                                    onPin = { vm.togglePin(c.id) },
                                    onMemory = { memoryOf = c },
                                )
                            }
                        }
                    }
                    ProjectTab.FILES -> ProjectFiles(project, padding, onOpenTerminal)
                    ProjectTab.SOURCES -> ProjectSources(vm, state, project, padding, ::offerUndo)
                    ProjectTab.MEMORY -> ProjectMemoryTab(vm, project, padding)
                }
            }
        }
    }
    }

    memoryOf?.let { c -> ConversationMemoryDialog(vm, c) { memoryOf = null } }
    if (editingProject) {
        ProjectDialog(project, onDismiss = { editingProject = false }) { vm.saveProject(it); editingProject = false }
    }
    if (editingInstructions) {
        var text by remember { mutableStateOf(atEnd(project.instructions)) }
        AlertDialog(
            onDismissRequest = { editingInstructions = false },
            properties = keepIfEdited(text.text != project.instructions),
            title = { Text("指令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("让 Kavi 在这个项目里专注某件事，或者一直按某种方式回答。项目里的每个对话都会带上。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("例如：这是我的小说，主角叫林舟，风格偏冷峻；每章三千字左右，对话多一些。") },
                        minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth().focusOnShow(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveProject(project.copy(instructions = text.text.trim())); editingInstructions = false }) { Text("保存") }
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
private fun ProjectSources(vm: ChatViewModel, state: UiState, project: Project, bars: PaddingValues,
                           offerUndo: (String, () -> Unit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
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
            RoundMenu(expanded = adding, onDismiss = { adding = false }) {
                MenuRow("添加文件", icon = AppIcons.File) { adding = false; pickFile.launch(arrayOf("*/*")) }
                MenuRow("添加文字", icon = Icons.Default.Edit) { adding = false; pasting = true }
            }
        }
    }
    if (project.files.isEmpty()) Box(Modifier.padding(bars)) {
        EmptyHint("给 Kavi 更多背景资料", "加上文件或一段文字（资料、设定、参考文章），项目里的每个对话都能用；太长的会在需要时搜着看。", addButton)
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        start = 16.dp, end = 16.dp, top = bars.calculateTopPadding() + 12.dp, bottom = bars.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(project.files, key = { it.id }) { f ->
            // 点一下看里面写了什么
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem().fillMaxWidth().glass(Radii.m).clip(Radii.m)
                .clickable { viewing = f }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                Icon(AppIcons.File, null, Modifier.size(20.dp), tint = colors.primary)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.text.length} 字", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = {
                    vm.saveProject(project.copy(files = project.files.filterNot { it.id == f.id }))
                    // 撤销：放回原来的位置（按当时的项目找，期间别的改动不丢）
                    val at = project.files.indexOfFirst { it.id == f.id }
                    offerUndo("移除了「${f.name.take(12)}」") {
                        vm.state.value.project(project.id)?.let { now ->
                            if (now.files.none { it.id == f.id }) vm.saveProject(now.copy(files = now.files.toMutableList().apply { add(at.coerceIn(0, size), f) }))
                        }
                    }
                }) {
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
    viewing?.let { f -> TextViewer(f.name, load = { f.text to false }, onDismiss = { viewing = null }) }
    if (pasting) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            properties = keepIfEdited(text.isNotBlank()),
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
private fun ProjectMemoryTab(vm: ChatViewModel, project: Project, bars: PaddingValues) {
    val colors = MaterialTheme.colorScheme
    var items by remember(project.id) { mutableStateOf(vm.projectMemory(project.id)) }
    var adding by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(ChatMemory.SECTIONS.last()) }
    fun save(next: List<String>) { items = next; vm.setProjectMemory(project.id, next) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bars).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Box(Modifier.fillMaxWidth().glass(Radii.l)) {
                Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 8.dp)) {
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
        Spacer(Modifier.size(4.dp))
        GlassTabs(ChatMemory.SECTIONS, ChatMemory.SECTIONS.indexOf(section).coerceAtLeast(0)) { section = ChatMemory.SECTIONS[it] }
        Row(verticalAlignment = Alignment.CenterVertically) {
            fun add() {
                val entry = "【$section】" + adding.trim()
                if (entry !in items) save(items + entry)
                adding = ""
            }
            // 回车就加上，键盘不收，接着打下一条
            OutlinedTextField(adding, { adding = it }, placeholder = { Text("加一条$section") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = DoneKeyboard, keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (adding.isNotBlank()) add() }))
            TextButton(enabled = adding.isNotBlank(), onClick = { add() }) { Text("添加") }
        }
    }
}

/** 项目图标能选的。 */
private val PROJECT_ICONS = listOf("📁", "💻", "🎨", "✍️", "💼", "🎬", "📚", "❤️", "✈️", "💡", "🧪", "🏠", "💰", "🎨", "🎮", "🍜", "🛒", "📈", "🧠", "⭐")

fun Project.iconOrDefault(): String = icon.ifBlank { com.jack.ai.data.ProjectKind.of(kind).takeIf { it != com.jack.ai.data.ProjectKind.GENERAL }?.icon ?: "📁" }

/**
 * 新建或编辑项目：类型（代码 / 写作 / 通用创作，决定开局带哪些工具）、图标、名字、记忆方式（默认 / 仅项目）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectDialog(initial: Project?, onDismiss: () -> Unit, onSave: (Project) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon.orEmpty()) }
    var isolated by remember { mutableStateOf(initial?.isolated ?: false) }
    var kind by remember { mutableStateOf(com.jack.ai.data.ProjectKind.of(initial?.kind)) }
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
                        Surface(onClick = { picking = true }, shape = Radii.m, color = glassFill, border = BorderStroke(0.8.dp, glassEdge()), modifier = Modifier.size(52.dp)) {
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
                    // 新建时一弹出来就能打名字
                    OutlinedTextField(name, { name = it }, placeholder = { Text("项目名称") }, singleLine = true,
                        modifier = Modifier.weight(1f).then(if (initial == null) Modifier.focusOnShow() else Modifier),
                        keyboardOptions = DoneKeyboard)
                }
                Text("类型", style = MaterialTheme.typography.titleSmall)
                com.jack.ai.data.ProjectKind.entries.forEach { k ->
                    MemoryModeOption("${k.icon} ${k.label}", k.desc, kind == k) {
                        // 图标还是上一个类型的默认的（或没选），跟着换
                        if (icon.isBlank() || com.jack.ai.data.ProjectKind.entries.any { it.icon == icon }) icon = k.icon
                        kind = k
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
                onSave((initial ?: Project(name = "")).copy(name = name.trim(), icon = icon, isolated = isolated, kind = kind.id))
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
        shape = Radii.m,
        color = glassFill,
        border = if (selected) BorderStroke(1.5.dp, colors.primary) else BorderStroke(0.8.dp, glassEdge()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
