package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Settings
import com.jack.ai.data.Updater
import com.jack.ai.tools.ToolSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置页的「版本更新」：当前版本、自动检查开关、手动检查、下好了就能装。 */
@Composable
fun UpdateSection(vm: ChatViewModel, s: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(runCatching { Updater.ready(ctx) }.getOrNull()) }

    fun set(change: (Settings) -> Settings) {
        vm.updateSettings(change)
        runCatching { Updater.schedule(ctx.applicationContext, change(s)) }
    }

    val checked = Updater.checkedAt(ctx).takeIf { it > 0 }?.let { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) }
    Text("当前版本 v${Updater.installedName(ctx)}" + (checked?.let { "，上次检查 $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("自动检查更新", style = MaterialTheme.typography.bodyMedium)
            Text("每 6 小时查一次，有新版本就下载好（约 80MB）并发通知，点通知安装", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(s.autoUpdate, { on -> set { it.copy(autoUpdate = on) } })
    }
    if (s.autoUpdate) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("只在 Wi-Fi 下", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(s.updateWifiOnly, { on -> set { it.copy(updateWifiOnly = on) } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; status = "正在检查，有新版本会直接下载（约 80MB）…"
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    try {
                        when (val r = Updater.checkAndDownload(ctx.applicationContext, s.githubToken)) {
                            Updater.Result.UpToDate -> "已经是最新版本"
                            is Updater.Result.Downloaded -> { ready = r.ready; "新版本 ${r.ready.tag} 已下载好" }
                        }
                    } catch (e: Exception) {
                        "检查失败：" + ToolSession.errorText(e)
                    }
                }
                busy = false
            }
        }) { Text(if (busy) "检查中…" else "检查更新") }
        ready?.let { r ->
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Updater.installIntent(ctx, r.file)) }
                    .onFailure { status = "打不开安装界面：${it.message}" }
            }) { Text("安装 ${r.tag}") }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("检查失败")) colors.error else colors.onSurfaceVariant) }
    ready?.notes?.takeIf { it.isNotBlank() }?.let { Text("这次更新：\n$it", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    if (ready != null) Text("第一次安装时，系统会让你允许 Jack「安装未知应用」，打开一次就行。覆盖安装，聊天记录不会丢。",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}
