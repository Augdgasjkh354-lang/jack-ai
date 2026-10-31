package com.jack.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.RemoteService
import com.jack.ai.api.sharedHttp
import com.jack.ai.data.RemoteClient
import com.jack.ai.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 部署说明：交给 ChatGPT 站点的话（代码在公开仓库里，ChatGPT 能直接读）。 */
private const val DEPLOY_PROMPT = """请用「站点」功能帮我部署一个网站，代码在这个公开仓库里，原样使用、不要改逻辑：
https://github.com/Augdgasjkh354-lang/jack-ai/tree/main/remote-site
（主文件是 remote-site/src/worker.js，Cloudflare Worker 的 ES module 写法，export default { fetch }。打不开链接的话我把代码贴给你。）

需要的配置：
1. 一个 D1 数据库，绑定名必须是 DB（表会在第一次请求时自动建，不用建表）
2. 一个 R2 存储桶，绑定名必须是 FILES
3. 一个 Secret，名字是 KAVI_KEY，值我自己在站点设置里填（不要写进代码）
4. 访问权限：互联网上的任何人都能访问（网站自己用 KAVI_KEY 登录把关）

部署好以后告诉我网站地址。"""

/**
 * 设置页的「远程连接」：在 iPhone、电脑上通过自己的 ChatGPT 站点用手机上的 Kavi。
 * 填站点地址、生成密钥（复制到站点的 Secrets），打开后常驻通知里一直连着。
 */
@Composable
fun RemoteSection(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val status by RemoteService.status.collectAsState()
    var url by remember { mutableStateOf(settings.remoteUrl) }
    var showKey by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    var confirmNewKey by remember { mutableStateOf(false) }
    fun copy(label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "已复制$label", Toast.LENGTH_SHORT).show()
    }
    fun update(transform: (Settings) -> Settings) {
        vm.updateSettings(transform)
        // 设置存好以后再按新设置开关服务
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ RemoteService.sync(ctx) }, 300)
    }
    val ready = settings.remoteUrl.isNotBlank() && settings.remoteKey.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("远程连接")
                Text(if (settings.remoteOn) status else if (ready) "关着" else "先填站点地址、生成密钥",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = settings.remoteOn, enabled = ready || settings.remoteOn, onCheckedChange = { on -> update { it.copy(remoteOn = on) } })
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim(); vm.updateSettings { s -> s.copy(remoteUrl = url) } },
            label = { Text("站点地址") }, placeholder = { Text("https://….chatgpt.site") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (settings.remoteKey.isBlank()) {
            OutlinedButton(onClick = { vm.updateSettings { it.copy(remoteKey = RemoteClient.newKey()) } }) { Text("生成密钥") }
        } else {
            Text("密钥：" + if (showKey) settings.remoteKey else "••••••••" + settings.remoteKey.takeLast(4), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy("密钥", settings.remoteKey) }) { Text("复制") }
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "藏起来" else "显示") }
                TextButton(onClick = { confirmNewKey = true }) { Text("换一个") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = ready, onClick = {
                test = "连接中…"
                scope.launch {
                    test = withContext(Dispatchers.IO) {
                        runCatching { RemoteClient(settings.remoteUrl, settings.remoteKey, sharedHttp).ping() }
                            .fold({ v -> if (v >= 1) "连上了（站点第 $v 版）" else "连上了，但站点代码太旧，重新部署一下" }, { "没连上：${it.message?.take(100)}" })
                    }
                }
            }) { Text("测试连接") }
            test?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("远程也能操作其他 App")
                Text("开着时，在别的设备上也能让 Kavi 操作手机上勾过的 App（手机要亮屏解锁）", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = settings.remotePhone, onCheckedChange = { on -> vm.updateSettings { it.copy(remotePhone = on) } })
        }
        OutlinedButton(onClick = { copy("部署说明", DEPLOY_PROMPT) }, modifier = Modifier.fillMaxWidth()) { Text("复制给 ChatGPT 的部署说明") }
        Text(
            "怎么用：① 点上面复制部署说明，发给 ChatGPT（Plus 以上，用「站点」功能）；② 部署好后在站点设置的 Secrets 里加 KAVI_KEY，值是这里的密钥；" +
                "③ 把站点地址填到上面，点「测试连接」，再打开「远程连接」；④ 在 iPhone 的 Safari 打开站点、输入密钥，「添加到主屏幕」。\n" +
                "开着时手机常驻一条通知、保持联网，比平时耗电；国外站点会自动走 Kavi 专用代理。别的设备上要确认的事就在那边网页上问。密钥不进备份；换了密钥，别的设备要重新登录。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
    }

    if (confirmNewKey) AlertDialog(
        onDismissRequest = { confirmNewKey = false },
        title = { Text("换一个密钥？") },
        text = { Text("换了以后要把新密钥填进站点的 KAVI_KEY 并重新部署，iPhone 等设备也要重新登录。") },
        confirmButton = { TextButton(onClick = { update { it.copy(remoteKey = RemoteClient.newKey()) }; confirmNewKey = false }) { Text("换") } },
        dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text("取消") } },
    )
}
