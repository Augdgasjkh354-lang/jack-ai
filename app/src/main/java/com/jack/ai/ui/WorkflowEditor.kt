package com.jack.ai.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.data.workflow.ToolInfo
import com.jack.ai.data.workflow.WfCond
import com.jack.ai.data.workflow.WfEdit
import com.jack.ai.data.workflow.WfLimits
import com.jack.ai.data.workflow.WfRun
import com.jack.ai.data.workflow.WfStep
import com.jack.ai.data.workflow.WfStepLog
import com.jack.ai.data.workflow.WfTemplate
import com.jack.ai.data.workflow.WfTrigger
import com.jack.ai.data.workflow.Workflow
import com.jack.ai.data.workflow.WorkflowBuilder
import com.jack.ai.data.workflow.WorkflowError
import com.jack.ai.data.workflow.WorkflowFormat
import com.jack.ai.data.workflow.Workflows
import com.jack.ai.data.workflow.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 工作流的卡片编辑器：一步一张卡片从上往下排，「如果」「对每一项」里面的缩进；点卡片展开填参数，
 * 卡片之间点「＋」加一步，⋯ 里上移、下移、复制、删除；输入框旁边的 {x} 从前面的步骤里选变量。
 * 试跑时每张卡片下面显示这一步实际拿到了什么，错的标红。也能一句话让 Kavi 搭好再改。
 */

/** 能加的步骤：类型、名字、一句话。 */
private val STEP_TYPES = listOf(
    Triple("tool", "调用工具", "Kavi 的工具、工具包里的工具"),
    Triple("ai", "让 Kavi 做", "写一段话、总结、判断，可以按 JSON 回"),
    Triple("if", "如果……否则", "满足条件才做"),
    Triple("forEach", "对每一项", "对一个列表里的每一项都做一遍"),
    Triple("notify", "发通知", "在手机上弹一条通知"),
    Triple("message", "发到收件箱", "结果放进收件箱，打开 App 能看到"),
    Triple("file", "存成文件", "存一个文字文件"),
    Triple("extract", "取出", "从 JSON 按路径取，或用正则取"),
    Triple("code", "跑代码", "一段 JavaScript，只能算东西"),
    Triple("set", "存一个值", "固定的文字或数字，后面用"),
    Triple("wait", "等一会", "等几秒再接着做"),
    Triple("stop", "停下", "提前结束，带一句原因"),
)

private val OPS = listOf("==" to "等于", "!=" to "不等于", ">" to "大于", ">=" to "大于等于", "<" to "小于", "<=" to "小于等于",
    "contains" to "包含", "notContains" to "不包含", "empty" to "为空", "notEmpty" to "不为空", "matches" to "匹配正则")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditor(initial: Workflow?, preview: Map<String, List<WfStepLog>> = emptyMap(), open: String? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val start = remember { initial ?: Workflow(name = "", steps = emptyList()) }
    var draft by remember { mutableStateOf(start) }
    var expanded by remember { mutableStateOf(open) }
    var adding by remember { mutableStateOf<WfEdit.Slot?>(null) }
    var problems by remember { mutableStateOf<List<String>?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val logs = remember { mutableStateMapOf<String, List<WfStepLog>>().apply { putAll(preview) } }
    var trial by remember { mutableStateOf<WfRun?>(null) }
    var trying by remember { mutableStateOf(false) }
    var building by remember { mutableStateOf(false) }
    val tools = remember { runCatching { WorkflowBuilder.tools(com.jack.ai.data.Store(ctx).loadSettings()) }.getOrDefault(emptyList()) }
    val dirty = draft != start
    fun back() { if (dirty) leaving = true else onClose() }
    fun edit(f: (List<WfStep>) -> List<WfStep>) { draft = draft.copy(steps = f(draft.steps)) }

    fun tryRun() {
        val p = WorkflowFormat.check(draft.copy(name = draft.name.ifBlank { "试跑" }))
        if (p.isNotEmpty()) { problems = p; return }
        trying = true; logs.clear(); trial = null
        scope.launch {
            val r = Workflows.run(ctx, draft.copy(name = draft.name.ifBlank { "试跑" }), "test", onStep = { l -> logs[l.stepId] = (logs[l.stepId] ?: emptyList()) + l })
            trial = r; trying = false
        }
    }

    var granting by remember { mutableStateOf<List<com.jack.ai.data.workflow.WfGrants.Need>?>(null) }

    fun commit(w: Workflow, approved: Boolean) {
        Workflows.save(ctx, com.jack.ai.data.workflow.WfGrants.apply(w, approved))
        Toast.makeText(ctx, "存好了", Toast.LENGTH_SHORT).show()
        onClose()
    }

    fun save() {
        val w = draft.copy(name = draft.name.trim())
        val p = WorkflowFormat.check(w)
        if (p.isNotEmpty()) { problems = p; return }
        // 有新的、或者改过的「往外做的事」：先给用户看，同意了才在后台放行
        if (com.jack.ai.data.workflow.WfGrants.missing(w).isNotEmpty()) { granting = com.jack.ai.data.workflow.WfGrants.needed(w); return }
        commit(w, approved = false)
    }

    val page: @Composable () -> Unit = {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = ::back)
                    Spacer(Modifier.width(12.dp))
                    Text(if (initial == null) "新工作流" else "编辑工作流", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = ::tryRun, enabled = !trying && draft.steps.isNotEmpty()) {
                        if (trying) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text(if (trying) "在跑" else "试跑")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = ::save, shape = CircleShape, enabled = draft.steps.isNotEmpty()) { Text("保存") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    KaviBuild(draft.steps.isEmpty(), building) { req ->
                        building = true
                        scope.launch {
                            val r = WorkflowBuilder.build(ctx, req, draft.takeIf { it.steps.isNotEmpty() })
                            building = false
                            r.onSuccess { draft = it.copy(id = draft.id, name = it.name.ifBlank { draft.name }); logs.clear(); trial = null; expanded = null }
                                .onFailure { e -> problems = (e as? WorkflowError)?.problems?.let { listOf("Kavi 搭得还有问题：") + it } ?: listOf(e.message ?: "搭不出来") }
                        }
                    }
                    BasicsCard(draft) { draft = it }
                    Column {
                        Text("步骤", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                        StepList(draft.steps, null, "root", draft.steps, expanded, logs, tools,
                            onToggle = { id -> expanded = if (expanded == id) null else id },
                            onEdit = { id, f -> edit { WfEdit.update(it, id, f) } },
                            onAdd = { adding = it },
                            onMove = { id, d -> edit { WfEdit.move(it, id, d) } },
                            onCopy = { id -> edit { WfEdit.duplicate(it, id) } },
                            onDelete = { id -> edit { WfEdit.remove(it, id) }; logs.remove(id) })
                    }
                    trial?.let { TrialSummary(it) }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
    if (packPagesInline) page()
    else Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = ::back)
        page()
    }

    adding?.let { slot ->
        ModalBottomSheet(onDismissRequest = { adding = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = Radii.sheetTop, containerColor = raisedColor) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("加一步", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                STEP_TYPES.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        row.forEach { (type, name, desc) ->
                            val (g, hue) = stepLook(type)
                            Row(Modifier.weight(1f).clip(Radii.m).background(MaterialTheme.colorScheme.background).clickable {
                                val id = WfEdit.newId(draft.steps)
                                edit { WfEdit.insert(it, slot, WfEdit.blank(type, id)) }
                                expanded = id; adding = null
                            }.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Glyph(g, hue, 32)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    granting?.let { needs ->
        val w = draft.copy(name = draft.name.trim())
        val missing = com.jack.ai.data.workflow.WfGrants.missing(w)
        AlertDialog(onDismissRequest = { granting = null }, title = { Text("后台跑时会往外做这些事") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    needs.forEach { n ->
                        val (g, hue) = stepLook("tool")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Glyph(if (n.tool == "send_email") "✉" else if (n.tool == "phone") "📱" else g, if (missing.any { it.stepId == n.stepId }) Hue.ORANGE else hue, 30)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(n.label, style = MaterialTheme.typography.bodyMedium)
                                if (missing.none { it.stepId == n.stepId }) Text("之前同意过", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Text("同意后，后台跑到这些步骤时不再问你；做的事和这里不一样（换了收件人、换了 App）的那一步会停下来通知你。" +
                        "付款、转账、密码这类授权了也不做。随时能在工作流页撤销。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { granting = null; commit(w, approved = true) }) { Text("同意并保存") } },
            dismissButton = { TextButton(onClick = { granting = null; commit(w, approved = false) }) { Text("先不授权") } })
    }

    problems?.let { list ->
        AlertDialog(onDismissRequest = { problems = null }, title = { Text("还有这些要改") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { list.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) } } },
            confirmButton = { TextButton(onClick = { problems = null }) { Text("知道了") } })
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("改的还没保存") },
        confirmButton = { TextButton(onClick = { leaving = false; save() }) { Text("保存") } },
        dismissButton = { Row { TextButton(onClick = { leaving = false; onClose() }) { Text("不保存") }; TextButton(onClick = { leaving = false }) { Text("接着改") } } })
}

// ---------- 让 Kavi 搭 ----------

@Composable
private fun KaviBuild(empty: Boolean, busy: Boolean, onBuild: (String) -> Unit) {
    var open by remember { mutableStateOf(empty) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(Radii.l).background(Hue.VIOLET.wash).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("✦", color = Hue.VIOLET.color, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(if (empty) "说说要做什么，让 Kavi 搭" else "让 Kavi 帮你改", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Chevron(open)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, shape = Radii.m,
                    placeholder = { Text(if (empty) "比如：每个工作日 8 点查上海天气，要下雨就提醒我带伞" else "比如：跌超 5% 才提醒，再加一步存成文件") })
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("用主模型，花一点钱", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                    Button(onClick = { onBuild(text) }, enabled = text.isNotBlank() && !busy, shape = CircleShape) {
                        if (busy) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text("在搭…") }
                        else Text(if (empty) "搭" else "改")
                    }
                }
            }
        }
    }
}

// ---------- 名字、什么时候跑、更多设置 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasicsCard(w: Workflow, onChange: (Workflow) -> Unit) {
    var more by remember { mutableStateOf(false) }
    // 改已有的：收成一行（名字 · 什么时候跑），点开再改，步骤露在前面
    var open by remember { mutableStateOf(w.name.isBlank()) }
    val t = w.trigger
    if (!open) {
        val scheduled = t.type == "schedule"
        Row(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).clickable { open = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            triggerLook(t).let { (g, h) -> Glyph(g, h, 40) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(WorkflowFormat.describe(t) + " · 最多 ${w.limits.timeoutMin} 分钟 · ¥${w.limits.maxCostYuan} · " + deliverLabel(w.deliver),
                    style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("改", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(w.name, { onChange(w.copy(name = it)) }, Modifier.fillMaxWidth(), singleLine = true, shape = Radii.m, label = { Text("名字") },
            placeholder = { Text("比如：股票早报") })
        OutlinedTextField(w.description, { onChange(w.copy(description = it)) }, Modifier.fillMaxWidth(), maxLines = 3, shape = Radii.m, label = { Text("一句话说明（可不填）") })
        Text("什么时候跑", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val kinds = listOf("manual" to "手动", "daily" to "每天", "weekdays" to "工作日", "weekly" to "每周", "interval" to "每隔", "once" to "某天一次",
            "watch" to "监控", "notify" to "收到通知", "share" to "分享过来")
        val cur = if (t.type == "schedule") t.repeat else t.type
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { (k, l) ->
                Pill(l, k == cur) {
                    onChange(w.copy(trigger = if (k in setOf("manual", "notify", "share")) t.copy(type = k) else t.copy(type = "schedule", repeat = k,
                        days = if (k == "weekly" && t.days.isEmpty()) listOf(1) else t.days,
                        date = if (k == "once" && t.date.isBlank()) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + 86_400_000)) else t.date)))
                }
            }
        }
        if (t.type == "notify") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(t.app, { onChange(w.copy(trigger = t.copy(app = it))) }, Modifier.weight(1f), singleLine = true, shape = Radii.m,
                    label = { Text("哪个 App") }, placeholder = { Text("微信、淘宝…") })
                OutlinedTextField(t.keyword, { onChange(w.copy(trigger = t.copy(keyword = it))) }, Modifier.weight(1f), singleLine = true, shape = Radii.m,
                    label = { Text("通知里有") }, placeholder = { Text("快递，取件") })
            }
            Text("两个至少填一个；几个词用逗号隔开，有一个就算。步骤里用 {{输入.app}}、{{输入.title}}、{{输入.text}} 拿到这条通知。同一个工作流一分钟最多跑一次。",
                style = MaterialTheme.typography.labelSmall, color = if (t.app.isBlank() && t.keyword.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            val ctx = LocalContext.current
            val granted = remember { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
            if (!granted) Row(Modifier.fillMaxWidth().clip(Radii.m).background(Hue.ORANGE.wash).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kavi 还没有「通知使用权」，收不到通知", style = MaterialTheme.typography.bodySmall, color = Hue.ORANGE.color, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("去给") }
            }
        }
        if (t.type == "share") Text("在别的 App 里点分享，选「交给 Kavi 工作流」，再挑这个。分享的文字或链接用 {{输入}} 拿到。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        if (t.type == "schedule" && t.repeat == "watch") Text("每隔一阵跑一次。步骤里用「如果……就停下」表示还没等到；哪次一路跑完没停下，就算等到了：发通知，然后不再查。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        if (t.type == "schedule") {
            if (t.repeat == "weekly") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                "一二三四五六日".forEachIndexed { i, c ->
                    val d = i + 1
                    Pill("周$c", d in t.days) { onChange(w.copy(trigger = t.copy(days = if (d in t.days) (t.days - d).ifEmpty { listOf(d) } else (t.days + d).sorted()))) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (t.repeat == "interval" || t.repeat == "watch") OutlinedTextField(t.everyMin.toString(), { v -> v.filter(Char::isDigit).take(5).toIntOrNull()?.let { onChange(w.copy(trigger = t.copy(everyMin = it))) } },
                    Modifier.weight(1f), singleLine = true, shape = Radii.m, label = { Text("每隔几分钟") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = t.everyMin < 15, supportingText = { Text("最少 15 分钟") })
                else {
                    if (t.repeat == "once") OutlinedTextField(t.date, { onChange(w.copy(trigger = t.copy(date = it.take(10)))) }, Modifier.weight(1f), singleLine = true, shape = Radii.m,
                        label = { Text("日期") }, placeholder = { Text("2026-12-01") }, isError = !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(t.date))
                    OutlinedTextField(t.time, { onChange(w.copy(trigger = t.copy(time = it.take(5)))) }, Modifier.weight(1f), singleLine = true, shape = Radii.m,
                        label = { Text("几点") }, placeholder = { Text("08:00") }, isError = WorkflowFormat.hm(t.time) == null)
                }
            }
            WorkflowFormat.nextAt(w.copy(enabled = true), System.currentTimeMillis())?.let {
                Text("下次：" + java.text.SimpleDateFormat("M月d日 EEEE HH:mm", java.util.Locale.CHINA).format(java.util.Date(it)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth().clickable { more = !more }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("更多设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("最多 ${w.limits.timeoutMin} 分钟 · ¥${w.limits.maxCostYuan} · " + deliverLabel(w.deliver), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(6.dp))
            Chevron(more)
        }
        AnimatedVisibility(more) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(w.limits.timeoutMin.toString(), { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { onChange(w.copy(limits = w.limits.copy(timeoutMin = it))) } },
                        Modifier.weight(1f), singleLine = true, shape = Radii.m, label = { Text("最多跑几分钟") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(w.limits.maxCostYuan.toString(), { v -> v.toDoubleOrNull()?.let { onChange(w.copy(limits = w.limits.copy(maxCostYuan = it))) } },
                        Modifier.weight(1f), singleLine = true, shape = Radii.m, label = { Text("最多花多少元") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text("结果送到", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("inbox", "notify", "none").forEach { d -> Pill(deliverLabel(d), w.deliver == d) { onChange(w.copy(deliver = d)) } }
                }
                Text("步骤里自己发了通知或收件箱的，就不再另外送。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun deliverLabel(d: String) = when (d) { "notify" -> "通知"; "none" -> "不送"; else -> "收件箱" }

// ---------- 步骤卡片 ----------

@Composable
private fun StepList(
    list: List<WfStep>, parent: String?, branch: String, all: List<WfStep>, expanded: String?, logs: Map<String, List<WfStepLog>>, tools: List<ToolInfo>,
    onToggle: (String) -> Unit, onEdit: (String, (WfStep) -> WfStep) -> Unit, onAdd: (WfEdit.Slot) -> Unit,
    onMove: (String, Int) -> Unit, onCopy: (String) -> Unit, onDelete: (String) -> Unit,
) {
    Column {
        if (list.isEmpty()) AddGap(first = true) { onAdd(WfEdit.Slot(parent, branch, 0)) }
        list.forEachIndexed { i, s ->
            StepCard(s, all, expanded == s.id, logs[s.id].orEmpty(), tools, onToggle, onEdit, onMove, onCopy, onDelete)
            when (s.type) {
                "if" -> {
                    Branch("是", Hue.GREEN) { StepList(s.then, s.id, "then", all, expanded, logs, tools, onToggle, onEdit, onAdd, onMove, onCopy, onDelete) }
                    Branch("否", Hue.GRAY) { StepList(s.otherwise, s.id, "else", all, expanded, logs, tools, onToggle, onEdit, onAdd, onMove, onCopy, onDelete) }
                }
                "forEach" -> Branch("每一项都做", Hue.SKY) { StepList(s.steps, s.id, "steps", all, expanded, logs, tools, onToggle, onEdit, onAdd, onMove, onCopy, onDelete) }
            }
            AddGap { onAdd(WfEdit.Slot(parent, branch, i + 1)) }
        }
    }
}

/** 卡片之间的「＋」。 */
@Composable
private fun AddGap(first: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 16.dp).width(2.dp).height(14.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(10.dp))
        Text(if (first) "＋ 加一步" else "＋", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun StepCard(
    s: WfStep, all: List<WfStep>, open: Boolean, logs: List<WfStepLog>, tools: List<ToolInfo>,
    onToggle: (String) -> Unit, onEdit: (String, (WfStep) -> WfStep) -> Unit,
    onMove: (String, Int) -> Unit, onCopy: (String) -> Unit, onDelete: (String) -> Unit,
) {
    val (g, hue) = stepLook(s.type)
    val colors = MaterialTheme.colorScheme
    val last = logs.lastOrNull()
    val failed = logs.any { !it.ok && !it.skipped }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor)
        .then(if (failed) Modifier.border(1.5.dp, Hue.RED.color, Radii.l) else if (open) Modifier.border(1.5.dp, hue.color.copy(alpha = 0.6f), Radii.l) else Modifier)
        .animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { onToggle(s.id) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(g, hue)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s.type in setOf("if", "forEach")) label(s) else s.name.ifBlank { label(s) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (!open) Text(vars(detail(s).ifBlank { "点开填" }, Hue.VIOLET.color, Hue.VIOLET.wash), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                CircleIconButton(Icons.Default.MoreVert, "更多", size = 34.dp, iconSize = 18.dp) { menu = true }
                RoundMenu(menu, { menu = false }) {
                    MenuRow("上移", glyph = "↑", enabled = WfEdit.canMove(all, s.id, -1)) { menu = false; onMove(s.id, -1) }
                    MenuRow("下移", glyph = "↓", enabled = WfEdit.canMove(all, s.id, 1)) { menu = false; onMove(s.id, 1) }
                    MenuRow("复制一份", glyph = "⧉") { menu = false; onCopy(s.id) }
                    MenuRow("删除", glyph = "✕", danger = true, sub = if (s.then.size + s.otherwise.size + s.steps.size > 0) "里面的步骤也一起删" else null) { menu = false; onDelete(s.id) }
                }
            }
        }
        if (open) StepForm(s, WfEdit.varsBefore(all, s.id), tools) { f -> onEdit(s.id, f) }
        if (last != null) TrialLine(logs)
    }
}

/** 试跑后卡片底下那行：这一步拿到了什么（循环里的显示一共几次、最后一次）。 */
@Composable
private fun TrialLine(logs: List<WfStepLog>) {
    val last = logs.last()
    val bad = logs.firstOrNull { !it.ok && !it.skipped } ?: last
    val show = if (bad.ok) last else bad
    val hue = when { !show.ok && show.skipped -> Hue.AMBER; !show.ok -> Hue.RED; else -> Hue.GREEN }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).clip(Radii.m).background(hue.wash).padding(10.dp)) {
        Text((if (!show.ok) (if (show.skipped) "出错，跳过了" else "出错") else "拿到了") + (if (logs.size > 1) " · 跑了 ${logs.size} 次" + (show.at.takeIf { it.isNotEmpty() }?.let { "，这是$it" } ?: "") else "") +
            " · ${show.ms} ms" + (if (show.costYuan > 0) " · ¥" + String.format(java.util.Locale.US, "%.3f", show.costYuan) else ""),
            style = MaterialTheme.typography.labelSmall.tabular, color = hue.color, fontWeight = FontWeight.SemiBold)
        val v = if (show.ok) show.output else show.error
        if (v.isNotBlank()) Text(v.take(600), style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (v.trimStart().startsWith("{") || v.trimStart().startsWith("[")) CodeFont else null),
            maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TrialSummary(r: WfRun) {
    Column(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("试跑", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusChip(r)
        }
        Text("${r.steps.size} 步 · ${(r.endedAt - r.startedAt) / 1000.0} 秒" + if (r.costYuan > 0) " · ¥" + String.format(java.util.Locale.US, "%.3f", r.costYuan) else "",
            style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.outline)
        if (r.error.isNotBlank()) Text(r.error, style = MaterialTheme.typography.bodySmall, color = if (r.status == "stopped") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        Text("试跑不记运行记录；步骤里的通知、收件箱、存文件是真的会做的。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

// ---------- 每种步骤要填的 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepForm(s: WfStep, vars: List<Pair<String, String>>, tools: List<ToolInfo>, edit: ((WfStep) -> WfStep) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (s.type) {
            "tool" -> ToolForm(s, vars, tools, edit)
            "ai" -> {
                VarField("要它做什么", s.prompt, vars, minLines = 3, hint = "比如：用一句话点评 {{这一只.名字}} 今天的走势") { v -> edit { it.copy(prompt = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("按 JSON 回", style = MaterialTheme.typography.bodyMedium)
                        Text("后面要用「取出」或 {{名字.字段}} 取它的某一项时打开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(s.json, { on -> edit { it.copy(json = on) } })
                }
            }
            "notify" -> {
                VarField("标题", s.title, vars) { v -> edit { it.copy(title = v) } }
                VarField("内容", s.body, vars, minLines = 2) { v -> edit { it.copy(body = v) } }
            }
            "message" -> VarField("发到收件箱的话", s.text, vars, minLines = 3) { v -> edit { it.copy(text = v) } }
            "file" -> {
                VarField("文件名", s.file, vars, hint = "比如：日报-{{今天}}.md") { v -> edit { it.copy(file = v) } }
                VarField("内容", s.text, vars, minLines = 3) { v -> edit { it.copy(text = v) } }
            }
            "code" -> {
                OutlinedTextField(s.code, { v -> edit { it.copy(code = v) } }, Modifier.fillMaxWidth(), minLines = 4, shape = Radii.m,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont), label = { Text("JavaScript") },
                    placeholder = { Text("return vars.行情.filter(x => x.涨跌幅 < -3)", fontFamily = CodeFont) })
                Text("vars.名字 拿前面的结果，return 的就是这一步的结果。只能算东西，不能联网。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            "set" -> VarField("值", WfTemplate.text(s.value), vars) { v -> edit { it.copy(value = JsonPrimitive(v)) } }
            "extract" -> {
                VarField("从哪取", s.from, vars, hint = "{{行情}}") { v -> edit { it.copy(from = v) } }
                val byRegex = s.regex.isNotBlank() || (s.path.isBlank() && s.from.isNotBlank() && false)
                var mode by remember(s.id) { mutableStateOf(if (byRegex) "regex" else "path") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("按路径（JSON）", mode == "path") { mode = "path"; edit { it.copy(regex = "") } }
                    Pill("按正则", mode == "regex") { mode = "regex"; edit { it.copy(path = "") } }
                }
                if (mode == "path") OutlinedTextField(s.path, { v -> edit { it.copy(path = v) } }, Modifier.fillMaxWidth(), singleLine = true, shape = Radii.m,
                    label = { Text("路径") }, placeholder = { Text("[0].价格 或 data.items") })
                else OutlinedTextField(s.regex, { v -> edit { it.copy(regex = v) } }, Modifier.fillMaxWidth(), singleLine = true, shape = Radii.m,
                    label = { Text("正则（取第一组括号）") }, placeholder = { Text("订单号 (\\w+)") }, isError = s.regex.isNotEmpty() && runCatching { Regex(s.regex) }.isFailure)
            }
            "if" -> CondForm(s, vars, edit)
            "forEach" -> {
                VarField("对哪个列表", s.list, vars, hint = "{{行情}}") { v -> edit { it.copy(list = v) } }
                OutlinedTextField(s.name, { v -> edit { it.copy(name = v) } }, Modifier.fillMaxWidth(), singleLine = true, shape = Radii.m,
                    label = { Text("每一项叫什么") }, supportingText = { Text("里面的步骤用 {{${s.name.ifBlank { "这一项" }}}}、{{${s.name.ifBlank { "这一项" }}.字段}}、{{序号}}") })
            }
            "wait" -> OutlinedTextField(s.seconds.toString(), { v -> v.filter(Char::isDigit).take(4).toIntOrNull()?.let { n -> edit { it.copy(seconds = n) } } },
                Modifier.fillMaxWidth(), singleLine = true, shape = Radii.m, label = { Text("等几秒（最多 3600）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            "stop" -> VarField("为什么停（可不填）", s.reason, vars) { v -> edit { it.copy(reason = v) } }
        }
        if (WfEdit.yields(s.type) || s.type == "forEach") {
            if (s.type != "forEach") OutlinedTextField(s.name, { v -> edit { it.copy(name = v) } }, Modifier.fillMaxWidth(), singleLine = true, shape = Radii.m,
                label = { Text("结果叫什么") }, supportingText = { Text("后面用 {{${s.varName}}} 拿到这一步的结果") })
        }
        if (s.type !in setOf("if", "forEach", "stop")) {
            Text("出错了", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("停下", s.onError == "stop") { edit { it.copy(onError = "stop", retries = 0) } }
                Pill("跳过接着做", s.onError == "skip") { edit { it.copy(onError = "skip", retries = 0) } }
                (1..3).forEach { n -> Pill("重试 $n 次", s.onError == "retry" && s.retries == n) { edit { it.copy(onError = "retry", retries = n) } } }
            }
        }
    }
}

@Composable
private fun ToolForm(s: WfStep, vars: List<Pair<String, String>>, tools: List<ToolInfo>, edit: ((WfStep) -> WfStep) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val info = tools.firstOrNull { it.name == s.tool }
    Row(Modifier.fillMaxWidth().clip(Radii.m).border(1.dp, MaterialTheme.colorScheme.outline, Radii.m).clickable { picking = true }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("工具", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.tool.ifBlank { "点这里选" }, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                color = if (s.tool.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            if (info != null) Text(info.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("换", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    // 参数：工具说明里有的列出来；用户自己加的也留着
    val keys = (info?.params?.map { it.name }.orEmpty() + s.args.keys).distinct()
    keys.forEach { k ->
        val p = info?.params?.firstOrNull { it.name == k }
        VarField(k + if (p?.required == true) " *" else "", s.args[k]?.let { WfTemplate.text(it) }.orEmpty(), vars,
            hint = p?.options?.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: p?.description?.take(60)) { v ->
            edit { st -> st.copy(args = JsonObject(if (v.isEmpty()) st.args - k else st.args + (k to typed(v, p?.type)))) }
        }
    }
    if (picking) AlertDialog(onDismissRequest = { picking = false }, title = { Text("选一个工具") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (tools.isEmpty()) Text("现在没有能用的工具（技能都关着？）", style = MaterialTheme.typography.bodySmall)
                // 工具包的排前面：做工作流多半是为了它们
                tools.sortedBy { if (it.name.startsWith(com.jack.ai.data.packs.Packs.PREFIX)) 0 else 1 }.forEach { t ->
                    Column(Modifier.fillMaxWidth().clip(Radii.s).clickable {
                        edit { it.copy(tool = t.name, args = JsonObject(it.args.filterKeys { k -> t.params.any { p -> p.name == k } })) }; picking = false
                    }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont), fontWeight = FontWeight.SemiBold)
                        Text(t.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picking = false }) { Text("取消") } })
}

/** 填的是字：按参数类型换成数字、真假或 JSON（里面有 {{变量}} 的照字留着，跑的时候再换）。 */
private fun typed(v: String, type: String?): JsonElement = when {
    "{{" in v -> JsonPrimitive(v)
    type == "number" || type == "integer" -> v.toLongOrNull()?.let { JsonPrimitive(it) } ?: v.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
    type == "boolean" -> if (v == "true" || v == "false") JsonPrimitive(v == "true") else JsonPrimitive(v)
    type == "object" || type == "array" -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(v) }.getOrElse { JsonPrimitive(v) }
    else -> JsonPrimitive(v)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CondForm(s: WfStep, vars: List<Pair<String, String>>, edit: ((WfStep) -> WfStep) -> Unit) {
    val c = s.cond ?: WfCond()
    if (c.all.isNotEmpty() || c.any.isNotEmpty()) {
        Text("几个条件一起：" + com.jack.ai.data.workflow.condText(c), style = MaterialTheme.typography.bodySmall)
        Text("要改的话让 Kavi 改，或者换成一个条件：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        TextButton(onClick = { edit { it.copy(cond = WfCond(op = ">")) } }) { Text("换成一个条件") }
        return
    }
    VarField("拿什么比", c.left, vars, hint = "{{这一只.涨跌幅}}") { v -> edit { it.copy(cond = c.copy(left = v)) } }
    var ops by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(CircleShape).background(Hue.ORANGE.wash).clickable { ops = true }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(OPS.firstOrNull { it.first == c.op }?.second ?: c.op, style = MaterialTheme.typography.labelLarge, color = Hue.ORANGE.color, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Chevron(ops, tint = Hue.ORANGE.color)
        }
        RoundMenu(ops, { ops = false }) {
            OPS.forEach { (op, l) -> MenuRow(l, glyph = when (op) { "==" -> "="; "!=" -> "≠"; ">" -> ">"; ">=" -> "≥"; "<" -> "<"; "<=" -> "≤"; "contains" -> "∋"; "notContains" -> "∌"; "empty" -> "∅"; "notEmpty" -> "◉"; else -> ".*" },
                checked = c.op == op) { ops = false; edit { it.copy(cond = c.copy(op = op)) } } }
        }
    }
    if (c.op !in setOf("empty", "notEmpty")) VarField(if (c.op == "matches") "正则" else "和什么比", c.right, vars, hint = "-3") { v -> edit { it.copy(cond = c.copy(right = v)) } }
}

/** 输入框，右边的 {x} 从前面的步骤里挑一个变量插进去。 */
@Composable
private fun VarField(label: String, value: String, vars: List<Pair<String, String>>, minLines: Int = 1, hint: String? = null, onChange: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = minLines == 1, minLines = minLines, maxLines = if (minLines == 1) 1 else 10, shape = Radii.m,
            label = { Text(label) }, placeholder = hint?.let { h -> { Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            trailingIcon = {
                Box {
                    Text("{x}", style = MaterialTheme.typography.labelLarge.copy(fontFamily = CodeFont), color = Hue.VIOLET.color, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(Radii.xs).background(Hue.VIOLET.wash).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                    RoundMenu(menu, { menu = false }) {
                        vars.forEach { (n, from) -> MenuRow(n, glyph = "{}", sub = from) { menu = false; onChange(value + "{{$n}}") } }
                    }
                }
            })
    }
}
