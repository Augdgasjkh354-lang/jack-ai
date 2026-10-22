package com.jack.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.jack.ai.api.BrowserDownload
import com.jack.ai.api.BrowserNet
import com.jack.ai.api.Net
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import kotlinx.coroutines.launch

/**
 * Jack 浏览器的完整页面：用户自己翻网页，或者看着 Jack 操作、随时接手。网页窗口和 Jack 用的是同一个（JackBrowser）。
 * 不能用来登录 Google（Google 不许在 App 内嵌的网页里登录）。
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var editing by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 进程被杀后直接回到这里时，代理设置还没读：先读一遍（顺带告诉 Net 怎么分流）
        settings = Store(applicationContext).loadSettings()
        JackBrowser.userViewing = true
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
        // 被系统回收后回来：只记网址，先按线路设好代理再打开（直接恢复网页会在代理设好之前就直连出去）
        val url = savedInstanceState?.getString(EXTRA_URL) ?: intent?.getStringExtra(EXTRA_URL)
        if (url != null) open(url)
    }

    override fun onResume() {
        super.onResume()
        JackBrowser.userViewing = true
    }

    override fun onPause() {
        super.onPause()
        JackBrowser.userViewing = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (JackBrowser.address.isNotEmpty()) outState.putString(EXTRA_URL, JackBrowser.address)
    }

    override fun onDestroy() {
        JackBrowser.userViewing = false
        JackBrowser.release(this)
        JackBrowser.clearProxyIfIdle()
        super.onDestroy()
    }

    private fun open(input: String) {
        val url = BrowserNet.normalize(input, proxiedNow())
        if (url.isEmpty()) return
        editing = url
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.EXTERNAL -> JackBrowser.external = url
            else -> lifecycleScope.launch { JackBrowser.load(this@BrowserActivity, url) }
        }
    }

    private fun reload() {
        val url = JackBrowser.address.ifEmpty { editing }
        if (url.isNotBlank()) open(url)
    }

    /** 现在打开新网页会不会走代理（决定搜索用 Google 还是必应）。 */
    private fun proxiedNow(): Boolean = JackBrowser.route != BrowserNet.DIRECT && Net.config.enabled

    private fun openExternal(url: String) {
        val intent = runCatching {
            if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                // 只许按「网页链接」的方式打开别的 App，不许网页点名启动某个内部页面
                component = null
                selector = null
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull()
        if (intent == null) { JackBrowser.error = "打不开这个链接"; return }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && BrowserNet.classify(fallback) == BrowserNet.Kind.OPEN) open(fallback)
            else JackBrowser.error = "手机上没有能打开它的 App"
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val colors = MaterialTheme.colorScheme
        val b = JackBrowser
        var menu by remember { mutableStateOf(false) }
        // 网页跳转后地址栏跟着变（用户正在改的时候除外：改完按前往才算）
        LaunchedEffect(b.address) { editing = b.address }
        BackHandler { val w = b.webView(this); if (w.canGoBack()) w.goBack() else finish() }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "关闭浏览器") }
                OutlinedTextField(
                    value = editing,
                    onValueChange = { editing = it },
                    placeholder = { Text("输入网址或要搜的内容") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open(editing) }),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("复制链接") }, enabled = b.address.isNotEmpty(), onClick = {
                            menu = false
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网址", b.address))
                            b.notice = "已复制链接"
                        })
                        DropdownMenuItem(text = { Text("用系统浏览器打开") }, enabled = b.address.isNotEmpty(), onClick = {
                            menu = false
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.address))) }
                        })
                        HorizontalDivider()
                        Text("线路", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        listOf(
                            BrowserNet.AUTO to "自动（国内直连，国外走代理）",
                            BrowserNet.PROXY to "全部走代理",
                            BrowserNet.DIRECT to "全部直连",
                        ).forEach { (key, label) ->
                            DropdownMenuItem(text = { Text((if (b.route == key) "✓ " else "    ") + label) }, onClick = {
                                menu = false
                                if (b.route != key) {
                                    b.changeRoute(key)
                                    if (b.address.isNotEmpty()) reload()
                                }
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("清除浏览数据", color = colors.error) }, onClick = { menu = false; b.clearData(); editing = "" })
                    }
                }
            }
            if (b.loading < 100) LinearProgressIndicator(progress = { b.loading / 100f }, Modifier.fillMaxWidth().height(2.dp))
            b.error?.let { msg ->
                Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                    TextButton(onClick = { reload() }) { Text("重试") }
                }
            }
            if (b.agentActive()) {
                Text("Jack 正在用这个浏览器。你可以直接接手操作，比如自己登录。",
                    Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(b.hostGen) { AndroidView(factory = { b.claim(this@BrowserActivity) }, modifier = Modifier.fillMaxSize()) }
                if (b.address.isEmpty() && b.loading >= 100) StartPage()
            }
            b.downloading?.let { msg ->
                Text(msg, Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = b.canBack, onClick = { b.webView(this@BrowserActivity).goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退") }
                IconButton(enabled = b.canForward, onClick = { b.webView(this@BrowserActivity).goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进") }
                IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "刷新") }
                Text(
                    routeLabel(),
                    Modifier.weight(1f).padding(end = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        b.external?.let { url ->
            AlertDialog(
                onDismissRequest = { b.external = null },
                title = { Text("打开别的 App？") },
                text = { Text("网页想用别的 App 打开：\n${url.take(200)}") },
                confirmButton = { TextButton(onClick = { b.external = null; openExternal(url) }) { Text("打开") } },
                dismissButton = { TextButton(onClick = { b.external = null }) { Text("不了") } },
            )
        }
        // Jack 在操作时网页要下载的，等 Jack 用 download 确认；用户自己点的才在这里问
        b.pending?.takeIf { it.byUser }?.let { p ->
            val big = p.size > BrowserDownload.ASK_ABOVE
            AlertDialog(
                onDismissRequest = { b.pending = null },
                title = { Text("下载这个文件？") },
                text = {
                    Text("「${p.name}」" + (if (p.size > 0) "，${BrowserDownload.sizeText(p.size)}" else "（网站没说多大）") +
                        "\n存到手机的「下载/JackAI」文件夹。" +
                        (if (big) "\n文件比较大，会用掉相应的代理流量。" else "") +
                        (if (p.name.endsWith(".apk", true)) "\n这是安卓安装包，只装信得过的来源。" else ""))
                },
                confirmButton = { TextButton(onClick = { b.saveToPhone(this@BrowserActivity, p) }) { Text("下载") } },
                dismissButton = { TextButton(onClick = { b.pending = null }) { Text("取消") } },
            )
        }
        b.saved?.let { f ->
            AlertDialog(
                onDismissRequest = { b.saved = null },
                title = { Text("下载好了") },
                text = { Text("「${f.name}」已存到${f.where}。") },
                confirmButton = {
                    TextButton(onClick = {
                        b.saved = null
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { b.notice = "手机上没有能打开它的 App，可以在文件管理器的「下载/JackAI」里找到它。" }
                    }) { Text("打开") }
                },
                dismissButton = { TextButton(onClick = { b.saved = null }) { Text("好") } },
            )
        }
        b.notice?.let { msg ->
            AlertDialog(
                onDismissRequest = { b.notice = null },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { b.notice = null }) { Text("好") } },
            )
        }
    }

    /** 底栏小字：当前网页实际怎么上网。 */
    private fun routeLabel(): String {
        val route = JackBrowser.route
        val host = runCatching { Uri.parse(JackBrowser.address).host }.getOrNull()
        val proxied = when (route) {
            BrowserNet.DIRECT -> false
            BrowserNet.PROXY -> true
            else -> host != null && Net.shouldProxy(host)
        }
        val mode = when (route) { BrowserNet.PROXY -> "全部走代理"; BrowserNet.DIRECT -> "全部直连"; else -> "自动" }
        return if (host == null) "线路：$mode" + if (route != BrowserNet.DIRECT && !Net.config.enabled) "（代理没开，现在都是直连）" else ""
        else "$mode · 这个网站" + if (proxied) "走代理" else "直连"
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun StartPage() {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Jack 浏览器", style = MaterialTheme.typography.titleLarge)
            Text(
                "开着 Jack 专用代理时，国外网站自动走代理，不用开系统 VPN，也不影响别的 App。" +
                    "Jack 也能用这个浏览器替你操作网页，你在这里登录过的网站它也能用。" +
                    "这里的登录状态和手机浏览器不共用。不能用来登录 Google 账号（Google 不允许）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Google" to "https://www.google.com", "维基百科" to "https://zh.wikipedia.org", "GitHub" to "https://github.com",
                    "YouTube" to "https://m.youtube.com", "Notion" to "https://www.notion.so").forEach { (name, url) ->
                    OutlinedButton(onClick = { open(url) }) { Text(name) }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
