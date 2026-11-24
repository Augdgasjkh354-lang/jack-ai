package com.jack.ai.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.data.workflow.WfRun
import com.jack.ai.data.workflow.WfStep
import com.jack.ai.data.workflow.WfStepLog
import com.jack.ai.data.workflow.WfTrigger
import com.jack.ai.data.workflow.Workflow
import com.jack.ai.data.workflow.WorkflowFormat
import com.jack.ai.data.workflow.Workflows
import com.jack.ai.data.workflow.condText
import com.jack.ai.data.workflow.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 工作流页：列表（什么时候跑、几步、上次怎么样、跑一下、开关）、详情（步骤树、运行记录）。
 * 编辑卡片是下一步（第 5 步）；现在新建、修改在聊天里让 Kavi 做。
 */

/** 每种步骤的图标字和颜色。 */
internal fun stepLook(type: String): Pair<String, Hue> = when (type) {
    "tool" -> "🔧" to Hue.BLUE
    "ai" -> "✦" to Hue.VIOLET
    "notify" -> "🔔" to Hue.AMBER
    "message" -> "💬" to Hue.GREEN
    "file" -> "📄" to Hue.GRAY
    "code" -> "⌨" to Hue.GRAY
    "set" -> "＝" to Hue.GRAY
    "extract" -> "✂" to Hue.SKY
    "if" -> "?" to Hue.ORANGE
    "forEach" -> "↻" to Hue.SKY
    "wait" -> "⏳" to Hue.GRAY
    "stop" -> "■" to Hue.RED
    else -> "·" to Hue.GRAY
}

/** 「什么时候跑」的图标字和颜色。 */
internal fun triggerLook(t: WfTrigger): Pair<String, Hue> = when {
    t.type == "notify" -> "🔔" to Hue.AMBER
    t.type == "share" -> "↗" to Hue.GREEN
    t.type == "schedule" && t.repeat == "watch" -> "◎" to Hue.ORANGE
    t.type == "schedule" -> "⏰" to Hue.SKY
    else -> "▶" to Hue.VIOLET
}

@Composable
internal fun Glyph(text: String, hue: Hue, size: Int = 34) {
    Box(Modifier.size(size.dp).clip(Radii.s).background(hue.wash), contentAlignment = Alignment.Center) {
        Text(text, color = hue.color, style = if (size >= 40) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatusChip(r: WfRun) {
    val (t, h) = when (r.status) {
        "ok" -> "跑完了" to Hue.GREEN
        "stopped" -> "停下了" to Hue.AMBER
        "limit" -> "碰到上限" to Hue.ORANGE
        "failed" -> "出错" to Hue.RED
        else -> "在跑" to Hue.BLUE
    }
    Text(t, style = MaterialTheme.typography.labelSmall, color = h.color, modifier = Modifier.clip(Radii.xs).background(h.wash).padding(horizontal = 8.dp, vertical = 2.dp))
}

private fun whenText(t: Long, now: Long = System.currentTimeMillis()): String {
    val f = java.text.SimpleDateFormat(if (now - t < 86_400_000L * 300) "M月d日 HH:mm" else "yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    return f.format(java.util.Date(t))
}

private fun nextText(w: Workflow): String? = WorkflowFormat.nextAt(w, System.currentTimeMillis())?.let { "下次 " + whenText(it) }

/** 示例：每天一句（手动跑；一步问模型，一步发通知），装上就能试。 */
private fun sample() = Workflow(
    name = "每日一句", description = "让 Kavi 给今天写一句提神的话，发成通知。装上点「跑一下」试试，也可以在聊天里让 Kavi 改成每天早上自动发。",
    trigger = WfTrigger("manual"),
    steps = listOf(
        WfStep(id = "s1", type = "ai", name = "一句", prompt = "今天是 {{今天}}。用一句话（不超过 30 个字）给我写一句提神的话，不要引号，不要解释。"),
        WfStep(id = "s2", type = "notify", title = "☀ {{今天}}", body = "{{一句}}"),
    ),
    limits = com.jack.ai.data.workflow.WfLimits(timeoutMin = 3, maxCostYuan = 0.1), by = "sample",
)

@Composable
fun WorkflowsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val list by remember { Workflows.flow(ctx) }.collectAsState()
    val running by Workflows.running.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    // 从通知点进来：展开那一个
    val asked by Workflows.openRequest.collectAsState()
    LaunchedEffect(asked) { asked?.let { open = it; Workflows.openRequest.value = null } }
    // 编辑器：null 没开；Workflow(name = "") 当新建
    var editing by remember { mutableStateOf<Workflow?>(null) }
    var creating by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { creating = true }, shape = CircleShape) { Text("＋ 新建") }
                }
            }
            item {
                Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    Text("工作流", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("排好的几步，到点或点一下就自己跑完，不用每步问模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val all = list.orEmpty().sortedByDescending { it.updatedAt }
            if (list != null && all.isEmpty()) item { WorkflowsEmpty { Workflows.save(ctx, sample()); Toast.makeText(ctx, "装好了，点「跑一下」试试", Toast.LENGTH_SHORT).show() } }
            items(all, key = { it.id }) { w ->
                WorkflowCard(w, w.id in running, onOpen = { open = w.id },
                    onRun = { Workflows.enqueue(ctx, w.id, "manual"); Toast.makeText(ctx, "在后台跑了，跑完结果在收件箱或通知里", Toast.LENGTH_SHORT).show() },
                    onToggle = { on -> Workflows.update(ctx, w.id) { it.copy(enabled = on) } })
            }
            if (all.isNotEmpty()) item {
                Text("也可以在聊天里跟 Kavi 说，比如「每个工作日 8 点查一下上海天气，要下雨就提醒我带伞」，它搭好给你看过再存。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(8.dp))
            }
        }
    }
    open?.let { id -> list.orEmpty().firstOrNull { it.id == id }?.let { w -> WorkflowDetail(w, w.id in running, onEdit = { editing = w }) { open = null } } }
    if (creating) WorkflowEditor(null) { creating = false }
    editing?.let { w -> WorkflowEditor(w) { editing = null } }
}

@Composable
private fun WorkflowsEmpty(onSample: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Radii.l).background(Hue.SKY.wash).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("↻", color = Hue.SKY.color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("还没有工作流", style = MaterialTheme.typography.titleMedium)
        }
        Text("工作流是排好的几步：调工具、让 Kavi 写点东西、如果……就……、对每一项……、发通知。到点自动跑，或者点一下就跑。\n" +
            "点右上角「＋ 新建」，一句话说要做什么，Kavi 会搭好卡片给你改；也可以在聊天里说。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSample, shape = CircleShape, modifier = Modifier.padding(top = 4.dp)) { Text("装一个示例：每日一句") }
    }
}

@Composable
private fun WorkflowCard(w: Workflow, running: Boolean, onOpen: () -> Unit, onRun: () -> Unit, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val last by produceState<WfRun?>(null, w.id, running) { value = withContext(Dispatchers.IO) { Workflows.runs(ctx, w.id).firstOrNull() } }
    val scheduled = w.trigger.type == "schedule"
    androidx.compose.material3.Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = Radii.l) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                triggerLook(w.trigger).let { (g, h) -> Glyph(g, h, 42) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(WorkflowFormat.describe(w.trigger), "${WorkflowFormat.count(w.steps)} 步", if (w.enabled) nextText(w) else "关着").joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall.tabular, color = colors.onSurfaceVariant, maxLines = 1)
                }
                if (w.trigger.type != "manual") Switch(w.enabled, onToggle)
            }
            if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在跑…", style = MaterialTheme.typography.labelSmall, color = colors.primary) }
                    last != null -> { StatusChip(last!!); Spacer(Modifier.width(8.dp)); Text(whenText(last!!.startedAt), style = MaterialTheme.typography.labelSmall.tabular, color = colors.outline) }
                    else -> Text("还没跑过", style = MaterialTheme.typography.labelSmall, color = colors.outline)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onRun, enabled = !running) { Text("跑一下") }
            }
        }
    }
}

// ---------- 详情 ----------

@Composable
private fun WorkflowDetail(w: Workflow, running: Boolean, onEdit: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val runs by produceState(emptyList<WfRun>(), w.id, running) { value = withContext(Dispatchers.IO) { Workflows.runs(ctx, w.id) } }
    var deleting by remember { mutableStateOf(false) }
    var openRun by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
                    Spacer(Modifier.width(12.dp))
                    Text("工作流", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onEdit) { Text("编辑") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    WorkflowHero(w)
                    Section("步骤") { StepTree(w.steps) }
                    GrantsSection(w)
                    Section("运行记录", if (runs.isEmpty()) "还没跑过" else "最近 ${runs.size} 次，点开看每一步拿到了什么") {
                        runs.forEach { r ->
                            RunRow(r, open = openRun == r.id) { openRun = if (openRun == r.id) null else r.id }
                        }
                    }
                    TextButton(onClick = { deleting = true }) { Text("删除这个工作流", color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(onClick = { Workflows.enqueue(ctx, w.id, "manual") }, enabled = !running, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        if (running) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)); Text("正在跑…") }
                        else Text("跑一下")
                    }
                }
            }
        }
    }
    if (deleting) ConfirmDeleteDialog("删除「${w.name}」？", "运行记录也会一起删掉。", onDismiss = { deleting = false }) {
        deleting = false; Workflows.delete(ctx, w.id); onBack()
    }
}

@Composable
private fun WorkflowHero(w: Workflow) {
    val colors = MaterialTheme.colorScheme
    val scheduled = w.trigger.type == "schedule"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        triggerLook(w.trigger).let { (g, h) -> Glyph(g, h, 64) }
        Spacer(Modifier.height(10.dp))
        Text(w.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(WorkflowFormat.describe(w.trigger), nextText(w)?.takeIf { w.enabled }, if (w.by == "kavi") "Kavi 搭的" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.tabular, color = colors.onSurfaceVariant)
        if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("每次最多 ${w.limits.timeoutMin} 分钟、花 ¥${w.limits.maxCostYuan} · 结果送到" + when (w.deliver) { "notify" -> "通知"; "none" -> "不送"; else -> "收件箱" },
            style = MaterialTheme.typography.labelSmall, color = colors.outline, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).padding(vertical = 8.dp, horizontal = 12.dp)) { content() }
    }
}

/** 步骤树：「如果」「对每一项」里面的缩进，左边一道和它同色的竖线。 */
@Composable
internal fun StepTree(steps: List<WfStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.forEach { s ->
            val (g, hue) = stepLook(s.type)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Glyph(g, hue)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (s.type in setOf("if", "forEach")) label(s) else s.name.ifBlank { label(s) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(vars(detail(s), Hue.VIOLET.color, Hue.VIOLET.wash), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            when (s.type) {
                "if" -> {
                    if (s.then.isNotEmpty()) Branch("是", Hue.GREEN) { StepTree(s.then) }
                    if (s.otherwise.isNotEmpty()) Branch("否", Hue.GRAY) { StepTree(s.otherwise) }
                }
                "forEach" -> Branch(null, Hue.SKY) { StepTree(s.steps) }
            }
        }
    }
}

@Composable
internal fun Branch(tag: String?, hue: Hue, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 16.dp)) {
        Box(Modifier.width(2.dp).fillMaxHeight().clip(CircleShape).background(hue.color.copy(alpha = 0.45f)))
        Column(Modifier.padding(start = 12.dp)) {
            if (tag != null) Text(tag, style = MaterialTheme.typography.labelSmall, color = hue.color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            content()
        }
    }
}

/** {{变量}} 换个颜色、去掉花括号，一眼看出哪些是跑的时候才填进来的。 */
internal fun vars(text: String, ink: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) = androidx.compose.ui.text.buildAnnotatedString {
    var i = 0
    Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}").findAll(text).forEach { m ->
        append(text.substring(i, m.range.first))
        withStyle(androidx.compose.ui.text.SpanStyle(color = ink, background = bg, fontWeight = FontWeight.Medium)) { append(" ${m.groupValues[1]} ") }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

internal fun detail(s: WfStep): String = when (s.type) {
    "tool" -> s.tool + (if (s.args.isNotEmpty()) "  " + s.args.entries.joinToString("，") { "${it.key} = ${com.jack.ai.data.workflow.WfTemplate.text(it.value)}" } else "")
    "ai" -> s.prompt + if (s.json) "（按 JSON 回）" else ""
    "notify" -> listOf(s.title, s.body).filter { it.isNotBlank() }.joinToString(" · ")
    "message" -> s.text
    "file" -> s.file
    "code" -> s.code.lines().firstOrNull().orEmpty()
    "set" -> com.jack.ai.data.workflow.WfTemplate.text(s.value)
    "extract" -> "从 ${s.from} 取 " + s.path.ifBlank { "正则 " + s.regex }
    "if" -> condText(s.cond)
    "forEach" -> "${s.list} 里的每一项，叫「${s.varName}」"
    "wait" -> "${s.seconds} 秒"
    "stop" -> s.reason
    else -> ""
} + (when (s.onError) { "skip" -> "  · 出错跳过"; "retry" -> "  · 出错重试 ${s.retries} 次"; else -> "" })

@Composable
private fun RunRow(r: WfRun, open: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(Radii.m).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(r)
            Spacer(Modifier.width(8.dp))
            Text(whenText(r.startedAt) + " · " + Workflows.triggerLabel(r.trigger), style = MaterialTheme.typography.bodySmall.tabular, modifier = Modifier.weight(1f))
            Text("${((r.endedAt - r.startedAt) / 1000.0).let { String.format(java.util.Locale.US, "%.1f", it) }} 秒" +
                if (r.costYuan > 0) " · ¥" + String.format(java.util.Locale.US, "%.3f", r.costYuan) else "",
                style = MaterialTheme.typography.labelSmall.tabular, color = colors.outline)
        }
        if (r.error.isNotBlank()) Text(r.error, style = MaterialTheme.typography.bodySmall, color = if (r.status == "stopped") colors.onSurfaceVariant else colors.error,
            maxLines = if (open) 8 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.steps.forEach { StepLogRow(it) }
                if (r.output.isNotBlank()) Text("结果：" + r.output.take(1500), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun StepLogRow(l: WfStepLog) {
    val colors = MaterialTheme.colorScheme
    val (g, hue) = when { !l.ok && l.skipped -> "↷" to Hue.AMBER; !l.ok -> "✗" to Hue.RED; else -> stepLook(l.type) }
    Row(verticalAlignment = Alignment.Top) {
        Glyph(g, hue, 26)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(l.name + if (l.at.isNotEmpty()) "  · ${l.at}" else "", style = MaterialTheme.typography.labelLarge)
            val v = if (l.ok) l.output else l.error
            if (v.isNotBlank()) Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (v.trimStart().startsWith("{") || v.trimStart().startsWith("[")) CodeFont else null),
                color = if (l.ok) colors.onSurfaceVariant else colors.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Text("${l.ms} ms", style = MaterialTheme.typography.labelSmall.tabular, color = colors.outline)
    }
}

/** 分享菜单「交给 Kavi 工作流」进来：挑一个「分享时跑」的工作流，分享的文字当 {{输入}}，放到后台跑。 */
@Composable
fun WorkflowSharePicker(text: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val targets = remember { Workflows.shareTargets(ctx) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text("交给哪个工作流？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clip(Radii.s).background(raisedColor).padding(10.dp))
                if (targets.isEmpty()) Text("没有「分享时跑」的工作流了。", style = MaterialTheme.typography.bodyMedium)
                targets.forEach { w ->
                    Row(Modifier.fillMaxWidth().clip(Radii.m).clickable {
                        Workflows.enqueue(ctx, w.id, "share", text); Toast.makeText(ctx, "交给「${w.name}」了，跑完结果在收件箱或通知里", Toast.LENGTH_SHORT).show(); onDone()
                    }.padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("↗", Hue.GREEN, 34)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("取消") } },
    )
}

/** 详情页「后台授权」：保存时同意过的往外做的事，能一条条撤销。 */
@Composable
private fun GrantsSection(w: Workflow) {
    val ctx = LocalContext.current
    val needs = remember(w) { com.jack.ai.data.workflow.WfGrants.needed(w) }
    if (needs.isEmpty()) return
    val missing = remember(w) { com.jack.ai.data.workflow.WfGrants.missing(w) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(start = 4.dp)) {
            Text("后台授权", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("后台跑时，这些往外做的事照这里的授权做，不再问你", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).padding(vertical = 6.dp, horizontal = 12.dp)) {
            needs.forEach { n ->
                val ok = missing.none { it.stepId == n.stepId }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (ok) "✓" else "!", if (ok) Hue.GREEN else Hue.ORANGE, 30)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.label, style = MaterialTheme.typography.bodyMedium)
                        Text(if (ok) "已授权" else "没授权：后台跑到这一步会停下", style = MaterialTheme.typography.labelSmall,
                            color = if (ok) MaterialTheme.colorScheme.outline else Hue.ORANGE.color)
                    }
                    if (ok) TextButton(onClick = {
                        Workflows.update(ctx, w.id) { it.copy(grants = it.grants.filterNot { g -> com.jack.ai.data.workflow.WfGrants.covers(g, n) }) }
                    }) { Text("撤销") }
                    else TextButton(onClick = {
                        Workflows.update(ctx, w.id) { com.jack.ai.data.workflow.WfGrants.apply(it, approved = true) }
                    }) { Text("授权") }
                }
            }
        }
    }
}
