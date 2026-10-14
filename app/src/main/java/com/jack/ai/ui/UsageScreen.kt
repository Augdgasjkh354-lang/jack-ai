package com.jack.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.UsageDay
import com.jack.ai.data.cost
import com.jack.ai.data.modelLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1e3)
    else -> "$n"
}

private fun money(d: Double): String = if (d < 0.01) String.format(Locale.US, "$%.4f", d) else String.format(Locale.US, "$%.2f", d)

private val List<UsageDay>.total get() = sumOf { it.prompt + it.completion }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(vm: ChatViewModel, state: UiState, onBack: () -> Unit) {
    val usage by produceState<List<UsageDay>?>(null) { value = vm.loadUsage() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用量统计") },
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
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("今天" to todayList, "本月" to monthList, "全部" to list).forEach { (label, l) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(tokens(l.total), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                            Text("约 " + money(l.sumOf { it.cost() }), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("最近 14 天（token）", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    val days = (13 downTo 0).map { back ->
                        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
                        fmt.format(c.time)
                    }
                    val values = days.map { d -> list.filter { it.date == d }.total }
                    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
                    val bar = colors.primary
                    val empty = colors.outlineVariant
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val gap = 6.dp.toPx()
                        val w = (size.width - gap * (values.size - 1)) / values.size
                        values.forEachIndexed { i, v ->
                            val h = if (v == 0L) 3.dp.toPx() else (size.height * v / max).coerceAtLeast(4.dp.toPx())
                            drawRoundRect(
                                color = if (v == 0L) empty else bar,
                                topLeft = Offset(i * (w + gap), size.height - h),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(days.first().drop(5), style = MaterialTheme.typography.labelSmall, color = colors.outline)
                        Spacer(Modifier.weight(1f))
                        Text("今天", style = MaterialTheme.typography.labelSmall, color = colors.outline)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本月按模型", style = MaterialTheme.typography.titleSmall)
                    if (monthList.isEmpty()) Text("本月还没有用量", color = colors.outline)
                    monthList.groupBy { it.model }.forEach { (m, l) ->
                        val hit = l.sumOf { it.cacheHit }
                        val prompt = l.sumOf { it.prompt }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(modelLabel(m))
                                Text(
                                    "${l.sumOf { it.requests }} 次请求 · 输入 ${tokens(prompt)}（缓存命中 ${if (prompt > 0) hit * 100 / prompt else 0}%）· 输出 ${tokens(l.sumOf { it.completion })}",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                )
                            }
                            Text(money(l.sumOf { it.cost() }), color = colors.primary)
                        }
                    }
                }
            }

            val top = state.conversations.filter { it.spentTokens > 0 }.sortedByDescending { it.spentTokens }.take(5)
            if (top.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("最费 token 的对话", style = MaterialTheme.typography.titleSmall)
                        top.forEach { c ->
                            Row {
                                Text(c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tokens(c.spentTokens), color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Text(
                "费用按 DeepSeek 参考价估算（美元）：Flash 输入 $0.14、缓存命中 $0.0028、输出 $0.28；Pro 输入 $1.74、缓存命中 $0.145、输出 $3.48，都是每百万 token。实际以 DeepSeek 账单为准。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.outline,
            )
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(56.dp)
            Spacer(Modifier.height(16.dp))
            Text("Jack AI 已上锁", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) {
                Icon(AppIcons.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("解锁")
            }
        }
    }
}
