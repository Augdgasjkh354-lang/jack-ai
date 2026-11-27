package com.jack.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jack.ai.data.phone.BackgroundRun

/** 后台运行现在放没放行（省电限制那一关）；回到页面时重新查。 */
@Composable
fun rememberBackgroundUnrestricted(): Boolean {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(BackgroundRun.unrestricted(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        ok = BackgroundRun.unrestricted(ctx)
        onPauseOrDispose { }
    }
    return ok
}

/**
 * 设置 → 后台运行：两行。上面是系统省电限制，能直接申请放行；
 * 下面是这台手机自己的自启动和后台管理，查不到开没开，只能带过去并写清楚怎么点。
 */
@Composable
fun BackgroundRunSection() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val ok = rememberBackgroundUnrestricted()
    val vendor = remember { BackgroundRun.vendor() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("不受省电限制")
            Text(if (ok) "已放行，系统不会因为省电掐掉 Kavi" else "没放行：锁屏一会儿后，定时任务、后台操作手机可能被系统停掉",
                style = MaterialTheme.typography.bodySmall, color = if (ok) colors.onSurfaceVariant else colors.error)
        }
        if (!ok) OutlinedButton(onClick = { BackgroundRun.askUnrestricted(ctx) }, modifier = Modifier.padding(start = 12.dp)) { Text("放行") }
    }
    HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (vendor == BackgroundRun.Vendor.OTHER) "自启动和后台" else "${vendor.label}的自启动和后台")
            Text(vendor.steps, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        OutlinedButton(onClick = {
            if (!BackgroundRun.openVendorPage(ctx)) toast(ctx, "没找到专门的设置页，在 App 信息里找「电池」或「后台」")
        }, modifier = Modifier.padding(start = 12.dp)) { Text("去设置") }
    }
}

private fun toast(ctx: android.content.Context, text: String) =
    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
