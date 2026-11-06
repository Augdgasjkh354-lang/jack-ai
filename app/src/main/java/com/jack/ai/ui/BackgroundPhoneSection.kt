package com.jack.ai.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.a11y.BackgroundPhone
import com.jack.ai.a11y.PhonePairingReceiver
import com.jack.ai.data.Settings
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingCode
import kotlinx.coroutines.launch

@Composable
fun BackgroundPhoneSection(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var license by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    if (Build.VERSION.SDK_INT < 31) {
        Text("后台屏幕需要安卓 12 以上，当前仍使用主屏操作。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val services = remember { PhoneServices.of(ctx) }
    val manager = services.shellManager
    val engine = manager.engine
    val status by engine.status.collectAsState()
    val current by BackgroundPhone.current.collectAsState()
    fun beginPairing() {
        manager.start()
        engine.startDiscovery()
        PhonePairingReceiver.show(ctx)
        val opened = DeveloperSettings.wirelessDebuggingPage().any { intent ->
            runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        if (!opened) manual = true
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginPairing() else manual = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("后台操作 App")
                Text("在独立虚拟屏幕里操作，你可以继续使用手机", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.phoneBackground, onCheckedChange = { on ->
                vm.updateSettings { it.copy(phoneBackground = on) }
                if (on) manager.start() else BackgroundPhone.current.value?.stop()
            })
        }
        if (settings.phoneBackground) {
            Text(when (val s = status) {
                BuiltinShellStatus.NotPaired -> "还没配对：开启无线调试后配对一次"
                BuiltinShellStatus.WirelessDebuggingOff -> "无线调试已关闭，请在开发者选项里打开"
                BuiltinShellStatus.Connecting -> "正在配对或连接助手…"
                BuiltinShellStatus.Running -> "后台手机助手已连接"
                is BuiltinShellStatus.Failed -> "连接未完成（${s.reason}），可以重新连接或重新配对"
            }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else beginPairing()
                }) { Text("无线调试配对") }
                OutlinedButton(onClick = { manager.start(); scope.launch { engine.retry() } }) { Text("重新连接") }
            }
            Text("在开发者选项打开无线调试，点「使用配对码配对设备」，保持窗口打开，在通知栏填六位码。不需要 root。", style = MaterialTheme.typography.bodySmall)
            // 已 root 的手机：经 su 起助手，不用配无线调试，重启后自己恢复（找得到 su 才显示）
            val hasSu by androidx.compose.runtime.produceState(false) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { manager.suPresent() } }
            if (hasSu) {
                var rootOn by remember { mutableStateOf(manager.rootStart()) }
                var asking by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("用 root 启动")
                        Text(if (asking) "正在问 root 授权，请在弹出的授权框里允许…" else "这台手机已 root：不用无线调试配对，重启后自己恢复。打开时会问一次 root 授权。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = rootOn, enabled = !asking, onCheckedChange = { on ->
                        asking = on
                        scope.launch {
                            rootOn = manager.setRootStart(on)
                            asking = false
                            if (on && !rootOn) android.widget.Toast.makeText(ctx, "没拿到 root 授权（拒绝了、没回应，或切不到 shell 身份）", android.widget.Toast.LENGTH_LONG).show()
                        }
                    })
                }
            }
            TextButton(onClick = { manual = true }) { Text("手动填配对码 / 端口") }
            Text("首次配对可分屏打开系统设置和 Kavi；配对端口与无线调试首页的连接端口不同。", style = MaterialTheme.typography.bodySmall)
            if (current != null) OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, BackgroundPhoneActivity::class.java)) }) { Text("查看正在运行的后台屏幕") }
            TextButton(onClick = { BackgroundPhone.current.value?.stop(); scope.launch { engine.unpair() } }) { Text("解除配对") }
        }
        Text("后台屏幕功能来自 Mika · Copyright 2026 wangzan101 · Apache-2.0", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { license = true }) { Text("许可与署名") }
    }
    if (manual) AlertDialog(onDismissRequest = { manual = false }, title = { Text("无线调试配对") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("保持系统配对码窗口打开，可用分屏填写。端口留空时自动发现。")
            OutlinedTextField(code, { code = it }, label = { Text("六位配对码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(port, { port = it }, label = { Text("配对端口（可留空）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }, confirmButton = { TextButton(enabled = PairingCode.normalize(code) != null && (port.isBlank() || PairingCode.port(port) != null), onClick = {
        val value = code; val number = PairingCode.port(port)
        manual = false; code = ""; manager.start()
        scope.launch { engine.startPairing(value, number) }
    }) { Text("配对") } }, dismissButton = { TextButton(onClick = { manual = false }) { Text("取消") } })
    if (license) AlertDialog(onDismissRequest = { license = false }, title = { Text("Mika 开源许可") }, text = {
        val content = remember { "Mika\nCopyright 2026 wangzan101\nhttps://github.com/wangzan101/Mika\n\n" + ctx.assets.open("licenses/mika/LICENSE").bufferedReader().use { it.readText() } + "\n\n" + ctx.assets.open("licenses/mika/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() } }
        Text(content, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { license = false }) { Text("关闭") } })
}
