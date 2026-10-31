package com.jack.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Switch
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

/** 缓存命中率；服务都没返回命中数时是 null。 */
private fun hitRate(l: List<UsageDay>): String {
    val known = l.filter { it.hitKnown || it.cacheHit > 0 }
    val prompt = known.sumOf { it.prompt }
    if (known.isEmpty() || prompt == 0L) return "未提供"
    return "${known.sumOf { it.cacheHit } * 100 / prompt}%"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    val usage by produceState<List<UsageDay>?>(null) { value = vm.loadUsage() }
    var editBudget by remember { mutableStateOf(false) }
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
                            Text(yuan(l.money), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                            Text(tokens(l.total) + " token", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---- 浏览器实测 ----
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val browserStats by androidx.compose.runtime.produceState("") {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.jack.ai.data.BrowserStats.summary(com.jack.ai.data.BrowserStats.load(appCtx))
                }
            }
            if (browserStats.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("浏览器实测成功率", style = MaterialTheme.typography.titleSmall)
                    Text(browserStats, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }

            // ---- 预算 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每月预算", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editBudget = true }) { Text(if (budget > 0) "修改" else "设置") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val wl = state.settings.workCostLimit
                        Text("Work 单次任务上限" + if (wl > 0) " ${yuan(wl)}" else "：不限",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editWorkLimit = true }) { Text("修改") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Work 交付前复核", style = MaterialTheme.typography.bodyMedium)
                            Text("做完后另请一次模型，只看结果、按验收标准查一遍，不合格打回去改。每个任务多花一次请求",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Switch(checked = state.settings.workReview, onCheckedChange = { on -> vm.updateSettings { it.copy(workReview = on) } })
                    }
                    Text("Work 开工前确认清单", style = MaterialTheme.typography.bodyMedium)
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
                        LinearProgressIndicator(
                            progress = { (spent / budget).toFloat().coerceIn(0f, 1f) },
                            color = if (spent >= budget) colors.error else colors.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("本月已花 ${yuan(spent)} / ${yuan(budget)}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                    }
                }
            }

            // ---- DeepSeek 时段 ----
            val peak = Pricing.isPeak(System.currentTimeMillis())
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (peak) "现在是 DeepSeek 高峰时段" else "现在是 DeepSeek 空闲时段（5 折）",
                        style = MaterialTheme.typography.titleSmall, color = if (peak) colors.error else colors.primary)
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
        AlertDialog(
            onDismissRequest = { editBudget = false },
            title = { Text("每月预算") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("金额（元），留空表示不设") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toDoubleOrNull() ?: 0.0
                    vm.updateSettings { it.copy(monthlyBudget = v.coerceAtLeast(0.0)) }
                    editBudget = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editBudget = false }) { Text("取消") } },
        )
    }
    if (editWorkLimit) {
        var text by remember { mutableStateOf(state.settings.workCostLimit.takeIf { it > 0 }?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { editWorkLimit = false },
            title = { Text("Work 单次任务上限") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("花到这么多先停下问你（元），留空不限") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(workCostLimit = (text.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)) }
                    editWorkLimit = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editWorkLimit = false }) { Text("取消") } },
        )
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
                    LinearProgressIndicator(
                        progress = { n.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                        trackColor = colors.surfaceVariant,
                    )
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
private fun RecentRequestsCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val list by produceState(emptyList<com.jack.ai.data.UsageRecord>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jack.ai.data.Store(ctx).recentRequests().reversed() }
    }
    if (list.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近的请求", style = MaterialTheme.typography.titleSmall)
            Text("命中越高越省钱。同一个对话里接着聊，正常应该大部分命中。", style = MaterialTheme.typography.bodySmall, color = colors.outline)
            (if (all) list else list.take(10)).forEach { r ->
                val rate = r.hit?.let { h -> if (r.prompt > 0) h * 100 / r.prompt else 0 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label.ifBlank { "后台" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(fmt.format(java.util.Date(r.at)) + " · " + modelLabel(r.model) + " · 输入 ${tokens(r.prompt.toLong())} · 输出 ${tokens(r.completion.toLong())}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    Text(rate?.let { "命中 $it%" } ?: "未返回", style = MaterialTheme.typography.labelLarge,
                        color = when { rate == null -> colors.outline; rate >= 60 -> colors.primary; else -> colors.error })
                }
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
