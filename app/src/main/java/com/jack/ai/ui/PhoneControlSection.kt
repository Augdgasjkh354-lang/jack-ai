package com.jack.ai.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jack.ai.ChatViewModel
import com.jack.ai.a11y.KaviAccessibility
import com.jack.ai.a11y.PhoneGuard
import com.jack.ai.data.Settings

/**
 * 设置页的「操作其他 App」：无障碍开没开（去系统里打开，安卓 13 以上先允许受限制的设置），
 * 勾 Kavi 能操作的 App（涉及钱的、系统设置勾不了）。
 */
@Composable
fun PhoneControlSection(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // 从系统设置回来时重新看一眼开没开
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val on = remember(tick) { KaviAccessibility.enabled(ctx) }
    var picking by remember { mutableStateOf(false) }
    val labels = remember(settings.phoneApps) {
        settings.phoneApps.map { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("无障碍服务")
                Text(
                    when {
                        on && KaviAccessibility.instance != null -> "开着"
                        on -> "开着，正在连上"
                        else -> "没开：Kavi 碰不到别的 App"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) colors.onSurfaceVariant else colors.error,
                )
            }
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text(if (on) "去关掉" else "去打开")
            }
        }
        if (!on) {
            Text(
                "在系统的无障碍设置里找到「已下载的应用 / 已安装的服务」→「Kavi 操作其他 App」，打开。" +
                    if (Build.VERSION.SDK_INT >= 33) "\n安卓 13 以上，从网上装的 App 默认不让开无障碍（开关是灰的）：先点下面「应用信息」，右上角 ⋮ →「允许受限制的设置」，再回来打开。" else "",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("应用信息") }
        }

        Text(
            if (settings.phoneApps.isEmpty()) "还没勾 App：Kavi 只能操作你勾过的。"
            else "能操作：" + labels.joinToString("、"),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (settings.phoneApps.isEmpty()) "选 App" else "选 App（${settings.phoneApps.size} 个）")
        }
        Text(
            "支付宝、银行、证券、钱包和付款页面一律不碰；发送、删除、提交这类操作先问你（开着完全授权时直接做、事后告诉你）；密码框不读不填。" +
                "操作时屏幕上方有一条「Kavi 正在操作 · 停止」，随时能停。有的手机会自己关掉无障碍，用不了时回来看一眼。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
    }

    if (picking) AppPicker(
        "Kavi 能操作的 App",
        settings.phoneApps.toSet(),
        blocked = { pkg, label -> PhoneGuard.blocked(pkg, label, ctx.packageName) },
        onDismiss = { picking = false },
    ) { chosen ->
        picking = false
        vm.updateSettings { it.copy(phoneApps = chosen.sorted()) }
    }
}

/**
 * 设置页的「问这一屏」：悬浮球（要开无障碍）和默认数字助理（长按电源键叫出来，不用开无障碍）。
 * 叫出来时把当前屏幕的字和截图带进新对话；涉及钱的 App 不读。
 */
@Composable
fun AskScreenSection(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val a11y = KaviAccessibility.enabled(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("悬浮球")
                Text(
                    if (a11y) "屏幕边上一个小球，点一下就带上当前屏幕问 Kavi；按住能拖到别处" else "要先打开上面「操作其他 App」里的无障碍服务",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = settings.floatBall, enabled = a11y || settings.floatBall,
                onCheckedChange = { on -> vm.updateSettings { it.copy(floatBall = on) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("设成默认数字助理")
                Text("在「默认应用 → 数字助理应用」里选 Kavi，并打开「使用屏幕上的文字」「使用屏幕截图」。之后长按电源键（或各家手机的助理手势）就能叫出 Kavi 问这一屏",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(SysSettings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { runCatching { ctx.startActivity(Intent(SysSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }) { Text("去设置") }
        }
        Text("这是你自己叫的，所以任何 App 都能读（不限于上面勾的），涉及钱的 App 不读。屏幕的字和截图只放进这个新对话。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
