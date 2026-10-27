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
import androidx.compose.ui.unit.sp
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
    // 这一页始终是夜晚：深蓝夜色、星点、月亮色的强调，不跟着浅色 / 深色模式变
    MaterialTheme(colorScheme = NightColors, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(NIGHT_SKY)) {
            Stars(Modifier.fillMaxWidth().height(320.dp))
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

/** 页面上方稀稀落落的星星（位置固定，每次打开一样）。 */
@Composable
private fun Stars(modifier: Modifier) {
    val stars = remember {
        val r = java.util.Random(27)
        List(46) { Triple(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.6f) }
    }
    androidx.compose.foundation.Canvas(modifier) {
        stars.forEach { (x, y, rad) ->
            drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f + y.let { 1 - it } * 0.45f), rad.dp.toPx() / 2 + 0.5f,
                androidx.compose.ui.geometry.Offset(x * size.width, y * y * size.height))
        }
        // 右上角一轮月光
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(androidx.compose.ui.graphics.Color(0x33F2D38A), androidx.compose.ui.graphics.Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(size.width * 0.82f, 70.dp.toPx()), radius = 120.dp.toPx()),
            radius = 120.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.82f, 70.dp.toPx()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryPage(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // 刚删掉的那条：列表上方给个「撤销」，误点了能拿回来
    var undo by remember { mutableStateOf<String?>(null) }
    val settings = state.settings
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
                    Text("记忆与梦", style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(" ✦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("Kavi 每天凌晨回看聊天，写给自己的日记。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = NIGHT_CARD)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("记忆", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(settings.memoryEnabled, { on -> vm.updateSettings { it.copy(memoryEnabled = on) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("做梦（每天 5:30）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(settings.dreamEnabled, { on -> vm.updateSettings { it.copy(dreamEnabled = on) } })
                        }
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

            item { NudgeCard(vm, settings) }
            item { KaviSelfCard() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("关于你", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text("  ${state.memories.size} 条", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "添加记忆") }
                    if (state.memories.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("清空") }
                }
                undo?.let { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已删除「${t.take(16)}${if (t.length > 16) "…" else ""}」", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.addMemory(t); undo = null }) { Text("撤销") }
                    }
                }
                if (state.memories.isEmpty()) {
                    Text("还没有记忆。", color = MaterialTheme.colorScheme.outline)
                }
            }
            items(state.memories, key = { it.id }) { m ->
                // 一条一张半透明的小卡
                androidx.compose.material3.Surface(color = NIGHT_CARD, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(m.text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { editing = m }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Edit, "修改", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { undo = m.text; vm.deleteMemory(m.id) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Delete, "删除", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Text("日记", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp))
                if (state.dreams.isEmpty()) {
                    Text("还没有写过。", color = MaterialTheme.colorScheme.outline)
                }
            }
            items(state.dreams, key = { it.id }) { d ->
                DreamCard(d, initiallyOpen = d == state.dreams.first(), onEdit = { vm.editJournal(d.id, it) }, onDelete = { vm.deleteDream(d.id) })
            }
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

/** 夜色页上的卡片：半透明的深蓝，下面的星空隐约透出来。 */
private val NIGHT_CARD = androidx.compose.ui.graphics.Color(0xB3182046)

private val dreamDate = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
private val dreamDay = SimpleDateFormat("M月d日", Locale.CHINA)

@Composable
fun DreamCard(d: DreamEntry, initiallyOpen: Boolean, onEdit: (String) -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val body = d.journal.ifBlank { d.dream }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Moon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(6.dp))
                Text(
                    if (d.merged > 0) "${dreamDay.format(Date(d.from))} – ${dreamDay.format(Date(d.createdAt))} · ${d.merged} 篇合成"
                    else dreamDate.format(Date(d.createdAt)),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                if (open) {
                    if (d.journal.isNotBlank()) TextButton(onClick = { editing = true }) { Text("编辑") }
                    TextButton(onClick = { deleting = true }) { Text("删除") }
                }
            }
            Text(
                body,
                style = if (d.journal.isNotBlank()) MaterialTheme.typography.bodyMedium.copy(lineHeight = 25.sp)
                    else MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(open) {
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
    if (editing) {
        var text by remember(d.id) { mutableStateOf(d.journal) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("编辑日记") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp)) },
            confirmButton = { TextButton(onClick = { onEdit(text); editing = false }, enabled = text.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
    if (deleting) {
        ConfirmDeleteDialog("删除这篇日记？", "删掉后之后的聊天不会再参考它。", onDismiss = { deleting = false }) {
            onDelete(); deleting = false
        }
    }
}
