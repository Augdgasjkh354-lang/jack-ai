package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Nudge
import com.jack.ai.data.Proactive
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「记忆与梦」页上的「Kavi 主动找你」：开关、现在回看一次、它说过和忍住没说的。 */
@Composable
fun NudgeCard(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val log by produceState(emptyList<Nudge>(), version) { value = withContext(Dispatchers.IO) { Store(ctx).loadNudges().asReversed() } }
    val f = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kavi 主动找你", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(settings.proactive, { on -> vm.updateSettings { it.copy(proactive = on) } })
            }
            Text("每天早上、傍晚各回看一次，有值得打扰的事才推送：一天最多 ${Proactive.MAX_PER_DAY} 次，${Proactive.QUIET_FROM} 点到 ${Proactive.QUIET_UNTIL} 点不找，同一件事不说两遍。通知上点「别为这种事找我」，这一类以后就不说了。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Proactive.check(ctx.applicationContext, force = true) } }
                        vm.mergeInbox()
                        checking = false
                        version++
                    }
                }) { Text("现在回看一次") }
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            val shown = if (showAll) log.take(30) else log.take(4)
            shown.forEach { n ->
                val head = f.format(Date(n.at)) + if (n.kind.isNotBlank()) "［${n.kind}］" else ""
                val body = when {
                    n.sent -> "说了：${n.text}" + when (n.feedback) { "useful" -> "（你点了有用）"; "mute" -> "（你说别为这种事找你）"; else -> "" }
                    n.blocked.isNotBlank() -> "没说（${n.blocked}）" + if (n.text.isNotBlank()) "：${n.text}" else ""
                    else -> "忍住了：${n.why.ifBlank { "没有值得打扰你的事" }}"
                }
                Text("$head $body", style = MaterialTheme.typography.bodySmall,
                    color = if (n.sent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (log.size > 4) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "收起" else "看全部（${log.size}）") }
        }
    }
}
