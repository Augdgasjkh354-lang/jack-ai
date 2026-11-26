package com.jack.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Conversation
import com.jack.ai.data.Pricing
import com.jack.ai.data.UsageDay
import com.jack.ai.data.costYuan
import com.jack.ai.data.modelLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal fun tokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1e3)
    else -> "$n"
}

internal fun yuan(d: Double): String = when {
    d == 0.0 -> "¥0"
    d < 0.01 -> String.format(Locale.US, "¥%.4f", d)
    else -> String.format(Locale.US, "¥%.2f", d)
}

private val List<UsageDay>.total get() = sumOf { it.prompt + it.completion }
private val List<UsageDay>.money get() = sumOf { it.costYuan() ?: 0.0 }

/** 命中率写成百分数，保留两位小数（99.87%）；整数除会把 99.9% 截成 99%。 */
fun hitPercent(hit: Long, total: Long): String =
    if (total <= 0) "0.00%" else String.format(java.util.Locale.US, "%.2f%%", hit * 100.0 / total)

/** 缓存命中率；服务都没返回命中数时是「未提供」。 */
private fun hitRate(l: List<UsageDay>): String {
    val known = l.filter { it.hitKnown || it.cacheHit > 0 }
    val prompt = known.sumOf { it.prompt }
    if (known.isEmpty() || prompt == 0L) return "未提供"
    return hitPercent(known.sumOf { it.cacheHit }, prompt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    val usage by produceState<List<UsageDay>?>(null) { value = vm.loadUsage() }
    var editBudget by remember { mutableStateOf(false) }
    var editQuota by remember { mutableStateOf(false) }
    var editWorkLimit by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        val list = usage ?: emptyList()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = fmt.format(Calendar.getInstance().time)
        val month = today.take(7)
        val todayList = list.filter { it.date == today }
        val monthList = list.filter { it.date.startsWith(month) }
        val colors = MaterialTheme.colorScheme
        val budget = state.settings.monthlyBudget
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("今天" to todayList, "本月" to monthList, "全部" to list).forEach { (label, l) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            // 和全 App 一样用无衬线；数字等宽，三格对得齐
                            Text(yuan(l.money), style = MaterialTheme.typography.titleLarge.tabular, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                maxLines = 1, softWrap = false)
                            Text(tokens(l.total) + " token", style = MaterialTheme.typography.labelMedium.tabular, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---- 实测：浏览器、操作其他 App、Office 一次做对率 ----
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val measured by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>()) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    listOf(
                        "浏览器实测成功率" to com.jack.ai.data.browser.BrowserStats.summary(com.jack.ai.data.browser.BrowserStats.load(appCtx)),
                        "操作其他 App 实测成功率" to com.jack.ai.data.phone.PhoneStats.summary(com.jack.ai.data.phone.PhoneStats.load(appCtx)),
                        "Word、PPT、Excel 一次做对率" to com.jack.ai.data.office.OfficeStats.summary(com.jack.ai.data.office.OfficeStats.load(appCtx)),
                    ).filter { it.second.isNotEmpty() }
                }
            }
            measured.forEach { (title, body) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }

            // ---- 用量额度：5 小时、每周（token） ----
            QuotaCard(state.settings) { editQuota = true }

            // ---- ChatGPT 套餐：Kavi 花了多少 ----
            if (state.settings.providers.any { it.baseUrl == com.jack.ai.data.CHATGPT_PROVIDER_URL }) ChatGptUsageCard()

            // ---- 预算 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每月预算", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editBudget = true }) { Text(if (budget > 0) "修改" else "设置") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val wl = state.settings.workCostLimit
                        Text("单次回答花费上限" + if (wl > 0) " ${yuan(wl)}" else "：不限",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editWorkLimit = true }) { Text("修改") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("任务交付前复核", style = MaterialTheme.typography.bodyMedium)
                            Text("做完后另请一次模型，只看结果、按验收标准查一遍，不合格打回去改。每个任务多花一次请求",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Switch(checked = state.settings.workReview, onCheckedChange = { on -> vm.updateSettings { it.copy(workReview = on) } })
                    }
                    Text("任务开工前确认清单", style = MaterialTheme.typography.bodyMedium)
                    Text("Kavi 列好步骤和验收标准后先给你看，你点「开始」才动手。大任务：6 步以上，或 Kavi 觉得要求含糊、花费大时",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("always" to "每次", "big" to "大任务时", "never" to "从不").forEach { (id, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = state.settings.workConfirmPlan == id,
                                onClick = { vm.updateSettings { it.copy(workConfirmPlan = id) } },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (budget > 0) {
                        val spent = monthList.money
                        SoftBar((spent / budget).toFloat(), if (spent >= budget) colors.error else colors.primary)
                        Text("本月已花 ${yuan(spent)} / ${yuan(budget)}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                    }
                }
            }

            // ---- DeepSeek 时段 ----
            // 一行状态，不是警告：高峰用琥珀色的小点，空闲用绿色，不再整行红字
            val peak = Pricing.isPeak(System.currentTimeMillis())
            val hue = if (peak) Hue.AMBER else Hue.GREEN
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(hue.color))
                    Spacer(Modifier.width(10.dp))
                    Text(if (peak) "DeepSeek 高峰时段" else "DeepSeek 空闲时段", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(if (peak) "原价" else "5 折", style = MaterialTheme.typography.labelLarge, color = hue.color,
                        modifier = Modifier.clip(CircleShape).background(hue.color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("最近 14 天（元）", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    val days = (13 downTo 0).map { back ->
                        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
                        fmt.format(c.time)
                    }
                    val values = days.map { d -> list.filter { it.date == d }.money }
                    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(0.0001)
                    val bar = colors.primary
                    val empty = colors.outlineVariant
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val gap = 6.dp.toPx()
                        val w = (size.width - gap * (values.size - 1)) / values.size
                        values.forEachIndexed { i, v ->
                            val h = if (v == 0.0) 3.dp.toPx() else (size.height * (v / max).toFloat()).coerceAtLeast(4.dp.toPx())
                            drawRoundRect(
                                color = if (v == 0.0) empty else bar,
                                topLeft = Offset(i * (w + gap), size.height - h),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(days.first().drop(5), style = MaterialTheme.typography.labelSmall, color = colors.outline)
                        Spacer(Modifier.weight(1f))
                        Text("今天 ${yuan(values.last())}", style = MaterialTheme.typography.labelSmall, color = colors.outline)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本月按模型", style = MaterialTheme.typography.titleSmall)
                    if (monthList.isEmpty()) Text("本月还没有用量", color = colors.outline)
                    monthList.groupBy { it.model }.forEach { (m, l) ->
                        val priced = l.any { it.costYuan() != null }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(modelLabel(m))
                                Text(
                                    "${l.sumOf { it.requests }} 次 · 输入 ${tokens(l.sumOf { it.prompt })}（缓存命中 ${hitRate(l)}）· 输出 ${tokens(l.sumOf { it.completion })}",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                )
                            }
                            Text(if (priced) yuan(l.money) else "未计价", color = colors.primary)
                        }
                    }
                }
            }

            ContextCard(vm, state.current)

            RecentRequestsCard()

            ToolStatsCard(vm)

            FeedbackCard(vm)

            HedgeCard(state.conversations)

            InjectionCard()

            val top = state.conversations.filter { it.spentTokens > 0 }.sortedWith(
                compareByDescending<Conversation> { it.spentCost }.thenByDescending { it.spentTokens },
            ).take(5)
            if (top.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("最花钱的对话", style = MaterialTheme.typography.titleSmall)
                        top.forEach { c ->
                            Row {
                                Text(c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(8.dp))
                                Text(if (c.spentCost > 0) yuan(c.spentCost) else tokens(c.spentTokens), color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

        }
    }

    if (editBudget) {
        var text by remember { mutableStateOf(if (state.settings.monthlyBudget > 0) state.settings.monthlyBudget.toString() else "") }
        fun saveBudget() { vm.updateSettings { it.copy(monthlyBudget = (text.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)) }; editBudget = false }
        AlertDialog(
            onDismissRequest = { editBudget = false },
            title = { Text("每月预算") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("金额（元），留空表示不设") }, singleLine = true, modifier = Modifier.focusOnShow(),
                    // 数字键盘上的「完成」直接保存
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = doneAction(true) { saveBudget() },
                )
            },
            confirmButton = { TextButton(onClick = { saveBudget() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editBudget = false }) { Text("取消") } },
        )
    }
    if (editQuota) {
        var five by remember { mutableStateOf(mText(state.settings.quotaFiveHours)) }
        var week by remember { mutableStateOf(mText(state.settings.quotaWeek)) }
        AlertDialog(
            onDismissRequest = { editQuota = false },
            title = { Text("用量额度") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("单位是百万 token（M），输入加输出都算。留空表示不限。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(five, { five = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("5 小时额度（M）") }, singleLine = true, modifier = Modifier.focusOnShow(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Next))
                    OutlinedTextField(week, { week = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("每周额度（M）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fun parse(t: String) = ((t.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong().coerceAtLeast(0)
                    vm.updateSettings { it.copy(quotaFiveHours = parse(five), quotaWeek = parse(week)) }
                    editQuota = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editQuota = false }) { Text("取消") } },
        )
    }
    if (editWorkLimit) {
        var text by remember { mutableStateOf(state.settings.workCostLimit.takeIf { it > 0 }?.toString().orEmpty()) }
        fun saveLimit() { vm.updateSettings { it.copy(workCostLimit = (text.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)) }; editWorkLimit = false }
        AlertDialog(
            onDismissRequest = { editWorkLimit = false },
            title = { Text("单次回答花费上限") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("花到这么多先停下问你（元），留空不限") }, singleLine = true, modifier = Modifier.focusOnShow(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = doneAction(true) { saveLimit() },
                )
            },
            confirmButton = { TextButton(onClick = { saveLimit() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editWorkLimit = false }) { Text("取消") } },
        )
    }
}

private fun mText(n: Long): String = if (n <= 0) "" else (n / 1_000_000.0).toString().removeSuffix(".0")

/** 5 小时额度和每周额度：进度条、用了多少、几点恢复。 */
@Composable
private fun QuotaCard(settings: com.jack.ai.data.Settings, onEdit: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    // 每 30 秒刷新一次，恢复时间到了进度条跟着清零
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() }
    }
    val windows = remember(settings, now) { com.jack.ai.data.Quota.windows(ctx, settings, now) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("用量额度", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("修改") }
            }
            windows.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (w.limit <= 0) "不限 · 已用 ${com.jack.ai.data.Quota.short(w.used)}"
                        else "${com.jack.ai.data.Quota.short(w.used)} / ${com.jack.ai.data.Quota.short(w.limit)}",
                        style = MaterialTheme.typography.bodySmall.tabular, color = colors.onSurfaceVariant,
                    )
                }
                if (w.limit > 0) SoftBar(w.fraction, if (w.over) colors.error else if (w.fraction >= 0.8f) Hue.AMBER.color else colors.primary)
                Text(
                    when {
                        w.start <= 0 -> "还没开始计时，下次用时开始算"
                        w.over -> "已用完，${com.jack.ai.data.Quota.resetText(w.resetAt, now)}恢复"
                        else -> "${com.jack.ai.data.Quota.resetText(w.resetAt, now)}恢复"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (w.over) colors.error else colors.outline,
                )
            }
            Text("用完后新消息发不出去，正在做的事做完这一步就停；定时任务也会跳过。", style = MaterialTheme.typography.bodySmall, color = colors.outline)
        }
    }
}

/** 一次请求的上下文构成：固定说明、记忆、项目资料、聊天记录各占多少。 */
@Composable
private fun ContextCard(vm: ChatViewModel, conv: Conversation?) {
    val colors = MaterialTheme.colorScheme
    val parts = remember(conv?.id, conv?.messages?.size, conv?.summary, conv?.skillTools) { vm.contextBreakdown(conv) }
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (conv != null && conv.messages.isNotEmpty()) "上下文构成 · 当前对话" else "上下文构成 · 新对话",
                style = MaterialTheme.typography.titleSmall)
            parts.forEach { (label, n) ->
                Column {
                    Row {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("约 ${tokens(n.toLong())}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    SoftBar(n.toFloat() / total, colors.primary, Modifier.padding(top = 4.dp))
                }
            }
            val real = conv?.lastPromptTokens ?: 0
            Text(
                "合计约 ${tokens(total.toLong())}" + if (real > 0) "，上次请求实际 ${tokens(real.toLong())}（接口返回，含当时的清单和工具过程）" else "",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            // 工具按需出现：不常用技能的工具读了做法才加进来，这里说一声这个对话打开了哪些
            val opened = remember(conv?.skillTools) { vm.openedSkillNames(conv) }
            Text(
                if (opened.isEmpty()) "GitHub、Notion、日历、邮箱、浏览器、文档生成这些不常用技能的工具，用到时才加进来。"
                else "这个对话里打开过：${opened.joinToString("、")}（之后一直带着它们的工具）",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
        }
    }
}

/** 每个工具调了几次、失败几次，失败多的排前面：用来看哪个工具不好用。 */
/** 最近每一次请求：输入多少、缓存命中多少。看哪里的缓存没命中。 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun RecentRequestsCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val all0 by produceState(emptyList<com.jack.ai.data.UsageRecord>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jack.ai.data.Store(ctx).recentRequests().reversed() }
    }
    if (all0.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    // 按来源筛：后台活、子 agent 一多就把聊天的请求挤没了，反过来也一样
    var kind by remember { mutableStateOf(0) }
    val list = remember(all0, kind) { all0.filter { kind == 0 || requestKind(it.label) == kind } }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近的请求", style = MaterialTheme.typography.titleSmall)
            Text("命中越高越省钱。同一个对话里接着聊，正常应该大部分命中。", style = MaterialTheme.typography.bodySmall, color = colors.outline)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "对话", "后台", "子 agent").forEachIndexed { i, name ->
                    val n = if (i == 0) all0.size else all0.count { requestKind(it.label) == i }
                    androidx.compose.material3.FilterChip(selected = kind == i, onClick = { kind = i; all = false }, label = { Text("$name $n") })
                }
            }
            (if (all) list else list.take(10)).forEachIndexed { idx, r ->
                val rate = r.hit?.let { h -> if (r.prompt > 0) h * 100.0 / r.prompt else 0.0 }
                // 命中低的：和同一个对话的上一次请求比开头，说清是哪一段变了
                val why = if (rate == null || rate >= 60 || r.label.isBlank()) null else if (r.prefix?.startsWith("gpt:") == true) gptMissReason(r.prefix) else {
                    val prev = list.drop(idx + 1).firstOrNull { it.label == r.label }
                    when (val c = prev?.let { com.jack.ai.api.PrefixPrint.firstChange(it.prefix, r.prefix) }) {
                        null -> if (prev == null) "这个对话的第一次请求" else "开头和上一次一样：${if (r.model.contains("gpt", ignoreCase = true)) "ChatGPT" else "服务"}那边没找到缓存（缓存过期，或者被分到了别的服务器）"
                        "" -> null
                        else -> "$c 和上一次不一样，从那里往后都没命中"
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label.ifBlank { "后台" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(fmt.format(java.util.Date(r.at)) + " · " + modelLabel(r.model) + " · 输入 ${tokens(r.prompt.toLong())} · 输出 ${tokens(r.completion.toLong())}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    Text(r.hit?.let { "命中 " + hitPercent(it.toLong(), r.prompt.toLong()) } ?: "未返回", style = MaterialTheme.typography.labelLarge,
                        color = when { rate == null -> colors.outline; rate >= 60 -> colors.primary; else -> colors.error })
                }
                why?.let { Text("↳ $it", style = MaterialTheme.typography.bodySmall, color = colors.error) }
            }
            if (list.size > 10) TextButton(onClick = { all = !all }) { Text(if (all) "收起" else "全部 ${list.size} 次") }
        }
    }
}

@Composable
private fun ToolStatsCard(vm: ChatViewModel) {
    val stats by produceState<List<com.jack.ai.data.ToolStat>?>(null) { value = vm.loadToolStats() }
    val list = stats.orEmpty().sortedWith(compareByDescending<com.jack.ai.data.ToolStat> { it.failures }.thenByDescending { it.calls })
    if (list.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("工具调用", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("共 ${list.sumOf { it.calls }} 次 · 失败 ${list.sumOf { it.failures }} 次",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            (if (all) list else list.take(8)).forEach { t ->
                Row {
                    Text(t.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${t.calls} 次" + if (t.failures > 0) " · 失败 ${t.failures}（${t.failures * 100 / t.calls}%）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.failures > 0) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
            if (list.size > 8) TextButton(onClick = { all = !all }) { Text(if (all) "收起" else "全部 ${list.size} 个") }
        }
    }
}

/** 攒下的问题反馈：导出成文件发给开发者，或清空。 */
@Composable
private fun FeedbackCard(vm: ChatViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val count by produceState(0, refresh) { value = vm.feedbackCount() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("问题反馈", style = MaterialTheme.typography.titleSmall)
            Text(
                if (count == 0) "回答下面点「⚠」可以记下有问题的那一轮，攒几条后在这里导出，发给帮你改 App 的人。"
                else "已记下 $count 条",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count > 0) Row {
                TextButton(onClick = {
                    vm.exportFeedback { file ->
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/markdown")
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(android.content.Intent.createChooser(send, file.name))
                    }
                }) { Text("导出") }
                TextButton(onClick = { vm.clearFeedback(); refresh++ }) { Text("清空") }
            }
        }
    }
}

/** 说话绕不绕：改成「先下判断」的提示词之前、之后，Chat 回答里每千字的转折数。 */
@Composable
private fun HedgeCard(convs: List<Conversation>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    val (before, after) = androidx.compose.runtime.remember(convs) { com.jack.ai.data.HedgeMeter.beforeAfter(ctx, convs) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("说话直不直", style = MaterialTheme.typography.titleSmall)
            Text("Chat 回答里每千字有几处「但、不过、不意味着、而是」这类转折。越少越直接",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            listOf("改之前" to before, "改之后" to after).forEach { (label, r) ->
                Row {
                    Text(label, Modifier.weight(1f))
                    Text(if (r.answers == 0) "还没有回答" else "%.1f 处 · %d 条".format(r.perThousand, r.answers), color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** 提示词注入：拦过几次、最近是在哪读到的（规则误报多的话，看这里就知道该调哪条）。 */
@Composable
private fun InjectionCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    val log = androidx.compose.runtime.produceState(emptyList<com.jack.ai.data.InjectionGuard.Record>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jack.ai.data.InjectionGuard.log(ctx) }
    }.value
    if (log.isEmpty()) return
    val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("拦下的提示词注入 · ${log.size} 次", style = MaterialTheme.typography.titleSmall)
            log.takeLast(5).reversed().forEach { r ->
                Column {
                    Text("${f.format(java.util.Date(r.at))} · ${r.kind}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(r.source, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("「${r.line}」", style = MaterialTheme.typography.bodySmall, color = colors.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * ChatGPT 套餐：Kavi 最近 5 小时、7 天用了多少（OpenAI 不给查剩余额度，只能记自己这边的），
 * 上次撞到上限的时间，服务响应头里要是有额度信息也列出来；底下链到 ChatGPT 的用量页看准确的百分比。
 */
@Composable
private fun ChatGptUsageCard() {
    val colors = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(15_000); value = System.currentTimeMillis() }
    }
    val usage = com.jack.ai.data.integrations.ChatGptUsage
    var refresh by remember { mutableStateOf(0) }
    val windows = remember(now, refresh) { usage.windows(now) }
    var calibrating by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ChatGPT 套餐", style = MaterialTheme.typography.titleSmall)
            windows.forEach { w ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        // 校准过才有百分比（估算的，前面带「约」）
                        w.percent?.let { pct ->
                            Text("约 " + String.format(java.util.Locale.US, "%.2f%%", pct), style = MaterialTheme.typography.labelLarge.tabular,
                                color = when { pct >= 90 -> colors.error; pct >= 70 -> Hue.AMBER.color; else -> colors.primary })
                        }
                    }
                    w.percent?.let { pct ->
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (pct / 100).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = when { pct >= 90 -> colors.error; pct >= 70 -> Hue.AMBER.color; else -> colors.primary },
                            trackColor = colors.onSurface.copy(alpha = 0.08f),
                            drawStopIndicator = {},
                        )
                    }
                    Text(
                        "${w.requests} 次 · ${com.jack.ai.data.Quota.short(w.tokens)} token" +
                            (if (w.input > 0) " · 缓存 ${hitPercent(w.cached.toLong(), w.input.toLong())}" else "") +
                            (if (w.usd > 0) " · 值 ${usdText(w.usd)}" else ""),
                        style = MaterialTheme.typography.bodySmall.tabular, color = colors.onSurfaceVariant,
                    )
                }
            }
            GptValue(usage.month(now), usage.since(), now)
            TextButton(onClick = { calibrating = true }) {
                Text(if (usage.calibratedAt > 0) "重新校准百分比" else "显示百分比：照 ChatGPT 用量页校准一次")
            }
            if (usage.limitHitAt > 0) Text(
                "上次额度用完：" + com.jack.ai.data.Quota.resetText(usage.limitHitAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = if (now - usage.limitHitAt < 5 * 3600_000L) colors.error else colors.outline,
            )
            if (usage.headers.isNotEmpty()) Text(
                "服务给的额度信息：\n" + usage.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            )
            Text("OpenAI 不提供查剩余额度的接口，这里是 Kavi 自己记的用量；百分比是照你上次校准估算的，" +
                "只算 Kavi 这边用的（网页版、手机 App 里用的不在内）。准确的数和几点恢复，要在 ChatGPT 的用量页看。",
                style = MaterialTheme.typography.bodySmall, color = colors.outline)
            TextButton(onClick = { Links.open(ctx, com.jack.ai.data.integrations.ChatGptAuth.USAGE_URL) }) { Text("看剩余额度、设每周上限") }
        }
    }
    if (calibrating) ChatGptCalibrateDialog(
        current = windows.map { it.percent },
        onOpenUsage = { Links.open(ctx, com.jack.ai.data.integrations.ChatGptAuth.USAGE_URL) },
        onDismiss = { calibrating = false },
    ) { pcts -> usage.calibrate(pcts); refresh++; calibrating = false }
}

/** 美元金额，后面带约合人民币。 */
private fun usdText(usd: Double): String {
    val f = if (usd < 1) "%.3f" else "%.2f"
    return "$" + String.format(java.util.Locale.US, f, usd) + "（约 ¥" + String.format(java.util.Locale.US, "%.2f", usd * com.jack.ai.data.Pricing.USD_TO_CNY) + "）"
}

/**
 * 按 OpenAI API 官方价算，最近 30 天 Kavi 用的 GPT 值多少钱，和套餐月费比一比（看会员开得划不划算）。
 * 只算 Kavi 这边的；网页版、手机 App 里用的不在内。
 */
@Composable
private fun GptValue(m: com.jack.ai.data.integrations.ChatGptUsage.Window, since: Long?, now: Long) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.primary.copy(alpha = 0.08f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("按 API 官方价算，最近 30 天值", style = MaterialTheme.typography.bodyMedium)
        Text(usdText(m.usd), style = MaterialTheme.typography.titleMedium.tabular, color = colors.primary)
        val plans = listOf("Plus" to 20, "Pro" to 200)
        Text(
            plans.joinToString("；") { (name, fee) ->
                val r = m.usd / fee
                "$name（月费 $fee 美元）的 " + if (r >= 1) String.format(java.util.Locale.US, "%.1f 倍", r) else String.format(java.util.Locale.US, "%.1f%%", r * 100)
            },
            style = MaterialTheme.typography.bodySmall.tabular, color = colors.onSurfaceVariant,
        )
        val days = since?.let { ((now - it) / 86_400_000L + 1).coerceAtMost(30) }
        val notes = buildList {
            if (days != null && days < 30) add("才记了 $days 天，按这个速度一个月约 ${usdText(m.usd * 30 / days)}")
            if (m.unpriced > 0) add("${m.unpriced} 次没算进去（旧版本记的，或者模型不在价目表里）")
            add("价格按 OpenAI API 标准档，命中缓存的输入按缓存价；只算 Kavi 这边用的")
        }
        Text(notes.joinToString("。") + "。", style = MaterialTheme.typography.labelSmall, color = colors.outline)
    }
}

/** GPT 的请求没命中：Kavi 发的和上一步接不接得上（见 ResponsesApi.verdict）。 */
private fun gptMissReason(v: String): String? {
    val p = v.removePrefix("gpt:").split(':')
    return when (p[0]) {
        "first" -> "这个对话的第一次请求"
        "ok" -> "和上一步完全接得上（Kavi 没改动前面的内容）：是 ChatGPT 那边没命中（缓存还没写好，或者分到了别的服务器）"
        "model" -> "这一步换了模型（每个模型的缓存各管各的），从开头起都没命中；接着用同一个模型就会命中"
        "head" -> "工具说明、系统提示或思考强度和上一步不一样（比如刚打开了一个技能），从开头起都没命中"
        "ws" -> "常连接：只发了新内容，前面的对话留在 ChatGPT 那边；这里没命中是 ChatGPT 没算缓存"
        "wslost" -> "和 ChatGPT 的常连接" + when (p.getOrNull(1)) {
            "token" -> "因为登录令牌到期换了"; "age" -> "连满了一小时"; "stopped" -> "因为上一步被停下或出错"
            else -> "被断开了（切到后台、网络切换或手机省电）"
        } + "，重新连上后只能从头发一遍，这一次没命中；接下来的会接着命中"
        "edit" -> "Kavi 改动了上一步发过的第 ${p.getOrNull(1)} 项（共 ${p.getOrNull(2)} 项，多半是压缩了旧的工具结果），从那里往后没命中"
        else -> null
    }
}

/** 校准：照 ChatGPT 用量页上「5 小时」「每周」已用的百分比填进来。 */
@Composable
private fun ChatGptCalibrateDialog(current: List<Double?>, onOpenUsage: () -> Unit, onDismiss: () -> Unit, onSave: (List<Double?>) -> Unit) {
    fun fmt(d: Double?) = d?.let { String.format(java.util.Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') }.orEmpty()
    var five by remember { mutableStateOf(fmt(current.getOrNull(0))) }
    var week by remember { mutableStateOf(fmt(current.getOrNull(1))) }
    fun parse(s: String) = s.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it > 0 && it <= 100 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校准百分比") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("打开 ChatGPT 的用量页，把「5 小时」和「每周」已用的百分比填进来。Kavi 按现在记的用量算出 100% 大概是多少，之后自己估。" +
                    "最好在主要用 Kavi、而且已经用掉一些的时候校准，越多越准。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenUsage) { Text("打开用量页") }
                androidx.compose.material3.OutlinedTextField(five, { five = it }, label = { Text("5 小时已用（%）") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                androidx.compose.material3.OutlinedTextField(week, { week = it }, label = { Text("每周已用（%）") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(enabled = parse(five) != null || parse(week) != null, onClick = { onSave(listOf(parse(five), parse(week))) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}


/** 请求从哪来：1 对话，2 后台（夜间整理、复核、定时任务、工作流……），3 子 agent。 */
private fun requestKind(label: String): Int = when {
    label.startsWith("子 agent") -> 3
    label.isBlank() || label.startsWith("后台") || label.startsWith("任务") || "交付前复核" in label -> 2
    else -> 1
}

/** 细圆头的进度条：轨道淡，进度为 0 时什么都不画（M3 默认会在末端留一个孤零零的小点）。 */
@Composable
internal fun SoftBar(fraction: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        color = color,
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
