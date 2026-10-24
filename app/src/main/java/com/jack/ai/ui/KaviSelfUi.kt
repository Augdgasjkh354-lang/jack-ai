package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jack.ai.data.KaviSelf
import com.jack.ai.data.SelfEdit

/** 「记忆与梦」里的 Kavi 的「我是谁」：现在每段是什么、改过哪些（谁批准的、为什么），每一次都能恢复。 */
@Composable
fun KaviSelfCard() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<SelfEdit?>(null) }
    var resetting by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val fmt = remember { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA) }
    KaviSelf.ensure(ctx)
    @Suppress("UNUSED_EXPRESSION") version
    val history = KaviSelf.history().asReversed()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kavi 的「我是谁」", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "收起" else "看全文") }
            }
            Text(KaviSelf.FIXED + "（这句改不了）", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (showAll) KaviSelf.SECTIONS.forEach { s ->
                Text(
                    "${s.title}${if (KaviSelf.isEdited(s.key)) "（Kavi 改过）" else ""}：${KaviSelf.current(s.key).ifBlank { "（没定，按它自己的样子说；你想让它怎么说，跟它说一声就行）" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (KaviSelf.isEdited(s.key)) colors.primary else colors.onSurface,
                )
            }
            Text(
                if (history.isEmpty()) "还没改过。Kavi 改自己时会在聊天里告诉你，这里也会留下每一版。" else "改过 ${history.size} 次（最新的在上）：",
                style = MaterialTheme.typography.labelMedium, color = colors.outline, modifier = Modifier.padding(top = 4.dp),
            )
            history.take(if (showAll) 30 else 3).forEach { e ->
                Column(Modifier.padding(top = 4.dp)) {
                    val how = when (e.how) { "direct" -> "你要求的"; "confirmed" -> "你点了同意"; else -> "恢复" }
                    Text("${fmt.format(java.util.Date(e.at))} · ${KaviSelf.title(e.section)} · $how", style = MaterialTheme.typography.labelMedium)
                    if (e.reason.isNotBlank()) Text("理由：${e.reason}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("原来：${e.old}", style = MaterialTheme.typography.bodySmall, color = colors.outline)
                    Text("改成：${e.new}", style = MaterialTheme.typography.bodySmall)
                    if (KaviSelf.SECTIONS.any { it.key == e.section }) TextButton(onClick = { restoring = e }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("恢复到这次之前", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (KaviSelf.SECTIONS.any { KaviSelf.isEdited(it.key) }) {
                TextButton(onClick = { resetting = true }) { Text("全部恢复默认", color = colors.error) }
            }
        }
    }
    restoring?.let { e ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("恢复「${KaviSelf.title(e.section)}」？") },
            text = { Text("现在：${KaviSelf.current(e.section)}\n\n恢复成：${e.old}") },
            confirmButton = { TextButton(onClick = { runCatching { KaviSelf.restore(ctx, e) }; restoring = null; version++ }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("取消") } },
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text("全部恢复默认？") },
            text = { Text("Kavi 改过的段都会变回安装包里的默认内容，修改记录会保留。") },
            confirmButton = { TextButton(onClick = { KaviSelf.resetAll(ctx); resetting = false; version++ }) { Text("恢复默认", color = colors.error) } },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("取消") } },
        )
    }
}
