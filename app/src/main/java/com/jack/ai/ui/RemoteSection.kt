package com.jack.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.RemoteService
import com.jack.ai.api.sharedHttp
import com.jack.ai.data.ClaimResult
import com.jack.ai.data.QrImage
import com.jack.ai.data.RemoteClient
import com.jack.ai.data.RemoteDevice
import com.jack.ai.data.RemoteInvite
import com.jack.ai.data.RemoteMember
import com.jack.ai.data.RemotePairing
import com.jack.ai.data.SiteInfo
import com.jack.ai.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 部署说明：交给 ChatGPT 站点的话（代码在公开仓库里，ChatGPT 能直接读）。不用填密钥：Kavi 第一次连上时自己认领站点。 */
private const val DEPLOY_PROMPT = """请用「站点」功能帮我部署一个网站，代码在这个公开仓库里，原样使用、不要改逻辑：
https://github.com/Augdgasjkh354-lang/jack-ai/tree/main/remote-site
（主文件是 remote-site/src/worker.js，Cloudflare Worker 的 ES module 写法，export default { fetch }。打不开链接的话我把代码贴给你。）

需要的配置：
1. 一个 D1 数据库，绑定名必须是 DB（表会在第一次请求时自动建，不用建表）
2. 一个 R2 存储桶，绑定名必须是 FILES
3. 不要设置 KAVI_KEY 之类的密钥：我手机上的 App 第一次连上时会自动认领这个站点（朋友的手机凭我给的邀请码加入）
4. 访问权限：互联网上的任何人都能访问（网站自己靠认领、邀请码和设备配对把关）

部署好以后告诉我网站地址。"""

/**
 * 设置页的「远程连接」：在 iPhone、电脑上通过 ChatGPT 站点用手机上的 Kavi。
 * 填站点地址、点「连接站点」：自动生成密钥并认领站点，第一台是站长；朋友的手机填站长给的邀请码加入，各用各的、互相看不到。
 * 再「配对新设备」：别的设备扫二维码或输 6 位码就能用；配过的设备能随时踢掉。站长还能邀请朋友、移出成员。
 */
@Composable
fun RemoteSection(vm: ChatViewModel, settings: Settings) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val status by RemoteService.status.collectAsState()
    var url by remember { mutableStateOf(settings.remoteUrl) }
    var showKey by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmNewKey by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<RemotePairing?>(null) }
    var devices by remember { mutableStateOf<List<RemoteDevice>?>(null) }
    var kick by remember { mutableStateOf<RemoteDevice?>(null) }
    var inviteIn by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<SiteInfo?>(null) }
    var invited by remember { mutableStateOf<RemoteInvite?>(null) }
    var members by remember { mutableStateOf<List<RemoteMember>?>(null) }
    var removing by remember { mutableStateOf<RemoteMember?>(null) }
    fun copy(label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "已复制$label", Toast.LENGTH_SHORT).show()
    }
    fun update(transform: (Settings) -> Settings) {
        vm.updateSettings(transform)
        // 设置存好以后再按新设置开关服务
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ RemoteService.sync(ctx) }, 300)
    }
    fun client(key: String = settings.remoteKey) = RemoteClient(settings.remoteUrl, key, sharedHttp)
    /** 在后台线程跑一件站点上的事，出错写进提示。 */
    fun site(label: String, work: suspend () -> String?) {
        if (busy) return
        busy = true; note = "$label…"
        scope.launch {
            note = runCatching { withContext(Dispatchers.IO) { work() } }.getOrElse { "$label 没成：${it.message?.take(120)}" }
            busy = false
        }
    }
    fun loadDevices() = site("读设备列表") { devices = client().devices(); null }
    fun loadMembers() = site("读成员列表") { members = client().members(); null }
    val ready = settings.remoteUrl.isNotBlank() && settings.remoteKey.isNotBlank()
    // 连着站点时看一下自己是不是站长（站长才有「邀请朋友」「站点成员」）
    LaunchedEffect(settings.remoteUrl, settings.remoteKey) {
        info = if (ready) withContext(Dispatchers.IO) { runCatching { client().info() }.getOrNull() } else null
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("远程连接")
                Text(if (settings.remoteOn) status else if (ready) "关着" else "先填站点地址，点「连接站点」",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = settings.remoteOn, enabled = ready || settings.remoteOn, onCheckedChange = { on -> update { it.copy(remoteOn = on) } })
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim(); vm.updateSettings { s -> s.copy(remoteUrl = url) } },
            label = { Text("站点地址") }, placeholder = { Text("https://….chatgpt.site") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = inviteIn, onValueChange = { inviteIn = it.trim() },
            label = { Text("邀请码（加入朋友的站点才要填）") }, placeholder = { Text("ABCD-EFGH") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = url.isNotBlank() && !busy, onClick = {
                // 没有密钥就先生成一把存好，再拿它去认领
                val key = settings.remoteKey.ifBlank { RemoteClient.newKey().also { k -> vm.updateSettings { it.copy(remoteKey = k) } } }
                val name = settings.userName.trim().ifBlank { android.os.Build.MODEL.orEmpty() }
                site("连接站点") {
                    val c = RemoteClient(url, key, sharedHttp)
                    when (c.claim(inviteIn, name)) {
                        ClaimResult.NEED_INVITE -> "这个站点已经有站长了：要加入，请站长在他的 Kavi 里点「邀请朋友」，把邀请码填在上面再连"
                        ClaimResult.BAD_INVITE -> "邀请码不对、用过了或过期了，请站长再发一个"
                        ClaimResult.TAKEN -> "这个站点只认一台手机，已经被别的手机认领了"
                        else -> {
                            val me = runCatching { c.info() }.getOrNull()
                            info = me
                            withContext(Dispatchers.Main) { inviteIn = ""; update { it.copy(remoteUrl = url, remoteKey = key, remoteOn = true) } }
                            (if (me?.admin == false) "加入了：你的对话和设备跟站长的分开，互相看不到。" else "连上了：你是这个站点的站长。") +
                                "下一步点「配对新设备」"
                        }
                    }
                }
            }) { Text("连接站点") }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
        }
        if (ready) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { site("发起配对") { pairing = client().startPairing(); null } }) { Text("配对新设备") }
                TextButton(enabled = !busy, onClick = { loadDevices() }) { Text(if (devices == null) "已配对的设备" else "刷新设备") }
            }
            devices?.let { list ->
                if (list.isEmpty()) Text("还没有配对的设备", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                list.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyMedium)
                            Text("最近用过：" + whenText(d.lastSeen), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        TextButton(enabled = !busy, onClick = { kick = d }) { Text("踢掉") }
                    }
                }
            }
            if (info?.admin == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { site("生成邀请码") { invited = client().invite(); null } }) { Text("邀请朋友") }
                    TextButton(enabled = !busy, onClick = { loadMembers() }) { Text(if (members == null) "站点成员" else "刷新成员") }
                }
                members?.let { list ->
                    list.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name + when { m.me -> "（我）"; m.admin -> "（站长）"; else -> "" }, style = MaterialTheme.typography.bodyMedium)
                                Text("最近在线：" + whenText(m.lastSeen), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            if (!m.me) TextButton(enabled = !busy, onClick = { removing = m }) { Text("移出") }
                        }
                    }
                }
            }
            Text("密钥：" + if (showKey) settings.remoteKey else "••••••••" + settings.remoteKey.takeLast(4), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "藏起来" else "显示") }
                TextButton(onClick = { copy("密钥", settings.remoteKey) }) { Text("复制") }
                TextButton(enabled = !busy, onClick = { confirmNewKey = true }) { Text("换一个") }
            }
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
            "怎么用：① 点上面复制部署说明，发给 ChatGPT（Plus 以上，用「站点」功能），不用填任何密钥；" +
                "② 把站点地址填到上面，点「连接站点」：Kavi 自动生成密钥、认领站点，你就是站长（部署好就马上连，免得被别人抢先）；" +
                "朋友要用：你点「邀请朋友」把邀请码发给他，他在自己的 Kavi 里填站点地址和邀请码再连，各用各的、互相看不到（消息会经过你的站点）；" +
                "③ 点「配对新设备」，在 iPhone 或电脑上扫二维码，或打开站点输 6 位码，然后「添加到主屏幕」。\n" +
                "开着时手机常驻一条通知、保持联网，比平时耗电；国外站点会自动走 Kavi 专用代理。别的设备上要确认的事就在那边网页上问。密钥不进备份。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
    }

    pairing?.let { p -> PairDialog(p, client().pairUrl(p.token), onCopy = { copy("配对链接", it) }) { pairing = null; if (devices != null) loadDevices() } }

    invited?.let { inv ->
        val text = "用我的 Kavi 远程站点：\n站点：${settings.remoteUrl}\n邀请码：${inv.code}（7 天内有效，只能用一次）\n" +
            "在 Kavi 的 设置 → 远程连接 里填上站点地址和邀请码，点「连接站点」。"
        AlertDialog(
            onDismissRequest = { invited = null },
            title = { Text("邀请朋友") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(inv.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("把邀请码和站点地址发给朋友，7 天内有效、只能用一次。他的对话和设备和你的分开，但消息会经过你的站点。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { copy("邀请", text); invited = null }) { Text("复制邀请") } },
            dismissButton = { TextButton(onClick = { invited = null }) { Text("关闭") } },
        )
    }

    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("把「${m.name}」移出站点？") },
            text = { Text("他的手机马上连不上，他在站点上的对话、文件和配过的设备一起删掉。要再用得重新邀请。") },
            confirmButton = { TextButton(onClick = {
                removing = null
                site("移出成员") { client().removeMember(m.id); members = client().members(); "已移出「${m.name}」" }
            }) { Text("移出") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }

    kick?.let { d ->
        AlertDialog(
            onDismissRequest = { kick = null },
            title = { Text("踢掉「${d.name}」？") },
            text = { Text("这台设备马上就用不了，要再用得重新配对。") },
            confirmButton = { TextButton(onClick = {
                kick = null
                site("踢掉设备") { client().revoke(d.id); devices = client().devices(); "已踢掉「${d.name}」" }
            }) { Text("踢掉") } },
            dismissButton = { TextButton(onClick = { kick = null }) { Text("取消") } },
        )
    }

    if (confirmNewKey) AlertDialog(
        onDismissRequest = { confirmNewKey = false },
        title = { Text("换一个密钥？") },
        text = { Text("站点会换成认新密钥，配过对的设备全部作废，要重新配对。") },
        confirmButton = { TextButton(onClick = {
            confirmNewKey = false
            val fresh = RemoteClient.newKey()
            site("换密钥") {
                client().rekey(fresh)
                withContext(Dispatchers.Main) { update { it.copy(remoteKey = fresh) } }
                devices = emptyList()
                "换好了，配过的设备要重新配对"
            }
        }) { Text("换") } },
        dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text("取消") } },
    )
}

/** 配对：大二维码（别的设备扫）、6 位码（打开站点输入）、倒计时；过期了要重新发起。 */
@Composable
private fun PairDialog(p: RemotePairing, link: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val qr = remember(link) { QrImage.encode(link).asImageBitmap() }
    var left by remember(p.token) { mutableStateOf(p.expires - System.currentTimeMillis() / 1000) }
    LaunchedEffect(p.token) { while (left > 0) { delay(1000); left = p.expires - System.currentTimeMillis() / 1000 } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配对新设备") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (left > 0) {
                    Image(qr, "配对二维码", Modifier.size(220.dp))
                    Text("用 iPhone 或电脑的相机扫一下；或者打开站点，输入：", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("还剩 ${left / 60}:${"%02d".format(left % 60)}，只能用一次", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text("过期了，关掉再点一次「配对新设备」。")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好了") } },
        dismissButton = { if (left > 0) TextButton(onClick = { onCopy(link) }) { Text("复制链接") } },
    )
}

/** 设备最近用过的时间：刚刚、几分钟前、几小时前，再早写日期。 */
private fun whenText(at: Long): String {
    val d = (System.currentTimeMillis() - at) / 60_000
    return when {
        at <= 0 -> "还没用过"
        d < 1 -> "刚刚"
        d < 60 -> "$d 分钟前"
        d < 24 * 60 -> "${d / 60} 小时前"
        else -> java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA).format(java.util.Date(at))
    }
}
