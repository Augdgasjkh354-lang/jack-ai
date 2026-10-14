package com.jack.ai.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
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
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                conv?.title ?: "新对话",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (project != null) {
                                Text(
                                    "项目：${project.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = {
                        ModelPicker(state.settings.model) { m -> vm.updateSettings { it.copy(model = m) } }
                        IconButton(onClick = { vm.newChat(state.currentProjectId) }) {
                            Icon(Icons.Default.Add, contentDescription = "新对话")
                        }
                    },
                )
            },
            bottomBar = {
                InputBar(
                    vm = vm,
                    state = state,
                    onNeedTavily = {
                        Toast.makeText(ctx, "请先在设置里填写 Tavily API Key", Toast.LENGTH_LONG).show()
                        onOpenSettings()
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (conv == null || conv.messages.isEmpty()) {
                    EmptyState(vm, state, conv, onOpenMemory)
                } else {
                    MessageList(vm, conv, state.streaming)
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(model: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(modelLabel(model))
            Icon(Icons.Default.ArrowDropDown, null)
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
                            Text(desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { if (id == model) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { onPick(id); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(vm: ChatViewModel, state: UiState, conv: Conversation?, onOpenMemory: () -> Unit) {
    val project = state.project(state.currentProjectId)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Chat, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        val name = state.settings.userName.trim()
        Text(
            if (name.isNotEmpty()) "$name，有什么可以帮你？" else "有什么可以帮你？",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (project != null) {
            Spacer(Modifier.height(6.dp))
            Text("在项目「${project.name}」中", color = MaterialTheme.colorScheme.primary)
        }
        state.dreams.firstOrNull { !it.seen }?.let { d ->
            Spacer(Modifier.height(16.dp))
            DreamCard(d, initiallyOpen = false, onClick = onOpenMemory)
        }
        Spacer(Modifier.height(24.dp))
        Text("选一个人设（可选）", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            val selectedName = conv?.presetName ?: ""
            state.settings.presets.forEach { p ->
                FilterChip(
                    selected = p.name == selectedName,
                    onClick = { vm.choosePreset(p) },
                    label = { Text(p.name) },
                )
            }
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        reversed.forEach { msg ->
            if (msg.id == conv.summaryUntilId) {
                item(key = "summary-divider") { SummaryDivider(conv.summary) }
            }
            item(key = msg.id) {
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
private fun InputBar(vm: ChatViewModel, state: UiState, onNeedTavily: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    var thinkMenu by remember { mutableStateOf(false) }
    val settings = state.settings

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) {
        vm.addImages(it)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.addFiles(it)
    }

    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    FilterChip(
                        selected = settings.thinking != ThinkLevel.OFF,
                        onClick = { thinkMenu = true },
                        label = { Text(settings.thinking.label) },
                        leadingIcon = { Icon(AppIcons.Brain, null, Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    if (lv == settings.thinking) Icon(Icons.Default.Check, null)
                                    else Spacer(Modifier.size(24.dp))
                                },
                                onClick = { vm.updateSettings { it.copy(thinking = lv) }; thinkMenu = false },
                            )
                        }
                    }
                }
                val webOn = settings.webSearch && settings.tavilyKey.isNotBlank()
                FilterChip(
                    selected = webOn,
                    onClick = {
                        if (settings.tavilyKey.isBlank()) onNeedTavily()
                        else vm.updateSettings { it.copy(webSearch = !it.webSearch) }
                    },
                    label = { Text("联网搜索") },
                    leadingIcon = { Icon(AppIcons.Globe, null, Modifier.size(18.dp)) },
                )
            }

            if (state.compressingId != null && state.compressingId == state.currentId) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("正在压缩较早的对话…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (state.pending.isNotEmpty() || state.importing) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    items(state.pending, key = { it.id }) { att -> PendingChip(att) { vm.removePending(att.id) } }
                    if (state.importing) {
                        item { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "更多")
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("给 DeepSeek 发消息") },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.size(8.dp))
                if (state.streaming) {
                    FilledIconButton(onClick = vm::stop, modifier = Modifier.size(52.dp)) {
                        Icon(AppIcons.Stop, contentDescription = "停止")
                    }
                } else {
                    FilledIconButton(
                        onClick = { if (vm.send(text)) text = "" },
                        enabled = (text.isNotBlank() || state.pending.isNotEmpty()) && !state.importing,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(AppIcons.Send, contentDescription = "发送")
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
