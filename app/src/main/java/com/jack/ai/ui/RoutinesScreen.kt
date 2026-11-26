package com.jack.ai.ui

import kotlinx.coroutines.launch

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
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
fun RoutinesScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit, onOpenWorkflows: () -> Unit = {},
                   /** 打开任务、监控结果所在的那个对话。 */
                   onOpenChat: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Routine?>(null) }
    // 删了不再先确认：直接删，底下出「撤销」（连同上次跑的记录一起拿回来）
    val undoBar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun deleteNow(r: Routine) {
        vm.deleteRoutine(r.id)
        scope.launch {
            undoBar.currentSnackbarData?.dismiss()
            val res = undoBar.showSnackbar("删除了「${r.title.take(12)}」", actionLabel = "撤销", duration = androidx.compose.material3.SnackbarDuration.Short)
            if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.saveRoutine(r)
        }
    }
    var canNotify by remember { mutableStateOf(Routines.canNotify(ctx)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    var canCalendar by remember { mutableStateOf(CalendarTool.canWrite(ctx)) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canCalendar = CalendarTool.canWrite(ctx)
    }

    // 按种类筛：全部 / 提醒 / 定时任务 / 监控
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("all") }
    Scaffold(
        snackbarHost = { KaviSnackbarHost(undoBar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(
                // 筛着「定时任务」时点加号，新建的就是定时任务，不用再在框里切一次
                onClick = { editing = newRoutineDraft().let { d -> if (filter == "task") d.copy(kind = "task") else d } },
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
            item { WorkflowsEntry(onOpenWorkflows) }
            if (!canNotify && Build.VERSION.SDK_INT >= 33) {
                item { PermissionRow("还没有通知权限", "提醒和任务结果要靠通知送到", "允许") { permission.launch(Manifest.permission.POST_NOTIFICATIONS) } }
            }
            if (!canCalendar) {
                item { PermissionRow("日历", "允许后 Kavi 能查和加日程", "允许") { calendarPermission.launch(CalendarTool.PERMISSIONS) } }
            }
            item { QuietHoursCard(state.settings) { start, end -> vm.updateSettings { it.copy(quietStart = start, quietEnd = end) } } }
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
                // 跑过的任务、监控能直接打开结果所在的对话；监控点卡片就是看结果（以前点了没反应）
                val chat = r.convId.takeIf { r.kind != "reminder" && state.conversations.any { c -> c.id == r.convId && c.messages.isNotEmpty() } }
                RoutineCard(r, onClick = { if (r.kind != "watch") editing = r else chat?.let(onOpenChat) }, onToggle = { on -> vm.saveRoutine(r.copy(enabled = on)) },
                    onDelete = { deleteNow(r) },
                    onOpenChat = chat?.let { id -> { onOpenChat(id) } },
                    // 复制一份：新的 id 和对话，先打开编辑框改时间、改内容再存
                    onCopy = { editing = r.copy(id = com.jack.ai.data.newId(), convId = com.jack.ai.data.newId(), title = r.title + "（副本）", lastRunAt = 0, enabled = true) })
            }
        }
    }

    editing?.let { r ->
        RoutineDialog(r, isNew = state.routines.none { it.id == r.id }, onDismiss = { editing = null }, onUpgrade = {
            // 照它的时间和要做的事搭一个工作流，原来的定时任务关掉（不删，想回去还能开）
            val w = com.jack.ai.data.workflow.Workflows.save(ctx, com.jack.ai.data.workflow.WorkflowUpgrade.from(r))
            vm.saveRoutine(r.copy(enabled = false))
            editing = null
            android.widget.Toast.makeText(ctx, "升级成工作流「${w.name}」了，原来的定时任务已关掉。可以在工作流里接着加步骤", android.widget.Toast.LENGTH_LONG).show()
            onOpenWorkflows()
        }) { saved ->
            vm.saveRoutine(saved)
            editing = null
            if (!canNotify && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** 免打扰时段：开着时这段时间里定时任务、监控的结果静音送达，提醒照常响。 */
@Composable
private fun QuietHoursCard(settings: com.jack.ai.data.Settings, onChange: (Int, Int) -> Unit) {
    val on = settings.quietStart in 0..23
    val start = if (on) settings.quietStart else 22
    val end = settings.quietEnd.coerceIn(0, 23)
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("免打扰时段", style = MaterialTheme.typography.titleSmall)
                Text(if (on) "${start}:00 到 ${end}:00，定时任务和监控的结果静音送达；你自己定的提醒照常响" else "关着：定时任务的结果随时响铃提醒",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(on, { v -> onChange(if (v) start else -1, end) })
        }
        if (on) androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            HourPicker("从", start) { onChange(it, end) }
            HourPicker("到", end) { onChange(start, it) }
        }
    }
}

@Composable
private fun HourPicker(label: String, hour: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { open = true }) { Text("$label ${hour}:00") }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { h -> androidx.compose.material3.DropdownMenuItem(text = { Text("${h}:00") }, onClick = { open = false; onPick(h) }) }
        }
    }
}

/** 提醒一次时的快捷时间：(说法, 时刻毫秒)。整分钟；「今晚 8 点」过了 19 点就不给了。 */
internal fun quickTimes(now: Long = System.currentTimeMillis()): List<Pair<String, Long>> {
    fun cal() = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    fun later(min: Int) = cal().apply { add(java.util.Calendar.MINUTE, min) }.timeInMillis
    fun at(dayOffset: Int, h: Int) = cal().apply { add(java.util.Calendar.DAY_OF_YEAR, dayOffset); set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
    val hourNow = cal().get(java.util.Calendar.HOUR_OF_DAY)
    return listOfNotNull(
        "10 分钟后" to later(10),
        "1 小时后" to later(60),
        ("今晚 8 点" to at(0, 20)).takeIf { hourNow < 19 },
        "明早 9 点" to at(1, 9),
    )
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
private fun RoutineCard(r: Routine, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onCopy: () -> Unit,
                        onOpenChat: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val (kindName, icon, tintLong) = kindLook(r.kind)
    val tint = androidx.compose.ui.graphics.Color(tintLong)
    var menu by remember { mutableStateOf(false) }
    // 关掉的那张整体淡一点、退一点，一眼看出哪些不在跑；开关拨回去又亮起来
    val live by androidx.compose.animation.core.animateFloatAsState(if (r.enabled) 1f else 0f, Springs.defaultSpatial(), label = "live")
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = Radii.l,
        color = colors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        interactionSource = press,
        modifier = Modifier.fillMaxWidth().pressScale(press, 0.98f).graphicsLayer {
            alpha = 0.55f + 0.45f * live
            val s = 0.98f + 0.02f * live
            scaleX = s; scaleY = s
        },
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(24.dp), tint = tint) }
                    Text(kindName, style = MaterialTheme.typography.labelSmall, color = tint,
                        modifier = Modifier.padding(top = 6.dp).clip(Radii.xs)
                            .background(tint.copy(alpha = 0.1f)).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(
                        tidyBreaks(r.describe() + (if (r.then.isNotBlank()) " · 满足后接着做" else "") + (if (r.work) " · 做事模式" else "") + (if (r.offPeak) " · 避开高峰" else "")),
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
                        onOpenChat?.let { open -> androidx.compose.material3.DropdownMenuItem(text = { Text("看结果") }, onClick = { menu = false; open() }) }
                        if (r.kind != "watch") androidx.compose.material3.DropdownMenuItem(text = { Text("修改") }, onClick = { menu = false; onClick() })
                        if (r.kind != "watch") androidx.compose.material3.DropdownMenuItem(text = { Text("复制一份") }, onClick = { menu = false; onCopy() })
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
private fun RoutineDialog(r: Routine, isNew: Boolean, onDismiss: () -> Unit, onUpgrade: (() -> Unit)? = null, onSave: (Routine) -> Unit) {
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
        properties = keepIfEdited(title != r.title || prompt != r.prompt),
        title = { Text(if (isNew) "添加" else "修改") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "reminder", { kind = "reminder" }, label = { Text("提醒") })
                    FilterChip(kind == "task", { kind = "task" }, label = { Text("AI 定时任务") })
                }
                OutlinedTextField(title, { title = it }, singleLine = true,
                    modifier = if (isNew) Modifier.focusOnShow() else Modifier,
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
                            Text("做事模式")
                            Text("能用全部技能（邮箱、生成文件…），不限步数", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(work, { work = it })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REPEAT_LABELS.forEach { (k, label) -> FilterChip(repeat == k, { repeat = k }, label = { Text(label) }) }
                }
                // 只提醒一次：常用的几个时间点一下就好，不用开时钟和日历去拨
                if (repeat == "none") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    remember { quickTimes() }.forEach { (label, at) ->
                        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
                        val picked = hour == c.get(java.util.Calendar.HOUR_OF_DAY) && minute == c.get(java.util.Calendar.MINUTE) && date == dayFmt.format(Date(at))
                        FilterChip(picked, {
                            hour = c.get(java.util.Calendar.HOUR_OF_DAY); minute = c.get(java.util.Calendar.MINUTE); date = dayFmt.format(Date(at))
                        }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime = true }) { Text(String.format(Locale.US, "%02d:%02d", hour, minute)) }
                    if (repeat == "none" || repeat == "weekly") {
                        OutlinedButton(onClick = { pickDate = true }) { Text(date) }
                    }
                }
                // 「保存」灰着时说清楚为什么：多半是只提醒一次、时间已经过了
                if (draft.nextAt() == null) Text("这个时间已经过了，改一下时间或日期", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                else Text(draft.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (kind == "reminder" || prompt.isNotBlank()) && draft.nextAt() != null,
                onClick = { onSave(draft) },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew && onUpgrade != null) TextButton(onClick = onUpgrade) { Text("升级成工作流") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
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
    val green = Hue.GREEN.color
    val amber = Hue.AMBER.color
    fun tone(m: String) = when (m) {
        "✓" -> green
        "○" -> amber
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

/** 进工作流页的那一条：几个、几个在定时跑。 */
@Composable
private fun WorkflowsEntry(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val flow = remember { com.jack.ai.data.workflow.Workflows.flow(ctx) }
    val list by flow.collectAsState()
    val all = list.orEmpty()
    Row(Modifier.fillMaxWidth().clip(Radii.l).background(Hue.SKY.wash).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("↻", color = Hue.SKY.color, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("工作流", style = MaterialTheme.typography.titleSmall)
            Text(if (all.isEmpty()) "排好的几步，到点或点一下就自己跑完" else "${all.size} 个 · ${all.count { it.enabled && it.trigger.type == "schedule" }} 个在定时跑",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 缺权限时的一行：左边说缺什么、有什么用，右边一个按钮。比一整张卡片省地方。 */
@Composable
private fun PermissionRow(title: String, hint: String, action: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(shape = Radii.l,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

/** 新建提醒的默认时间：下一个整点（23 点以后就是明天 0 点），不再固定早上 9 点——过了 9 点新建，「保存」一直是灰的。 */
internal fun newRoutineDraft(now: Long = System.currentTimeMillis()): Routine {
    val c = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        add(java.util.Calendar.HOUR_OF_DAY, 1)
        set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    return Routine(title = "", hour = c.get(java.util.Calendar.HOUR_OF_DAY), minute = 0,
        date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time))
}
