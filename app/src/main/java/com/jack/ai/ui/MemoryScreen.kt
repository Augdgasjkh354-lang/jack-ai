package com.jack.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.sp
import com.jack.ai.ChatViewModel
import kotlinx.coroutines.launch
import com.jack.ai.UiState
import com.jack.ai.data.DreamEntry
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.inSpace
import com.jack.ai.data.spaceName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    // 这一页始终是夜晚：深蓝夜色、星点、月亮色的强调，不跟着浅色 / 深色模式变
    MaterialTheme(colorScheme = NightColors, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(NIGHT_SKY)) {
            NightStars(Modifier.fillMaxWidth().height(320.dp))
            MemoryPage(vm, state, onBack)
        }
    }
}

/** 夜空：上面深蓝，往下慢慢透出一点紫。 */
private val NIGHT_SKY = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(androidx.compose.ui.graphics.Color(0xFF0A0F24), androidx.compose.ui.graphics.Color(0xFF141A3A), androidx.compose.ui.graphics.Color(0xFF0D1229)),
)

private val NightColors = androidx.compose.material3.darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFF2D38A),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1B1733),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF2B2F5C),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFF6EBCB),
    secondary = androidx.compose.ui.graphics.Color(0xFFB7BDE0),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1A2147),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFEDEFFB),
    background = androidx.compose.ui.graphics.Color.Transparent,
    surface = androidx.compose.ui.graphics.Color(0xFF111735),
    onSurface = androidx.compose.ui.graphics.Color(0xFFEDEFFB),
    onBackground = androidx.compose.ui.graphics.Color(0xFFEDEFFB),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1C2349),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFA9B0D3),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0F1431),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF131939),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF171E40),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF1C2349),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF1A2147),
    outline = androidx.compose.ui.graphics.Color(0xFF6F78A6),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF283160),
    error = androidx.compose.ui.graphics.Color(0xFFFF8A80),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryPage(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<MemoryItem?>(null) }
    // 刚删掉的那条：列表上方给个「撤销」，误点了能拿回来
    var undo by remember { mutableStateOf<String?>(null) }
    val settings = state.settings
    // 看哪个记忆空间（删掉了就回到「生活」），按哪个人和事筛
    var spacePick by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val space = spacePick.takeIf { id -> settings.memorySpaces.any { it.id == id } }.orEmpty()
    var aboutPick by remember(space) { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val deepAll by androidx.compose.runtime.produceState(emptyList<MemoryItem>(), state.memories) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jack.ai.data.memory.MemoryOps.deep(ctx) }
    }
    val hot = state.memories.inSpace(space, settings)
    val deepHere = deepAll.inSpace(space, settings)
    fun about(m: MemoryItem) = aboutPick?.let { a -> m.about.any { com.jack.ai.data.memory.MemoryGraph.norm(it) == com.jack.ai.data.memory.MemoryGraph.norm(a) } } ?: true
    val shown = hot.filter(::about)
    LaunchedEffect(Unit) {
        vm.reloadMemory()
        vm.markDreamsSeen()
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = {},
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                    Text("记忆", style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(" ✦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("Kavi 每天凌晨回看聊天，整理关于你的记忆：新的记下，过时的作废，重复的合并，不常用的放到深处。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = NIGHT_CARD)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("记忆", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(settings.memoryEnabled, { on -> vm.updateSettings { it.copy(memoryEnabled = on) } })
                        }
                        // 这两样都在后台调模型（统计页记成「后台 · 聊天时回看」「后台 · 经历摘要」）：关了记忆就都不跑
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("聊天时顺手记", style = MaterialTheme.typography.titleSmall)
                                Text(tidyBreaks("每聊 3 轮用后台模型看一眼，有值得长期记的当场记下"), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(settings.chatReview && settings.memoryEnabled, { on -> vm.updateSettings { it.copy(chatReview = on) } }, enabled = settings.memoryEnabled)
                        }
                        if (!settings.memoryEnabled) Text("记忆关着：聊天时顺手记、经历摘要、夜间整理都不会在后台跑。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("夜间整理（每天 5:30）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(settings.dreamEnabled, { on -> vm.updateSettings { it.copy(dreamEnabled = on) } })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = vm::dreamNow, enabled = !state.dreaming) { Text("现在就整理") }
                            if (state.dreaming) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("正在整理…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item { NudgeCard(vm, settings) }
            item { FactsCard(vm) }
            item { SpaceBar(vm, settings, space) { spacePick = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("关于你", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text("  ${hot.size} 条 · 约 ${com.jack.ai.data.memory.MemoryOps.hotTokens(hot)}/${com.jack.ai.data.memory.MemoryOps.HOT_BUDGET}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "添加记忆") }
                    if (hot.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("清空") }
                }
                AboutBar(hot + deepHere, aboutPick) { aboutPick = it }
                // 关系图：标过的人和事、谁和谁出现在同一条记忆里
                var showGraph by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                if (MemoryGraphHas(hot + deepHere)) {
                    TextButton(onClick = { showGraph = !showGraph }) { Text(if (showGraph) "收起关系图" else "看关系图") }
                    AnimatedVisibility(showGraph) {
                        androidx.compose.material3.Surface(color = NIGHT_CARD, shape = Radii.m,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            MemoryGraphView(hot + deepHere, aboutPick, { aboutPick = it })
                        }
                    }
                }
                undo?.let { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已删除「${t.take(16)}${if (t.length > 16) "…" else ""}」", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.addMemory(t, space); undo = null }) { Text("撤销") }
                    }
                }
                if (hot.isEmpty()) {
                    Text("还没有记忆。", color = MaterialTheme.colorScheme.outline)
                }
            }
            items(shown, key = { it.id }) { m ->
                // 一条一张半透明的小卡
                androidx.compose.material3.Surface(color = NIGHT_CARD, shape = Radii.m,
                    // 删掉的那条淡出、下面的补上来；撤销时淡回来
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.animateItem().fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(m.text, style = MaterialTheme.typography.bodyMedium)
                            aboutLine(m)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)) }
                            // 做梦记下的出处，改过的标一下
                            if (m.source.isNotBlank() || m.history.isNotEmpty()) Text(
                                listOfNotNull(m.source.takeIf { it.isNotBlank() }?.let { "出处 $it" }, "改过 ${m.history.size} 次".takeIf { m.history.isNotEmpty() }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = m }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Edit, "修改", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { undo = m.text; vm.deleteMemory(m.id) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Delete, "删除", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { DeepMemories(vm, state.memories, deepHere.filter(::about), aboutPick != null) }

            item {
                Text("整理记录", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp))
                if (state.dreams.isEmpty()) {
                    Text("还没有整理过。", color = MaterialTheme.colorScheme.outline)
                }
            }
            items(state.dreams, key = { it.id }) { d ->
                androidx.compose.foundation.layout.Box(Modifier.animateItem()) { DreamCard(d, onDelete = { vm.deleteDream(d.id) }) }
            }
        }
    }

    if (adding || editing != null) {
        // 一打开就能打字；改的时候光标在最后
        var field by remember(editing?.id) { mutableStateOf(atEnd(editing?.text ?: "")) }
        val text = field.text
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            properties = keepIfEdited(text != (editing?.text ?: "")),
            title = { Text(if (editing != null) "修改记忆" else "添加记忆") },
            text = {
                OutlinedTextField(field, { field = it }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth().focusOnShow(),
                    placeholder = { Text("例如：用户喜欢简短直接的回答") })
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val e = editing
                    if (e != null) vm.updateMemory(e.id, text) else vm.addMemory(text, space)
                    adding = false; editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    // 还成立但不常用：放到深处，不占每次对话的篇幅
                    editing?.let { e -> TextButton(onClick = { vm.demoteMemory(e.id); editing = null }) { Text("放到深处") } }
                    // 挪到别的空间
                    if (editing != null && settings.memorySpaces.isNotEmpty()) TextButton(onClick = { moving = editing; editing = null }) { Text("挪到…") }
                    TextButton(onClick = { adding = false; editing = null }) { Text("取消") }
                }
            },
        )
    }
    moving?.let { m ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("挪到哪个空间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    (listOf("" to com.jack.ai.data.DEFAULT_SPACE_NAME) + settings.memorySpaces.map { it.id to it.name }).filter { it.first != space }.forEach { (id, name) ->
                        Pill(name, false) { vm.moveMemory(m.id, id); moving = null }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        ConfirmDeleteDialog("清空「${settings.spaceName(space)}」的记忆？", "这个空间里常记的都会被删除，无法恢复（深处的和别的空间不动）。", onDismiss = { confirmClear = false }) {
            vm.clearMemories(space); confirmClear = false
        }
    }
}

/** 夜色页上的卡片：半透明的深蓝，下面的星空隐约透出来。 */
private val NIGHT_CARD = androidx.compose.ui.graphics.Color(0xB3182046)

private val dreamDate = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)

@Composable
fun DreamCard(d: DreamEntry, onDelete: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Moon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(6.dp))
                Text(dreamDate.format(Date(d.createdAt)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { deleting = true }) { Text("删除") }
            }
            Text(d.changes.ifBlank { "这次没有改动。" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (deleting) {
        ConfirmDeleteDialog("删除这条记录？", "只删记录，记忆本身不变。", onDismiss = { deleting = false }) {
            onDelete(); deleting = false
        }
    }
}

/** 事实库：Kavi 做任务时查实、存下来的事（状态、出处），不对的可以删。 */
@Composable
internal fun FactsCard(vm: ChatViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    var refresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var showFacts by remember { mutableStateOf(false) }
    val facts by androidx.compose.runtime.produceState(emptyList<com.jack.ai.data.memory.Fact>(), refresh) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.jack.ai.data.memory.Facts.load(ctx).filter { it.status != "retired" }.sortedByDescending { it.updatedAt }
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = NIGHT_CARD, contentColor = colors.onSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("事实库 ${facts.size} 条" + (if (facts.isNotEmpty()) "（已核对 ${facts.count { it.status == "verified" }}）" else ""),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (facts.isNotEmpty()) TextButton(onClick = { showFacts = !showFacts }) { Text(if (showFacts) "收起" else "看看") }
            }
            if (facts.isEmpty()) Text("还是空的。Kavi 做任务时查实的事会存进来，每条都带出处。",
                style = MaterialTheme.typography.bodySmall, color = colors.outline)
            if (showFacts) facts.take(50).forEach { f ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.text, style = MaterialTheme.typography.bodyMedium)
                        val state = when (f.status) { "verified" -> "已核对"; "conflict" -> "有冲突"; else -> "单一来源" } +
                            (if (f.expiresAt in 1 until System.currentTimeMillis()) " · 可能过时" else "")
                        Text(state + " · " + f.sources.map { com.jack.ai.data.memory.Facts.host(it.url) }.distinct().take(3).joinToString("、") +
                            (if (f.topic.isNotBlank()) " · ${f.topic}" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (f.status == "conflict") colors.error else colors.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.deleteFact(f.id); refresh++ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "删除", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 深处的记忆：还成立、但不常用，平时不每次都带着，聊到相关的会自己浮上来。
 * Kavi 做梦时放下去、拿回来；这里也能手动拿回常记或删掉。
 */
@Composable
internal fun DeepMemories(vm: ChatViewModel, hot: List<com.jack.ai.data.MemoryItem>, deepHere: List<com.jack.ai.data.MemoryItem>, filtered: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 按人和事筛的时候直接展开
    var open by remember(filtered) { mutableStateOf(filtered) }
    var gone by remember(hot) { mutableStateOf(emptySet<String>()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val deep = deepHere.filter { it.id !in gone }
    if (deep.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { open = !open }) {
            Text((if (open) "收起" else "深处的记忆") + "（${deep.size} 条）")
        }
        if (open) {
            Text("不常用的放在这里，聊到相关的会自己浮上来，不占每次对话的篇幅。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            deep.sortedByDescending { maxOf(it.lastUsedAt, it.createdAt) }.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(m.text, style = MaterialTheme.typography.bodySmall)
                        aboutLine(m)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)) }
                        if (m.lastUsedAt > 0) Text("上次浮上来 " + com.jack.ai.data.Timeline.day(m.lastUsedAt), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    TextButton(onClick = { vm.promoteMemory(m.id) }) { Text("放回常记") }
                    IconButton(onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.jack.ai.data.memory.MemoryOps.saveDeep(ctx, com.jack.ai.data.memory.MemoryOps.deep(ctx).filterNot { it.id == m.id }); gone = gone + m.id }
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, "删除", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 一个小圆角标签：选中的用主题色。 */
@Composable
internal fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (selected) c.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) c.primary.copy(alpha = 0.5f) else c.outlineVariant),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) c.primary else c.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
    }
}

/**
 * 记忆空间那一排：生活、工作……，最后是「新空间」。选中别的空间时能改名、删掉（里面的记忆并回「生活」）。
 */
@Composable
internal fun SpaceBar(vm: ChatViewModel, settings: com.jack.ai.data.Settings, selected: String, onSelect: (String) -> Unit) {
    var naming by remember { mutableStateOf<String?>(null) }   // null 不显示；"" 新建；其他是改名的 id
    var deleting by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Pill(com.jack.ai.data.DEFAULT_SPACE_NAME, selected == "") { onSelect("") } }
            items(settings.memorySpaces, key = { it.id }) { sp -> Pill(sp.name, selected == sp.id) { onSelect(sp.id) } }
            item { Pill("＋ 新空间", false) { naming = "" } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (settings.memorySpaces.isEmpty()) "可以按场景分开记（生活、工作、生意……）：每个对话选一个空间，只用、只记这个空间的。"
                else "每个对话用一个空间（对话的「记忆」里选），只用、只记这个空间的；别的空间互不打扰。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (selected.isNotEmpty()) {
                TextButton(onClick = { naming = selected }) { Text("改名") }
                TextButton(onClick = { deleting = true }) { Text("删除") }
            }
        }
    }
    naming?.let { id ->
        var name by remember(id) { mutableStateOf(settings.memorySpaces.firstOrNull { it.id == id }?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (id.isEmpty()) "新记忆空间" else "改名") },
            text = { OutlinedTextField(name, { name = it.take(12) }, singleLine = true, placeholder = { Text("例如：工作") }, modifier = Modifier.focusOnShow(),
                keyboardOptions = DoneKeyboard, keyboardActions = doneAction(name.isNotBlank() && name.trim() != com.jack.ai.data.DEFAULT_SPACE_NAME) {
                    if (id.isEmpty()) onSelect(vm.addSpace(name)) else vm.renameSpace(id, name)
                    naming = null
                }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && name.trim() != com.jack.ai.data.DEFAULT_SPACE_NAME, onClick = {
                    if (id.isEmpty()) onSelect(vm.addSpace(name)) else vm.renameSpace(id, name)
                    naming = null
                }) { Text("好") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("取消") } },
        )
    }
    if (deleting) {
        ConfirmDeleteDialog("删除「${settings.spaceName(selected)}」？", "里面的记忆会并回「${com.jack.ai.data.DEFAULT_SPACE_NAME}」，用这个空间的对话也改用「${com.jack.ai.data.DEFAULT_SPACE_NAME}」。",
            onDismiss = { deleting = false }) {
            vm.deleteSpace(selected); onSelect(""); deleting = false
        }
    }
}

/** 按人和事看：这个空间里标得最多的几个（人、地方、项目……），点一个只看关于它的记忆（深处的也列出来）。 */
@Composable
internal fun AboutBar(all: List<MemoryItem>, selected: String?, onSelect: (String?) -> Unit) {
    val top = remember(all) {
        com.jack.ai.data.memory.MemoryGraph.entities(all).entries.sortedByDescending { it.value.size }.take(16)
            .map { (k, v) -> (v.first().about.firstOrNull { com.jack.ai.data.memory.MemoryGraph.norm(it) == k } ?: k) to v.size }
    }
    if (top.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        items(top, key = { it.first }) { (name, n) ->
            Pill("$name $n", selected != null && com.jack.ai.data.memory.MemoryGraph.norm(selected) == com.jack.ai.data.memory.MemoryGraph.norm(name)) {
                onSelect(if (selected != null && com.jack.ai.data.memory.MemoryGraph.norm(selected) == com.jack.ai.data.memory.MemoryGraph.norm(name)) null else name)
            }
        }
    }
}

/** 记忆下面那行小字：涉及谁、什么时候。 */
internal fun aboutLine(m: MemoryItem): String? = listOfNotNull(
    m.about.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "涉及 "),
    m.at.takeIf { it.isNotBlank() },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** 至少有两个标过的人和事才画关系图。 */
internal fun MemoryGraphHas(all: List<MemoryItem>): Boolean = com.jack.ai.data.memory.MemoryGraph.entities(all).size >= 2
