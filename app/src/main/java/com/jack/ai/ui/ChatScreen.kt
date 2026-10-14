package com.jack.ai.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.MODEL_CHAT
import com.jack.ai.data.MODEL_REASONER
import com.jack.ai.data.SearchRecord
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, state: UiState, onOpenSettings: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val conv = state.current
    val ctx = LocalContext.current

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = state.conversations.filter { it.messages.isNotEmpty() },
                currentId = state.currentId,
                onNew = { vm.newChat(); scope.launch { drawerState.close() } },
                onSelect = { vm.select(it); scope.launch { drawerState.close() } },
                onDelete = vm::delete,
                onRename = vm::rename,
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            conv?.title ?: "新对话",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "历史对话")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::newChat) {
                            Icon(Icons.Default.Add, contentDescription = "新对话")
                        }
                    },
                )
            },
            bottomBar = {
                InputBar(
                    streaming = state.streaming,
                    reasoning = state.settings.model == MODEL_REASONER,
                    webSearch = state.settings.webSearch,
                    onToggleReasoning = {
                        vm.updateSettings { it.copy(model = if (it.model == MODEL_REASONER) MODEL_CHAT else MODEL_REASONER) }
                    },
                    onToggleWeb = {
                        if (state.settings.tavilyKey.isBlank()) {
                            Toast.makeText(ctx, "请先在设置里填写 Tavily API Key", Toast.LENGTH_LONG).show()
                            onOpenSettings()
                        } else {
                            vm.updateSettings { it.copy(webSearch = !it.webSearch) }
                        }
                    },
                    onSend = vm::send,
                    onStop = vm::stop,
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (conv == null || conv.messages.isEmpty()) {
                    EmptyState(vm, state, conv)
                } else {
                    MessageList(vm, conv, state.streaming)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(vm: ChatViewModel, state: UiState, conv: Conversation?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Chat, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("有什么可以帮你？", style = MaterialTheme.typography.headlineSmall)
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
    val lastAssistantId = conv.messages.lastOrNull { it.role == "assistant" }?.id
    // 倒序排列：最新的消息在最下面，流式输出时自动贴底
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(reversed, key = { it.id }) { msg ->
            if (msg.role == "user") {
                UserBubble(msg, onDelete = { vm.deleteFrom(msg.id) })
            } else {
                val isLast = msg.id == lastAssistantId && msg.id == conv.messages.last().id
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: ChatMessage, onDelete: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                .combinedClickable(onClick = {}, onLongClick = { menu = true }),
        ) {
            Text(
                msg.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text("这条消息") },
            text = { Text("复制内容，或删除这条及之后的所有消息。") },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(msg.content))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    menu = false
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { onDelete(); menu = false }) { Text("删除") }
            },
        )
    }
}

@Composable
private fun AssistantMessage(
    msg: ChatMessage,
    streaming: Boolean,
    isLast: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (msg.searches.isNotEmpty()) {
            SearchBox(msg.searches)
            Spacer(Modifier.height(8.dp))
        }
        if (msg.reasoning.isNotEmpty()) {
            ThinkingBox(msg.reasoning, thinking = streaming && msg.content.isEmpty())
            Spacer(Modifier.height(8.dp))
        }
        if (msg.content.isNotEmpty()) {
            MarkdownText(
                markdown = msg.content,
                color = colors.onSurface,
                codeBackground = colors.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (streaming && msg.reasoning.isEmpty() && msg.searches.none { it.pending }) {
            CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
        }
        msg.error?.let { err ->
            Spacer(Modifier.height(4.dp))
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Text(
                    err,
                    color = colors.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (msg.content.isNotEmpty()) {
                    SmallAction(AppIcons.Copy, "复制") {
                        clipboard.setText(AnnotatedString(msg.content))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                if (isLast) SmallAction(Icons.Default.Refresh, "重新回答", onRegenerate)
                SmallAction(Icons.Default.Delete, "删除", onDelete)
                if (msg.model.isNotEmpty()) {
                    Text(
                        if (msg.model == MODEL_REASONER) "深度思考" else msg.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, desc, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThinkingBox(reasoning: String, thinking: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(thinking) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { expanded = !expanded }),
            ) {
                Icon(AppIcons.Brain, null, Modifier.size(16.dp), tint = colors.primary)
                Spacer(Modifier.size(6.dp))
                Text(
                    if (thinking) "思考中…" else "已深度思考",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(expanded) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchBox(searches: List<SearchRecord>) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val searching = searches.any { it.pending }
    val sources = searches.flatMap { it.sources }
    Surface(
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Globe, null, Modifier.size(16.dp), tint = colors.primary)
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    if (searching) "正在搜索：${searches.last { it.pending }.query}"
                    else "已搜索 ${searches.size} 次，找到 ${sources.size} 个网页",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var n = 0
                    searches.forEach { rec ->
                        Text(
                            "🔍 " + rec.query + (rec.error?.let { "（$it）" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rec.error != null) colors.error else colors.onSurfaceVariant,
                        )
                        rec.sources.forEach { src ->
                            n++
                            val idx = n
                            Text(
                                "[$idx] ${src.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { runCatching { uriHandler.openUri(src.url) } }
                                    .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    streaming: Boolean,
    reasoning: Boolean,
    webSearch: Boolean,
    onToggleReasoning: () -> Unit,
    onToggleWeb: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 2.dp) {
      Column(
          Modifier
              .fillMaxWidth()
              .navigationBarsPadding()
              .imePadding()
              .padding(horizontal = 12.dp, vertical = 6.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = reasoning,
                onClick = onToggleReasoning,
                label = { Text("深度思考") },
                leadingIcon = { Icon(AppIcons.Brain, null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = webSearch,
                onClick = onToggleWeb,
                label = { Text("联网搜索") },
                leadingIcon = { Icon(AppIcons.Globe, null, Modifier.size(18.dp)) },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("给 DeepSeek 发消息") },
                modifier = Modifier.weight(1f),
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (streaming) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
                    Icon(AppIcons.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = { onSend(text); text = "" },
                    enabled = text.isNotBlank(),
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
private fun HistoryDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onSettings: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Jack AI",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Add, null) },
                label = { Text("新对话") },
                selected = false,
                onClick = onNew,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (conversations.isEmpty()) {
                Text(
                    "还没有聊天记录",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(conversations, key = { it.id }) { c ->
                    NavigationDrawerItem(
                        label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = c.id == currentId,
                        onClick = { onSelect(c.id) },
                        badge = {
                            Row {
                                IconButton(onClick = { renaming = c }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Edit, "重命名", Modifier.size(18.dp))
                                }
                                IconButton(onClick = { deleting = c }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp))
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            HorizontalDivider()
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Settings, null) },
                label = { Text("设置") },
                selected = false,
                onClick = onSettings,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    renaming?.let { c ->
        var title by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(c.id, title); renaming = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除对话？") },
            text = { Text("「${c.title}」会被永久删除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(c.id); deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
