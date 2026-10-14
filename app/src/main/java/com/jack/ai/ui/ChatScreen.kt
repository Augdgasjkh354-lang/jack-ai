package com.jack.ai.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Attachment
import com.jack.ai.data.Conversation
import com.jack.ai.data.MODEL_FLASH
import com.jack.ai.data.MODEL_PRO
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.modelLabel
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    state: UiState,
    onOpenSettings: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val conv = state.current
    val project = state.project(state.currentProjectId)
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val bg = MaterialTheme.colorScheme.background

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                state = state,
                onNew = { vm.newChat(); scope.launch { drawerState.close() } },
                onSelect = { vm.select(it); scope.launch { drawerState.close() } },
                onDelete = vm::delete,
                onRename = vm::rename,
                onOpenProject = { scope.launch { drawerState.close() }; onOpenProject(it) },
                onNewProject = { scope.launch { drawerState.close() }; onNewProject() },
                onOpenMemory = { scope.launch { drawerState.close() }; onOpenMemory() },
                onOpenSkills = { scope.launch { drawerState.close() }; onOpenSkills() },
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            containerColor = bg,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = bg, scrolledContainerColor = bg),
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                conv?.takeIf { it.messages.isNotEmpty() }?.title ?: "Jack AI",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                            ModelPicker(state.settings.model, project?.name) { m -> vm.updateSettings { it.copy(model = m) } }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newChat(state.currentProjectId) }) {
                            Icon(AppIcons.NewChat, contentDescription = "新对话", Modifier.size(22.dp))
                        }
                    },
                )
            },
            bottomBar = {
                InputBar(
                    vm = vm,
                    state = state,
                    text = text,
                    onTextChange = { text = it },
                    onNeedTavily = {
                        Toast.makeText(ctx, "请先在设置里填写 Tavily API Key", Toast.LENGTH_LONG).show()
                        onOpenSettings()
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (conv == null || conv.messages.isEmpty()) {
                    EmptyState(vm, state, conv, onOpenMemory, onSuggest = { text = it })
                } else {
                    MessageList(vm, conv, state.streaming)
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(model: String, projectName: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { open = true }.padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                (projectName?.let { "$it · " } ?: "") + modelLabel(model),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                MODEL_FLASH to "快、便宜，能看图片",
                MODEL_PRO to "更聪明，适合难题（不能看图）",
            ).forEach { (id, desc) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(modelLabel(id))
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { if (id == model) Icon(Icons.Default.Check, null, tint = colors.primary) else Spacer(Modifier.size(24.dp)) },
                    onClick = { onPick(id); open = false },
                )
            }
        }
    }
}

private data class Suggestion(val title: String, val sub: String, val prompt: String)

private val SUGGESTIONS = listOf(
    Suggestion("写一份周报", "生成 Word 文档", "帮我写一份本周工作周报，做成 Word 文档。我这周做了："),
    Suggestion("做个演示文稿", "PPT，自动配色", "帮我做一个关于「」的 PPT，大概 8 页。"),
    Suggestion("今天有什么新闻", "联网搜索最新消息", "今天有什么值得关注的新闻？"),
    Suggestion("整理成表格", "生成 Excel", "帮我把下面的内容整理成 Excel 表格：\n"),
)

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

@Composable
private fun EmptyState(
    vm: ChatViewModel,
    state: UiState,
    conv: Conversation?,
    onOpenMemory: () -> Unit,
    onSuggest: (String) -> Unit,
) {
    val project = state.project(state.currentProjectId)
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        LogoMark(52.dp)
        Spacer(Modifier.height(20.dp))
        val name = state.settings.userName.trim()
        Text(
            greeting() + if (name.isNotEmpty()) "，$name" else "",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (project != null) "在项目「${project.name}」里，想做点什么？" else "今天想聊点什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        state.dreams.firstOrNull { !it.seen }?.let { d ->
            Spacer(Modifier.height(20.dp))
            DreamCard(d, initiallyOpen = false, onClick = onOpenMemory)
        }
        Spacer(Modifier.height(28.dp))
        SUGGESTIONS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { sg ->
                    Surface(
                        onClick = { onSuggest(sg.prompt) },
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfaceContainerLowest,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(sg.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(sg.sub, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("人设", style = MaterialTheme.typography.labelMedium, color = colors.outline)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
            val selectedName = conv?.presetName ?: ""
            items(state.settings.presets, key = { it.id }) { p ->
                val sel = p.name == selectedName
                Pill(label = p.name, active = sel, onClick = { vm.choosePreset(p) })
            }
        }
    }
}

/** 圆角小按钮：选中时浅陶土底色，没选中时只有细边框。 */
@Composable
private fun Pill(
    label: String,
    active: Boolean,
    icon: ImageVector? = null,
    dropdown: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) colors.primaryContainer else Color.Transparent,
        contentColor = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = if (active) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = if (icon != null) 10.dp else 14.dp, end = if (dropdown) 6.dp else 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.size(5.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (dropdown) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MessageList(vm: ChatViewModel, conv: Conversation, streaming: Boolean) {
    val listState = rememberLazyListState()
    val reversed = conv.messages.asReversed()
    val lastId = conv.messages.last().id
    // 倒序排列：最新的消息在最下面，流式输出时自动贴底
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        reversed.forEach { msg ->
            if (msg.id == conv.summaryUntilId) {
                item(key = "summary-divider") { SummaryDivider(conv.summary) }
            }
            item(key = msg.id) {
                Box(Modifier.animateItem()) {
                    if (msg.role == "user") {
                        UserBubble(msg, onDelete = { vm.deleteFrom(msg.id) })
                    } else {
                        val isLast = msg.id == lastId
                        AssistantMessage(
                            msg = msg,
                            streaming = streaming && isLast,
                            isLast = isLast,
                            onRegenerate = vm::regenerate,
                            onDelete = { vm.deleteFrom(msg.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryDivider(summary: String) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 4.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  以上较早的内容已压缩 · 点击查看提要  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("前情提要") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MarkdownText(summary, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surfaceVariant,
                        textSizeSp = 14f)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun InputBar(
    vm: ChatViewModel,
    state: UiState,
    text: String,
    onTextChange: (String) -> Unit,
    onNeedTavily: () -> Unit,
) {
    var attachMenu by remember { mutableStateOf(false) }
    var thinkMenu by remember { mutableStateOf(false) }
    val settings = state.settings
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.red < 0.5f

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) {
        vm.addImages(it)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.addFiles(it)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (state.compressingId != null && state.compressingId == state.currentId) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("正在压缩较早的对话…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = if (dark) 0.dp else 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
                if (state.pending.isNotEmpty() || state.importing) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        items(state.pending, key = { it.id }) { att -> PendingChip(att) { vm.removePending(att.id) } }
                        if (state.importing) {
                            item { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        }
                    }
                }
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text("问点什么…", color = colors.outline) },
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        Surface(
                            onClick = { attachMenu = true },
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.padding(start = 6.dp).size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, "更多", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("图片") },
                                leadingIcon = { Icon(AppIcons.Image, null) },
                                onClick = {
                                    attachMenu = false
                                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("文件（PDF、Word、文本、代码）") },
                                leadingIcon = { Icon(AppIcons.File, null) },
                                onClick = {
                                    attachMenu = false
                                    pickFiles.launch(arrayOf("*/*"))
                                },
                            )
                            val conv = state.current
                            if (conv != null && conv.messages.size >= 4) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("压缩上下文")
                                            Text(
                                                "已用约 ${conv.lastPromptTokens / 1000}k / ${settings.compressTokens / 1000}k，到了会自动压缩",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(AppIcons.Compress, null) },
                                    enabled = !state.streaming && state.compressingId == null,
                                    onClick = {
                                        attachMenu = false
                                        vm.compress()
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        Pill(
                            label = settings.thinking.label,
                            active = settings.thinking != ThinkLevel.OFF,
                            icon = AppIcons.Brain,
                            dropdown = true,
                            onClick = { thinkMenu = true },
                        )
                        DropdownMenu(expanded = thinkMenu, onDismissRequest = { thinkMenu = false }) {
                            ThinkLevel.entries.forEach { lv ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(lv.label)
                                            Text(
                                                when (lv) {
                                                    ThinkLevel.OFF -> "直接回答，最快"
                                                    ThinkLevel.LOW -> "简单想一想"
                                                    ThinkLevel.HIGH -> "认真思考（默认）"
                                                    ThinkLevel.MAX -> "想到最透，最慢最贵"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if (lv == settings.thinking) Icon(Icons.Default.Check, null, tint = colors.primary)
                                        else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = { vm.updateSettings { it.copy(thinking = lv) }; thinkMenu = false },
                                )
                            }
                        }
                    }
                    Pill(
                        label = "联网",
                        active = settings.webSearch && settings.tavilyKey.isNotBlank(),
                        icon = AppIcons.Globe,
                        onClick = {
                            if (settings.tavilyKey.isBlank()) onNeedTavily()
                            else vm.updateSettings { it.copy(webSearch = !it.webSearch) }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.streaming) {
                        Surface(onClick = vm::stop, shape = CircleShape, color = colors.onSurface, modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.Stop, "停止", Modifier.size(16.dp), tint = colors.surface)
                            }
                        }
                    } else {
                        val canSend = (text.isNotBlank() || state.pending.isNotEmpty()) && !state.importing
                        Surface(
                            onClick = { if (vm.send(text)) onTextChange("") },
                            enabled = canSend,
                            shape = CircleShape,
                            color = if (canSend) colors.primary else colors.surfaceVariant,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.ArrowUp, "发送", Modifier.size(20.dp),
                                    tint = if (canSend) colors.onPrimary else colors.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingChip(att: Attachment, onRemove: () -> Unit) {
    if (att.kind == "image") {
        Box {
            Thumbnail(att.path, 56.dp)
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp).align(Alignment.TopEnd)) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface) {
                    Icon(Icons.Default.Close, "移除", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    } else {
        InputChip(
            selected = false,
            onClick = onRemove,
            label = { Text(att.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(AppIcons.File, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Default.Close, "移除", Modifier.size(16.dp)) },
        )
    }
}
