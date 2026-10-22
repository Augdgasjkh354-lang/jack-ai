package com.jack.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.data.QrImage
import com.jack.ai.data.Settings
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的「Jack 专用代理」：扫码 / 选二维码图片 / 粘贴订阅或节点链接导入，开关、选节点、测试、更新订阅。
 * 订阅地址和节点只存在手机上，这里也不显示完整地址。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProxySection(vm: ChatViewModel, s: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    fun run(block: suspend () -> String) {
        busy = true
        status = null
        scope.launch {
            status = block()
            busy = false
        }
    }
    fun import(text: String) = run {
        vm.importProxy(text).fold({ n -> "导入了 $n 个节点。" + vm.testProxy() }, { "导入失败：${it.message}" })
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { import(it) } }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { QrImage.decode(ctx, uri) }
            if (text == null) status = "这张图里没认出二维码" else import(text)
        }
    }

    Text(
        "只给 Jack 用，不影响手机上别的 App，也不用开系统 VPN。DeepSeek、12306、高德等国内服务始终直连，国外的走代理；" +
            "连接器可以在它的设置里单独指定。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    if (s.proxyNodes.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (s.proxyEnabled) "已开启" else "已关闭", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${s.proxyNodes.size} 个节点" + if (s.proxySub.isNotBlank()) " · 来自订阅" else "",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            Switch(s.proxyEnabled, { on -> vm.updateSettings { it.copy(proxyEnabled = on) } })
        }
        Box {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text("节点：" + s.proxyNode.ifBlank { "自动选最快的" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                DropdownMenuItem(text = { Text("自动选最快的") }, onClick = { picking = false; vm.updateSettings { it.copy(proxyNode = "") } })
                s.proxyNodes.forEach { n ->
                    DropdownMenuItem(text = { Text(n.name + "  ·  " + n.type) }, onClick = {
                        picking = false
                        vm.updateSettings { it.copy(proxyNode = n.name) }
                    })
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            scan.launch(ScanOptions().setPrompt("扫订阅或节点的二维码").setBeepEnabled(false).setOrientationLocked(false)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE))
        }) { Text("扫码导入") }
        OutlinedButton(enabled = !busy, onClick = { image.launch("image/*") }) { Text("从图片导入") }
        OutlinedButton(enabled = !busy, onClick = { pasting = true }) { Text("粘贴链接") }
        if (s.proxyNodes.isNotEmpty()) {
            OutlinedButton(enabled = !busy && s.proxyEnabled, onClick = { run { vm.testProxy() } }) { Text("测试") }
            if (s.proxySub.isNotBlank()) OutlinedButton(enabled = !busy, onClick = {
                run { vm.refreshProxy().fold({ "已更新，$it 个节点" }, { "更新失败：${it.message}" }) }
            }) { Text("更新订阅") }
            TextButton(enabled = !busy, onClick = { vm.clearProxy(); status = "已删除节点，代理已关闭" }) {
                Text("删除", color = colors.error)
            }
        }
    }
    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if ("失败" in it || "不通" in it || "没" in it) colors.error else colors.onSurfaceVariant) }

    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("粘贴订阅或节点") },
            text = {
                OutlinedTextField(text, { text = it }, placeholder = { Text("https://… 订阅地址，或 ss:// vmess:// 等链接，可以多行") },
                    minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { pasting = false; import(text) }) { Text("导入") } },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("取消") } },
        )
    }
}
