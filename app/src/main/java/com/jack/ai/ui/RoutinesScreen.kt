package com.jack.ai.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.CalendarTool
import com.jack.ai.data.REPEAT_LABELS
import com.jack.ai.data.Routine
import com.jack.ai.data.Routines
import com.jack.ai.data.describe
import com.jack.ai.data.nextAt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val MORNING_PROMPT = "给我写一段早安问候：1. 今天的日期和星期；" +
    "2. 如果你记得我在哪个城市，联网查一下今天当地的天气，给出穿衣或带伞的建议；" +
    "3. 结合你对我的了解和我最近在忙的事，说一句真诚的关心或鼓励；" +
    "4. 有值得留意的事就顺带提醒一下。整体 150 字以内，像朋友发的微信。"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Routine?>(null) }
    var deleting by remember { mutableStateOf<Routine?>(null) }
    var canNotify by remember { mutableStateOf(Routines.canNotify(ctx)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    var canCalendar by remember { mutableStateOf(CalendarTool.canWrite(ctx)) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canCalendar = CalendarTool.canWrite(ctx)
    }

    // 按种类筛：全部 / 提醒 / 定时任务 / 监控
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("all") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(
                onClick = { editing = Routine(title = "", hour = 9, minute = 0) },
                shape = androidx.compose.foundation.shape.CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Default.Add, "添加") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("提醒和任务", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            item { KindFilter(filter) { filter = it } }
            if (!canNotify && Build.VERSION.SDK_INT >= 33) {
                item {
                    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没有通知权限", style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知") }
                        }
                    }
                }
            }
            if (!canCalendar) {
                item {
                    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("日历", style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { calendarPermission.launch(CalendarTool.PERMISSIONS) }) { Text("允许读写日历") }
                        }
                    }
                }
            }
            if (state.routines.none { it.title == "早安问候" }) {
                item {
                    OutlinedButton(onClick = {
                        vm.saveRoutine(Routine(kind = "task", title = "早安问候", prompt = MORNING_PROMPT, hour = 7, minute = 30, repeat = "daily"))
                        if (!canNotify && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("☀️ 开启每天 7:30 早安问候") }
                }
            }
            if (state.routines.isEmpty()) {
                item { Text("还没有提醒和任务。", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp)) }
            }
            val shown = state.routines.filter { filter == "all" || it.kind == filter }
            if (state.routines.isNotEmpty() && shown.isEmpty()) {
                item { Text("这一类还没有。", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) }
            }
            items(shown.sortedBy { it.nextAt() ?: Long.MAX_VALUE }, key = { it.id }) { r ->
                // 监控在聊天里让 AI 设，这里只能开关和删除
                RoutineCard(r, onClick = { if (r.kind != "watch") editing = r }, onToggle = { on -> vm.saveRoutine(r.copy(enabled = on)) },
                    onDelete = { deleting = r })
            }
        }
    }

    deleting?.let { r ->
        ConfirmDeleteDialog("删除「${r.title}」？", "删掉后不会再提醒，运行记录也看不到了。", onDismiss = { deleting = null }) {
            vm.deleteRoutine(r.id); deleting = null
        }
    }
    editing?.let { r ->
        RoutineDialog(r, isNew = state.routines.none { it.id == r.id }, onDismiss = { editing = null }) { saved ->
            vm.saveRoutine(saved)
            editing = null
            if (!canNotify && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** 一种提醒的样子：名字、图标、颜色。 */
private fun kindLook(kind: String): Triple<String, androidx.compose.ui.graphics.vector.ImageVector, Long> = when (kind) {
    "reminder" -> Triple("提醒", Icons.Default.Notifications, 0xFFEF5A5A)
    "watch" -> Triple("监控", AppIcons.Radar, 0xFF22A06B)
    else -> Triple("任务", AppIcons.Calendar, 0xFF3B82F6)
}

/** 顶上的分段筛选：全部 / 提醒 / 定时任务 / 监控。 */
@Composable
private fun KindFilter(value: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp)) {
            listOf("all" to "全部", "reminder" to "提醒", "task" to "定时任务", "watch" to "监控").forEach { (k, label) ->
                val on = k == value
                androidx.compose.material3.Surface(
                    onClick = { onChange(k) },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (on) colors.primary else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                        modifier = Modifier.padding(vertical = 9.dp))
                }
            }
        }
    }
}

/** 一条提醒 / 任务 / 监控：左边彩色圆图标和种类，中间时间和标题，右边开关；下面一行是上次跑得怎么样。 */
@Composable
private fun RoutineCard(r: Routine, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (kindName, icon, tintLong) = kindLook(r.kind)
    val tint = androidx.compose.ui.graphics.Color(tintLong)
    var menu by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = colors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(24.dp), tint = tint) }
                    Text(kindName, style = MaterialTheme.typography.labelSmall, color = tint,
                        modifier = Modifier.padding(top = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(tint.copy(alpha = 0.1f)).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(
                        r.describe() + (if (r.then.isNotBlank()) " · 满足后接着做" else "") + (if (r.work) " · Work" else "") + (if (r.offPeak) " · 避开高峰" else ""),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    val ended = if (r.enabled && r.nextAt() == null) "已过期" else if (!r.enabled && r.kind == "watch") "已结束"
                        else if (!r.enabled && r.repeat == "none") "已完成" else null
                    if (ended != null) Text(ended, style = MaterialTheme.typography.labelSmall, color = colors.outline, modifier = Modifier.padding(top = 2.dp))
                }
                Switch(r.enabled, onToggle, modifier = Modifier.padding(top = 4.dp))
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多", tint = colors.outline) }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (r.kind != "watch") androidx.compose.material3.DropdownMenuItem(text = { Text("修改") }, onClick = { menu = false; onClick() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("删除", color = colors.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            RunHistory(r.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RoutineDialog(r: Routine, isNew: Boolean, onDismiss: () -> Unit, onSave: (Routine) -> Unit) {
    val dayFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    var kind by remember { mutableStateOf(r.kind) }
    var title by remember { mutableStateOf(r.title) }
    var prompt by remember { mutableStateOf(r.prompt) }
    var hour by remember { mutableStateOf(r.hour) }
    var minute by remember { mutableStateOf(r.minute) }
    var repeat by remember { mutableStateOf(r.repeat) }
    var date by remember { mutableStateOf(r.date.ifBlank { dayFmt.format(Date()) }) }
    var offPeak by remember { mutableStateOf(r.offPeak) }
    var work by remember { mutableStateOf(r.work) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }

    val draft = r.copy(kind = kind, title = title.trim(), prompt = prompt.trim(), hour = hour, minute = minute,
        repeat = repeat, date = date, enabled = true, offPeak = kind == "task" && offPeak, work = kind == "task" && work)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加" else "修改") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "reminder", { kind = "reminder" }, label = { Text("提醒") })
                    FilterChip(kind == "task", { kind = "task" }, label = { Text("AI 定时任务") })
                }
                OutlinedTextField(title, { title = it }, singleLine = true,
                    label = { Text(if (kind == "reminder") "提醒内容" else "任务名") })
                if (kind == "task") {
                    OutlinedTextField(prompt, { prompt = it }, minLines = 3, maxLines = 6,
                        label = { Text("到时候让 AI 做什么") },
                        placeholder = { Text("比如：搜索过去 24 小时的 AI 新闻，整理成 5 条简报") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("避开高峰时段")
                        }
                        Switch(offPeak, { offPeak = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Work 模式")
                            Text("能用全部技能（邮箱、生成文件…），不限步数", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(work, { work = it })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REPEAT_LABELS.forEach { (k, label) -> FilterChip(repeat == k, { repeat = k }, label = { Text(label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime = true }) { Text(String.format(Locale.US, "%02d:%02d", hour, minute)) }
                    if (repeat == "none" || repeat == "weekly") {
                        OutlinedButton(onClick = { pickDate = true }) { Text(date) }
                    }
                }
                Text(draft.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (kind == "reminder" || prompt.isNotBlank()) && draft.nextAt() != null,
                onClick = { onSave(draft) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (pickTime) {
        val ts = rememberTimePickerState(hour, minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { hour = ts.hour; minute = ts.minute; pickTime = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("取消") } },
            text = { TimePicker(ts) },
        )
    }
    if (pickDate) {
        val utc = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
        val ds = rememberDatePickerState(initialSelectedDateMillis = runCatching { utc.parse(date)?.time }.getOrNull())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    ds.selectedDateMillis?.let { date = utc.format(Date(it)) }
                    pickDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("取消") } },
        ) { DatePicker(ds) }
    }
    Spacer(Modifier.size(0.dp))
}

/** 一条提醒或任务的运行记录：最近一次的结果一行；点开看最近几次，每次调了哪些工具、留下什么、为什么失败。 */
@Composable
private fun RunHistory(routineId: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val runs by androidx.compose.runtime.produceState(emptyList<com.jack.ai.data.RunRecord>(), routineId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jack.ai.data.RunLog.of(ctx, routineId) }
    }
    if (runs.isEmpty()) return
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val fmt = remember { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA) }
    fun mark(r: com.jack.ai.data.RunRecord) = when {
        r.interrupted() || r.status == "failed" || r.status == "needs_setup" -> "✗"
        r.status == "running" -> "…"
        r.status == "not_met" -> "○"
        r.status == "unknown" || r.status == "partial" -> "△"
        else -> "✓"
    }
    val last = runs.first()
    // ✓ 绿、○ 橙、✗ △ 红，和左边的图标对齐
    fun tone(m: String) = when (m) {
        "✓" -> androidx.compose.ui.graphics.Color(0xFF22A06B)
        "○" -> androidx.compose.ui.graphics.Color(0xFFE08600)
        "✗", "△" -> colors.error
        else -> colors.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(start = 84.dp, end = 16.dp, bottom = 14.dp)) {
        Text(
            "上次 ${fmt.format(java.util.Date(last.startedAt))} ${mark(last)} ${last.statusText()}" + if (runs.size > 1 || last.steps.isNotEmpty() || last.error.isNotBlank()) (if (open) "  收起" else "  查看记录") else "",
            style = MaterialTheme.typography.labelMedium,
            color = tone(mark(last)),
            modifier = Modifier.clickable { open = !open },
        )
        if (open) runs.take(8).forEach { r ->
            Column(Modifier.padding(top = 6.dp)) {
                Text("${mark(r)} ${fmt.format(java.util.Date(r.startedAt))} · ${r.statusText()}", style = MaterialTheme.typography.labelMedium)
                r.steps.forEach { st ->
                    Text("    ${if (st.ok) "·" else if (st.rejected) "⊘" else "✗"} ${st.tool}" + if (st.note.isNotBlank()) "：${st.note}" else "",
                        style = MaterialTheme.typography.labelSmall, color = if (st.ok) colors.onSurfaceVariant else colors.error)
                }
                if (r.error.isNotBlank()) Text("    原因：${r.error}", style = MaterialTheme.typography.labelSmall, color = colors.error)
                else if (r.note.isNotBlank()) Text("    ${r.note}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
