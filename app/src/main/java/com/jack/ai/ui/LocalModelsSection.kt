package com.jack.ai.ui

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.chat.LocalBrain
import com.jack.ai.data.LocalModel
import com.jack.ai.data.LocalModels
import com.jack.ai.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的「本地模型」：列出能下的模型（多大、占多少内存），下载（能暂停、断了接着下）、选用、测速、删除；
 * 下面两个开关：优先用本地、用它判断问题难不难。
 */
@Composable
fun LocalModelsSection(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by LocalModels.state.collectAsState()
    // 下载状态变了（下完、删了）就重新看一眼文件
    val ready = remember(downloads, settings.localModel) { LocalModels.ALL.associate { it.id to LocalModels.ready(ctx, it) } }
    val used = remember(downloads) { LocalModels.usedBytes(ctx) }
    val ramGb = remember { totalRamGb(ctx) }
    val bench = remember { mutableStateMapOf<String, String>() }
    var confirmDelete by remember { mutableStateOf<LocalModel?>(null) }
    var confirmData by remember { mutableStateOf<LocalModel?>(null) }

    fun select(id: String) {
        vm.updateSettings { it.copy(localModel = id) }
        if (id.isNotEmpty()) LocalBrain.warmUp(ctx, settings.copy(localModel = id))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("这台手机内存约 ${"%.0f".format(ramGb)} GB。模型只在这台手机上，不进备份。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.localModel.isEmpty(), onClick = { select("") })
            Text("不用本地模型")
        }

        LocalModels.ALL.forEach { m ->
            val d = downloads[m.id]
            val isReady = ready[m.id] == true
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    RadioButton(selected = settings.localModel == m.id, enabled = isReady, onClick = { select(m.id) })
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("下载 ${LocalModels.mb(m.bytes)}，用的时候占内存约 ${m.ramMb} MB",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!isReady && ramGb > 0 && ramGb < m.ramMb / 1024.0 * 12) Text("这台手机内存偏小，建议用轻量的",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    when {
                        d?.running == true -> {
                            val p = if (d.total > 0) d.done.toFloat() / d.total else 0f
                            LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("下载中 ${LocalModels.mb(d.done)} / ${LocalModels.mb(d.total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { LocalModels.pause(m) }) { Text("暂停") }
                            }
                        }
                        isReady -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(bench[m.id] ?: "已下载", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                bench[m.id] = "测速中…（第一次要先加载几秒）"
                                scope.launch {
                                    bench[m.id] = withContext(Dispatchers.IO) {
                                        runCatching { LocalModels.benchmark(ctx, m) }.fold(
                                            { (load, each) -> (if (load > 50) "加载 ${"%.1f".format(load / 1000.0)} 秒，" else "") + "每条约 $each 毫秒" },
                                            { "跑不起来：${it.message?.take(80)}" },
                                        )
                                    }
                                }
                            }) { Text("测速") }
                            TextButton(onClick = { confirmDelete = m }) { Text("删除") }
                        }
                        else -> {
                            if (d?.error?.isNotEmpty() == true) Text(d.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            val partial = remember(downloads) { LocalModels.partBytes(ctx, m) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { if (metered(ctx)) confirmData = m else LocalModels.start(ctx, m) }) {
                                    Text(if (partial > 0) "接着下载" else "下载")
                                }
                                if (partial > 0) {
                                    Text("已下 ${LocalModels.mb(partial)}", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { confirmDelete = m }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (used > 0) Text("本地模型一共占了 ${LocalModels.mb(used)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("优先用本地")
                Text(if (settings.localPrefer) "找记忆、查事实库先用本地模型，不调用硅基流动" else "配了硅基流动就先用它；没配或连不上时用本地模型",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.localPrefer, onCheckedChange = { on -> vm.updateSettings { it.copy(localPrefer = on) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("判断问题难不难")
                Text("「思考·自动」时，看着简单的问题再让本地模型看一眼，像难题就用高档思考",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.localClassify, onCheckedChange = { on -> vm.updateSettings { it.copy(localClassify = on) } })
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除 ${m.name.substringBefore('（')}？") },
            text = { Text("删掉 ${LocalModels.mb(m.bytes)} 的模型文件，以后要用得重新下载。算好的向量留着，重新下载后接着用。") },
            confirmButton = {
                TextButton(onClick = {
                    LocalModels.delete(ctx, m)
                    if (settings.localModel == m.id) vm.updateSettings { it.copy(localModel = "") }
                    bench.remove(m.id)
                    confirmDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
    confirmData?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmData = null },
            title = { Text("正在用流量") },
            text = { Text("现在没连 Wi-Fi，下载要用 ${LocalModels.mb(m.bytes)} 流量。断了可以接着下。") },
            confirmButton = { TextButton(onClick = { LocalModels.start(ctx, m); confirmData = null }) { Text("照样下载") } },
            dismissButton = { TextButton(onClick = { confirmData = null }) { Text("等连上 Wi-Fi") } },
        )
    }
}

private fun totalRamGb(ctx: Context): Double = runCatching {
    val info = ActivityManager.MemoryInfo()
    (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    info.totalMem / 1024.0 / 1024 / 1024
}.getOrDefault(0.0)

private fun metered(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
}.getOrDefault(false)
