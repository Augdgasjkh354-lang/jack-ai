package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.jack.ai.api.BrowserNet
import com.jack.ai.api.Net
import com.jack.ai.data.ProxyCore
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Jack 浏览器：自己翻网页用，可以走 Jack 专用代理，不用开系统 VPN、不影响别的 App。
 * 和小工具的网页窗口完全分开：没有 JackBridge，不能读手机文件，不碰本机和局域网地址。
 * 不能用来登录 Google（Google 不许在 App 内嵌的网页里登录）。
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var settings: Settings

    // 界面状态
    private var address by mutableStateOf("")
    private var editing by mutableStateOf("")
    private var title by mutableStateOf("")
    private var loading by mutableIntStateOf(100)
    private var canBack by mutableStateOf(false)
    private var canForward by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var route by mutableStateOf(BrowserNet.AUTO)
    private var external by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)

    /**
     * 最近给代理口令的时刻。并行的连接各要一次（实测一个页面 6 次）；
     * 10 秒内要了 30 次以上，说明口令没被接受，再给也是空转。
     */
    private val authTimes = ArrayDeque<Long>()
    /** 网页上次是按哪个代理端口设的（0 = 直连）；内核重启换了端口就要重设。 */
    private var appliedPort = -1
    private var appliedRoute = ""

    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 进程被杀后直接回到这里时，代理设置还没读：先读一遍（顺带告诉 Net 怎么分流）
        settings = Store(applicationContext).loadSettings()
        route = prefs.getString("route", BrowserNet.AUTO) ?: BrowserNet.AUTO

        web = WebView(this).apply {
            this.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = Client()
            webChromeClient = Chrome()
            setDownloadListener { url, _, _, _, _ ->
                notice = "Jack 浏览器暂时不能下载文件。可以用菜单里的「复制链接」，换别的方式下载：\n$url"
            }
        }

        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen()
                }
            }
        }
        // 被系统回收后回来：只记网址，先按线路设好代理再打开（直接恢复网页会在代理设好之前就直连出去）
        (savedInstanceState?.getString(EXTRA_URL) ?: intent?.getStringExtra(EXTRA_URL))?.let { open(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (address.isNotEmpty()) outState.putString(EXTRA_URL, address)
    }

    override fun onDestroy() {
        // 代理设置是整个 App 的网页窗口共用的：浏览器关了就撤掉，小工具照旧直连
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
        }
        web.destroy()
        super.onDestroy()
    }

    // ---------- 打开网页 ----------

    /** 地址栏输入或外面传来的网址：先按线路设好代理，再打开。 */
    private fun open(input: String) {
        val url = BrowserNet.normalize(input, proxiedNow())
        if (url.isEmpty()) return
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.OPEN -> prepare { web.loadUrl(url) }
            BrowserNet.Kind.EXTERNAL -> external = url
            BrowserNet.Kind.BLOCKED -> error = "不能打开这个地址（本机、局域网和文件地址都不开）：$url"
        }
    }

    private fun reload() = prepare { if (web.url.isNullOrEmpty()) open(editing) else web.reload() }

    /** 走代理时代理要先起来，网页窗口要先指向它；起不来就不打开，告诉用户原因。 */
    private fun prepare(then: () -> Unit) {
        error = null
        lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) { BrowserNet.plan(route) }
            when (plan) {
                is BrowserNet.Plan.Blocked -> { error = plan.reason; loading = 100 }
                BrowserNet.Plan.Direct -> apply(0, emptyList(), then)
                is BrowserNet.Plan.Proxy -> apply(plan.port, plan.bypass, then)
            }
        }
    }

    private fun apply(port: Int, bypass: List<String>, then: () -> Unit) {
        if (port == appliedPort && route == appliedRoute) { then(); return }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (port == 0) { appliedPort = 0; appliedRoute = route; then(); return }
            error = "这台手机的网页组件版本太旧，不能单独给浏览器设代理。到应用商店更新「Android System WebView」后再试。"
            return
        }
        val controller = ProxyController.getInstance()
        val done = Runnable { appliedPort = port; appliedRoute = route; then() }
        if (port == 0) {
            controller.clearProxyOverride({ runOnUiThread(it) }, done)
        } else {
            val config = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").apply {
                bypass.forEach { addBypassRule(it) }
            }.build()
            controller.setProxyOverride(config, { runOnUiThread(it) }, done)
        }
    }

    /** 现在打开新网页会不会走代理（决定搜索用 Google 还是必应）。 */
    private fun proxiedNow(): Boolean = route != BrowserNet.DIRECT && Net.config.enabled

    private fun openExternal(url: String) {
        val intent = runCatching {
            if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                // 只许按「网页链接」的方式打开别的 App，不许网页点名启动某个内部页面
                component = null
                selector = null
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull()
        if (intent == null) { error = "打不开这个链接"; return }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && BrowserNet.classify(fallback) == BrowserNet.Kind.OPEN) open(fallback)
            else error = "手机上没有能打开它的 App"
        }
    }

    private fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(this).apply { clearHttpAuthUsernamePassword(); clearFormData() }
        web.clearCache(true)
        web.clearHistory()
        web.clearFormData()
        web.loadUrl("about:blank")
        address = ""; editing = ""; title = ""
        notice = "已清除：Cookie（登录状态）、缓存、网页存储、浏览记录。"
    }

    private fun describe(code: Int, desc: String): String = when {
        "PROXY" in desc && "AUTH" in desc -> "代理不接受浏览器给的口令（$desc）。把这个提示告诉开发者。"
        "PROXY" in desc || "TUNNEL" in desc -> "连不上 Jack 专用代理（$desc）。" + (ProxyCore.lastError?.let { "代理：$it" } ?: "可以点刷新重试，或到设置里测试代理。")
        code == WebViewClient.ERROR_HOST_LOOKUP -> "找不到这个网站（$desc）。" + if (route == BrowserNet.DIRECT) "国外网站可以把线路换成「自动」或「全部走代理」。" else ""
        code == WebViewClient.ERROR_TIMEOUT || code == WebViewClient.ERROR_CONNECT ->
            "连接失败（$desc）。" + if (route == BrowserNet.DIRECT || !Net.config.enabled) "国外网站需要开 Jack 专用代理。" else "节点可能不通，到设置里测试一下代理。"
        else -> "打不开（$desc）"
    }

    // ---------- 网页回调 ----------

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (BrowserNet.classify(url)) {
                BrowserNet.Kind.OPEN -> false
                BrowserNet.Kind.EXTERNAL -> { if (request.hasGesture() || request.isForMainFrame) external = url; true }
                BrowserNet.Kind.BLOCKED -> { if (request.isForMainFrame) error = "已拦下：网页想打开本机、局域网或文件地址（$url）"; true }
            }
        }

        // 网页里的图片、脚本、请求也不许碰本机和局域网
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host ?: return null
            if (request.url.scheme in setOf("http", "https", "ws", "wss") && BrowserNet.isLocal(host)) {
                return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            if (BrowserNet.isOurProxy(host, realm) && ProxyCore.user.isNotEmpty()) {
                val now = System.currentTimeMillis()
                while (authTimes.isNotEmpty() && now - authTimes.first() > 10_000) authTimes.removeFirst()
                if (authTimes.size < 30) { authTimes.addLast(now); handler.proceed(ProxyCore.user, ProxyCore.pass); return }
                error = "浏览器没能通过 Jack 专用代理的口令验证。把这个提示告诉开发者。"
            }
            // 网站自己要的账号密码（很少见）：不支持，取消
            handler.cancel()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: SslError) {
            // 证书不对一律不继续：可能是假网站或被劫持
            handler.cancel()
            if (e.url == view.url || e.url == address) error = "这个网站的安全证书有问题，已停止打开（${e.url}）"
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
            if (request.isForMainFrame) error = describe(e.errorCode, e.description?.toString().orEmpty())
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            address = url; editing = url
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url != "about:blank") { address = url; editing = url }
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url != "about:blank") { address = url; editing = url }
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { loading = newProgress }
        override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
        // 不给网页任何手机权限（相机、麦克风、位置）
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
    }

    // ---------- 界面 ----------

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val colors = MaterialTheme.colorScheme
        var menu by remember { mutableStateOf(false) }
        BackHandler { if (web.canGoBack()) web.goBack() else finish() }
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
                        DropdownMenuItem(text = { Text("复制链接") }, enabled = address.isNotEmpty(), onClick = {
                            menu = false
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网址", address))
                            notice = "已复制链接"
                        })
                        DropdownMenuItem(text = { Text("用系统浏览器打开") }, enabled = address.isNotEmpty(), onClick = {
                            menu = false
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
                        })
                        HorizontalDivider()
                        Text("线路", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        listOf(
                            BrowserNet.AUTO to "自动（国内直连，国外走代理）",
                            BrowserNet.PROXY to "全部走代理",
                            BrowserNet.DIRECT to "全部直连",
                        ).forEach { (key, label) ->
                            DropdownMenuItem(text = { Text((if (route == key) "✓ " else "    ") + label) }, onClick = {
                                menu = false
                                if (route != key) {
                                    route = key
                                    prefs.edit().putString("route", key).apply()
                                    if (address.isNotEmpty()) reload()
                                }
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("清除浏览数据", color = colors.error) }, onClick = { menu = false; clearData() })
                    }
                }
            }
            if (loading < 100) LinearProgressIndicator(progress = { loading / 100f }, Modifier.fillMaxWidth().height(2.dp))
            error?.let { msg ->
                Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                    TextButton(onClick = { reload() }) { Text("重试") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                if (address.isEmpty() && loading >= 100) StartPage()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = canBack, onClick = { web.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退") }
                IconButton(enabled = canForward, onClick = { web.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进") }
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
        external?.let { url ->
            AlertDialog(
                onDismissRequest = { external = null },
                title = { Text("打开别的 App？") },
                text = { Text("网页想用别的 App 打开：\n${url.take(200)}") },
                confirmButton = { TextButton(onClick = { external = null; openExternal(url) }) { Text("打开") } },
                dismissButton = { TextButton(onClick = { external = null }) { Text("不了") } },
            )
        }
        notice?.let { msg ->
            AlertDialog(
                onDismissRequest = { notice = null },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { notice = null }) { Text("好") } },
            )
        }
    }

    /** 底栏小字：当前网页实际怎么上网。 */
    private fun routeLabel(): String {
        val host = runCatching { Uri.parse(address).host }.getOrNull()
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
                    "这里的登录状态和手机浏览器不共用。不能用来登录 Google 账号（Google 不允许）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Google" to "https://www.google.com", "维基百科" to "https://zh.wikipedia.org", "GitHub" to "https://github.com",
                    "YouTube" to "https://m.youtube.com", "Notion" to "https://www.notion.so").forEach { (name, url) ->
                    OutlinedButton(onClick = { editing = url; open(url) }) { Text(name) }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
