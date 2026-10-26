package com.jack.ai.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.jack.ai.ChatViewModel
import com.jack.ai.data.Settings
import com.jack.ai.vpn.KaviVpnService
import com.jack.ai.vpn.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 手机上一个能打开的 App。 */
private data class PhoneApp(val pkg: String, val label: String)

/**
 * 「Kavi 专用代理」里的按 App 分流：选几个 App，它们也走代理（系统 VPN，只管它们，状态栏会有钥匙图标）；
 * 没选的照常用手机网络。第一次打开要用户在系统弹窗里同意建 VPN。
 */
@Composable
fun ProxyAppsSection(vm: ChatViewModel, s: Settings) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    fun turnOn() {
        vm.updateSettings { it.copy(proxyAppsOn = true) }
        KaviVpnService.start(ctx)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) turnOn()
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("让这些 App 也走代理", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        !TProxyService.available -> "这一版没带上分流组件（或系统低于 Android 10），暂时用不了"
                        KaviVpnService.state.isNotBlank() -> KaviVpnService.state
                        s.proxyApps.isEmpty() -> "还没选 App"
                        else -> "选了 ${s.proxyApps.size} 个"
                    },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            Switch(
                checked = s.proxyAppsOn && KaviVpnService.running,
                enabled = TProxyService.available && s.proxyEnabled && s.proxyApps.isNotEmpty(),
                onCheckedChange = { on ->
                    if (!on) {
                        vm.updateSettings { it.copy(proxyAppsOn = false) }
                        KaviVpnService.stop(ctx)
                    } else {
                        val ask = KaviVpnService.consent(ctx)
                        if (ask == null) turnOn() else runCatching { consent.launch(ask) }
                    }
                },
            )
        }
        OutlinedButton(onClick = { picking = true }, enabled = TProxyService.available, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.proxyApps.isEmpty()) "选 App" else "选 App（${s.proxyApps.size} 个）")
        }
        Text("开着时状态栏会有钥匙图标；同一时间只能开一个 VPN。没选的 App（比如抖音）照常用手机网络。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    if (picking) AppPicker(s.proxyApps.toSet(), onDismiss = { picking = false }) { chosen ->
        picking = false
        vm.updateSettings { it.copy(proxyApps = chosen.sorted()) }
        // 正在跑：重启一下，新的名单才生效
        if (KaviVpnService.running) {
            KaviVpnService.stop(ctx)
            if (chosen.isNotEmpty()) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ KaviVpnService.start(ctx) }, 1500)
            else vm.updateSettings { it.copy(proxyAppsOn = false) }
        }
    }
}

@Composable
private fun AppPicker(selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<PhoneApp>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                // 选过的排前面，其余按名字
                .sortedWith(compareByDescending<PhoneApp> { it.pkg in selected }.thenBy { it.label })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("走代理的 App") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜 App 名字") }, singleLine = true)
                val list = apps
                if (list == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list.filter { query.isBlank() || it.label.contains(query.trim(), true) || it.pkg.contains(query.trim(), true) }, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (a.pkg in chosen) chosen - a.pkg else chosen + a.pkg }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(a.pkg)
                            Spacer(Modifier.size(10.dp))
                            Text(a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(a.pkg in chosen, { on -> chosen = if (on) chosen + a.pkg else chosen - a.pkg })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("好（${chosen.size}）") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(32.dp)) { icon?.let { Image(it, null, Modifier.size(32.dp)) } }
}
