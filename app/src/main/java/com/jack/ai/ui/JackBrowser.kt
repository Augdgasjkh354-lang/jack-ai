package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.http.SslError
import android.util.Base64
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.jack.ai.api.BrowserDownload
import com.jack.ai.api.BrowserNet
import com.jack.ai.api.BrowserAds
import com.jack.ai.api.BrowserPages
import com.jack.ai.api.BrowserTranslate
import com.jack.ai.data.backgroundEndpoint
import kotlinx.serialization.json.put
import com.jack.ai.api.Net
import com.jack.ai.data.ProxyCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Jack 浏览器：用户自己翻网页（BrowserActivity）和 Jack 替用户操作网页（browser 工具）用的是同一个网页窗口，
 * 登录状态共用，用户随时能接手。可以走 Jack 专用代理（见 BrowserNet）。
 * 和小工具的网页窗口完全分开：没有 JackBridge，不能读手机文件，不碰本机和局域网地址。
 * 网页窗口只能在主线程用；下面的挂起函数自己切到主线程。
 */
object JackBrowser {
    // ---------- 界面看的状态 ----------
    /**
     * 一个标签页：自己的网页窗口和这一页的状态。现在只开一个；多标签就是往 [tabs] 里加，
     * 网页窗口的回调都写自己这一页（[Tab]），界面和 Jack 看的是当前这一页。
     */
    class Tab(val id: Int) {
        var web: WebView? = null
        var address by mutableStateOf("")
        var title by mutableStateOf("")
        var loading by mutableIntStateOf(100)
        var canBack by mutableStateOf(false)
        var canForward by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        /** error 是不是「这一页没打开」：是就盖一层干净的出错页，否则（比如拦下了一次跳转）只在顶上提一句。 */
        var pageFailed by mutableStateOf(false)
        /** 页内查找：共几处、当前第几处（从 0 起）。 */
        var findCount by mutableIntStateOf(0)
        var findIndex by mutableIntStateOf(0)
        /** 这一页拦下的广告请求数。拦截在后台线程，先累加在 blockedNow，再交给界面。 */
        val blockedNow = java.util.concurrent.atomic.AtomicInteger(0)
        var blocked by mutableIntStateOf(0)
        /** 这一页现在显示的是译文；translating 是翻译进度（null = 没在翻）。 */
        var translated by mutableStateOf(false)
        var translating by mutableStateOf<String?>(null)
        /** 当前网页的网站名，给后台线程的拦截判断用（不能在那里读网页窗口）。 */
        @Volatile var site = ""
        /** 正在加载的页面加载完时完成。 */
        var pageDone: CompletableDeferred<Unit>? = null

        fun clearError() { error = null; pageFailed = false }
    }

    val tabs = androidx.compose.runtime.mutableStateListOf<Tab>()
    var current by mutableIntStateOf(0)
        private set
    private var nextTabId = 0
    /** 还没建网页窗口时，界面读到的空白页。 */
    private val blank = Tab(-1)
    val tab: Tab get() = tabs.getOrNull(current) ?: blank

    // 下面这些都是当前这一页的，界面和 browser 工具照旧这样读
    var address: String get() = tab.address; set(v) { tab.address = v }
    var title: String get() = tab.title; set(v) { tab.title = v }
    val loading: Int get() = tab.loading
    val canBack: Boolean get() = tab.canBack
    val canForward: Boolean get() = tab.canForward
    var error: String? get() = tab.error; set(v) { tab.error = v }
    var pageFailed: Boolean get() = tab.pageFailed; set(v) { tab.pageFailed = v }
    var findCount: Int get() = tab.findCount; set(v) { tab.findCount = v }
    var findIndex: Int get() = tab.findIndex; set(v) { tab.findIndex = v }
    /** 网页想打开别的 App，等用户点头（只在用户自己看着浏览器时问）。 */
    var external by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var route by mutableStateOf(BrowserNet.AUTO)
        private set
    /** 网页要下载、还没决定下不下的文件。 */
    var pending by mutableStateOf<BrowserDownload.Pending?>(null)
    /** 正在下载的进度文字（null = 没在下）。 */
    var downloading by mutableStateOf<String?>(null)
    /** 刚存到手机「下载」文件夹的文件，浏览器页给个「打开」按钮。 */
    var saved by mutableStateOf<Saved?>(null)

    data class Saved(val name: String, val uri: android.net.Uri, val mime: String, val where: String)

    /** 网页里的视频要全屏：全屏的画面和退出用的回调。 */
    var fullscreen by mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null)
    /** 浏览器页开着时由它接手「网页要选文件」；返回 false 表示没接。 */
    var chooser: ((android.webkit.ValueCallback<Array<android.net.Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    /** 用户长按了链接或图片，等浏览器页弹菜单。 */
    var pressed by mutableStateOf<Pressed?>(null)
    data class Pressed(val link: String?, val image: String?)
    /** 最近访问（只记用户自己看的）和书签，存在 browser 偏好里。 */
    var visits by mutableStateOf(listOf<BrowserPages.Visit>())
    var marks by mutableStateOf(listOf<BrowserPages.Visit>())
    /** 网页跟着 App 变深色（浅色主题时不变）。 */
    private var darkAllowed = true
    /** 去广告开没开、哪些网站不去（网站名）。拦截在后台线程读，所以另存一份。 */
    var adBlock by mutableStateOf(true)
        private set
    var adAllow by mutableStateOf(setOf<String>())
        private set
    @Volatile private var adBlockNow = true
    @Volatile private var adAllowNow = setOf<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 这个网站名下拦不拦广告。 */
    private fun blocksAds(site: String) = adBlockNow && adAllowNow.none { site == it || site.endsWith(".$it") }

    fun changeAdBlock(on: Boolean) {
        adBlock = on; adBlockNow = on
        prefs().edit().putBoolean("adblock", on).apply()
        reloadCurrent()
    }

    /** 当前网站不去广告（有的网站拦了广告就不让看），再点一次恢复。 */
    fun toggleAdAllow() {
        val site = BrowserPages.site(address).ifEmpty { return }
        adAllow = if (adAllow.any { site == it || site.endsWith(".$it") }) adAllow.filterNot { site == it || site.endsWith(".$it") }.toSet()
        else adAllow + site
        adAllowNow = adAllow
        prefs().edit().putStringSet("adallow", adAllow).apply()
        reloadCurrent()
    }

    fun adAllowedHere() = BrowserPages.site(address).let { s -> s.isNotEmpty() && adAllow.any { s == it || s.endsWith(".$it") } }

    private fun reloadCurrent() {
        val w = web ?: return
        if (address.isNotEmpty()) { clearError(); w.reload() }
    }

    /**
     * 把当前网页翻成中文：取出文字、分批交给模型（后台用的便宜模型），翻好一批换一批。
     * 网页换了就停。返回出错原因，成功返回 null。
     */
    suspend fun translate(ctx: Context): String? {
        val t = tab
        val w = web ?: return "还没打开网页"
        val url = t.address
        val s = com.jack.ai.data.Store(ctx).let { it to it.loadSettings() }
        val ep = s.second.backgroundEndpoint() ?: return "翻译要用模型：先到设置里填好 DeepSeek Key（或别的模型服务）"
        val raw = withContext(Dispatchers.Main) { suspendCancellableCoroutine<String> { c -> w.evaluateJavascript(BrowserTranslate.COLLECT_JS) { r -> c.resume(r ?: "") } } }
        val segs = BrowserTranslate.segments(raw)
        if (segs.isEmpty()) return "这一页没有需要翻译的外文"
        val batches = BrowserTranslate.batches(segs)
        val client = com.jack.ai.api.DeepSeekClient(s.first::addUsage)
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        var done = 0
        var failed = 0
        withContext(Dispatchers.Main) { t.translating = "正在翻译 0/${batches.size}"; t.translated = true }
        try {
            kotlinx.coroutines.coroutineScope {
                batches.forEach { ids ->
                    launch {
                        gate.acquire()
                        try {
                            val out = runCatching {
                                client.complete(ep, listOf(
                                    kotlinx.serialization.json.buildJsonObject { put("role", "system"); put("content", BrowserTranslate.SYSTEM) },
                                    kotlinx.serialization.json.buildJsonObject { put("role", "user"); put("content", BrowserTranslate.request(segs, ids)) },
                                ), maxTokens = 8192, jsonMode = true)
                            }.getOrNull()?.let { BrowserTranslate.parse(it, ids) }.orEmpty()
                            withContext(Dispatchers.Main) {
                                // 用户已经去了别的网页：不往新网页上写
                                if (t.address == url && t.translated && out.isNotEmpty()) w.evaluateJavascript(BrowserTranslate.applyJs(out), null)
                                if (out.isEmpty()) failed++
                                done++
                                t.translating = "正在翻译 $done/${batches.size}"
                            }
                        } finally { gate.release() }
                    }
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { t.translating = null }
        }
        return when {
            failed == batches.size -> "翻译没成功（模型没返回结果），可以再试一次"
            failed > 0 -> "有 $failed 批没翻成，那部分还是原文"
            segs.size >= BrowserTranslate.MAX_SEGMENTS -> "网页太长，只翻了前面一部分"
            else -> null
        }
    }

    /** 放回原文。 */
    fun showOriginal() {
        val w = web ?: return
        tab.translated = false
        w.evaluateJavascript(BrowserTranslate.RESTORE_JS, null)
    }

    /** 用电脑版打开的网站（网站名，不带 www.）。 */
    var desktopSites by mutableStateOf(setOf<String>())
    /** 手机版的浏览器标识（去掉了 App 内嵌标记）；电脑版的由它换算。 */
    private var mobileUa = ""

    /** 下载在后台跑，离开浏览器页也不停。 */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Jack 最近一次操作网页的时刻；聊天页据此显示小窗。 */
    var agentAt by mutableLongStateOf(0L)
    /** 网页窗口换了地方（浏览器页 ↔ 聊天小窗）就加一，让原来的地方重新来要。 */
    var hostGen by mutableIntStateOf(0)
    /** 用户正开着浏览器页。 */
    var userViewing = false

    private val web: WebView? get() = tab.web
    private lateinit var wrapper: MutableContextWrapper
    private lateinit var app: Context

    /** 网页上次是按哪个代理端口设的（0 = 直连）；内核重启换了端口就要重设。 */
    private var appliedPort = -1
    private var appliedRoute = ""
    private var pageDone: CompletableDeferred<Unit>? get() = tab.pageDone; set(v) { tab.pageDone = v }
    /** 最近给代理口令的时刻：10 秒内要了 30 次以上，说明口令没被接受。 */
    private val authTimes = ArrayDeque<Long>()

    fun agentActive(now: Long = System.currentTimeMillis()) = web != null && now - agentAt < 5 * 60_000

    // ---------- 网页窗口 ----------

    /** 拿到（第一次就建好）网页窗口。只能在主线程调。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context): WebView {
        web?.let { return it }
        app = ctx.applicationContext
        route = app.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("route", BrowserNet.AUTO) ?: BrowserNet.AUTO
        // 用可换底的 Context：放进浏览器页时换成那个页面（下拉框、弹窗要它），离开后换回 App
        wrapper = MutableContextWrapper(app)
        loadLists()
        return newTab().web!!
    }

    /**
     * 新开一个标签页并切过去。多标签的界面还没做，现在只在第一次用浏览器时开一个；
     * 以后加「新标签」按钮时调它，再加切换和关闭（[switchTo]、[closeTab]）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(): Tab {
        val t = Tab(nextTabId++)
        t.web = WebView(wrapper).apply {
            settings.apply {
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
                userAgentString = BrowserPages.cleanUserAgent(userAgentString)
                mobileUa = userAgentString
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkAllowed)
            }
            // 第三方 Cookie 要开：「用 XX 账号登录」的跳转和内嵌登录靠它
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setFindListener { active, count, done -> if (done) { t.findIndex = active; t.findCount = count } }
            setOnLongClickListener { v -> longPress(v as WebView) }
            webViewClient = Client(t)
            webChromeClient = Chrome(t)
            setDownloadListener { url, userAgent, disposition, mime, length ->
                // 先记下来：用户看着就弹窗问，Jack 在操作就等它用 download 动作确认
                pending = BrowserDownload.Pending(url, BrowserDownload.nameFor(url, disposition, mime), mime.orEmpty(),
                    length, userAgent.orEmpty(), t.address, byUser = userViewing)
            }
        }
        tabs += t
        switchTo(tabs.lastIndex)
        return t
    }

    /** 切到第 i 个标签页：网页窗口换了，让浏览器页重新来要。 */
    fun switchTo(i: Int) {
        if (i !in tabs.indices) return
        current = i
        hostGen++
    }

    /** 关掉一个标签页（最后一个不关，只回起始页）。 */
    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        if (tabs.size == 1) { home(); return }
        val t = tabs.removeAt(i)
        (t.web?.parent as? ViewGroup)?.removeView(t.web)
        t.web?.destroy()
        switchTo(if (i < current) current - 1 else current.coerceAtMost(tabs.lastIndex))
    }

    private fun prefs() = app.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val listJson = Json { ignoreUnknownKeys = true }
    private val visitList = kotlinx.serialization.builtins.ListSerializer(BrowserPages.Visit.serializer())

    private fun loadLists() {
        fun read(k: String) = prefs().getString(k, null)?.let { runCatching { listJson.decodeFromString(visitList, it) }.getOrNull() }.orEmpty()
        visits = read("visits"); marks = read("marks")
        desktopSites = prefs().getStringSet("desktop", emptySet()).orEmpty().toSet()
        adBlock = prefs().getBoolean("adblock", true); adBlockNow = adBlock
        adAllow = prefs().getStringSet("adallow", emptySet()).orEmpty().toSet(); adAllowNow = adAllow
    }

    private fun uaFor(url: String) = if (BrowserPages.isDesktop(desktopSites, url)) BrowserPages.desktopUserAgent(mobileUa) else mobileUa

    /** 换网页前按网站选手机版还是电脑版的标识。返回有没有换。 */
    private fun applyUa(w: WebView, url: String): Boolean {
        val ua = uaFor(url)
        if (mobileUa.isEmpty() || w.settings.userAgentString == ua) return false
        w.settings.userAgentString = ua
        return true
    }

    /** 当前网站切换电脑版 / 手机版（按网站记住），然后重新打开。 */
    fun toggleDesktop() {
        val w = web ?: return
        val site = BrowserPages.site(address).ifEmpty { return }
        desktopSites = if (BrowserPages.isDesktop(desktopSites, address)) desktopSites.filterNot { site == it || site.endsWith(".$it") }.toSet()
        else desktopSites + site
        prefs().edit().putStringSet("desktop", desktopSites).apply()
        applyUa(w, address)
        clearError()
        w.loadUrl(address)
    }

    /** 电脑版：自适应的网站会按手机宽度排，把可视宽度改成电脑的，再缩到屏幕上。 */
    private fun widenIfDesktop(w: WebView, url: String) {
        if (!BrowserPages.isDesktop(desktopSites, url)) return
        w.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';" +
                "(document.head||document.documentElement).appendChild(m);}m.content='width=1200';})();", null,
        )
    }

    /** 「问 Kavi」：当前网页的标题、网址和正文（最多 6 万字）。 */
    suspend fun pageText(ctx: Context): String {
        val raw = unwrap(eval(ctx, "(document.body ? document.body.innerText : '').slice(0, 60000)"))
        return "网页：$title\n链接：$address\n\n" + raw.trim()
    }

    /** 长按图片「识别二维码」：带着网页的登录状态把图片取下来再读。读不出返回 null。 */
    suspend fun scanQr(url: String): String? {
        val bytes = if (url.startsWith("data:image", true)) {
            runCatching { Base64.decode(url.substringAfter(","), Base64.DEFAULT) }.getOrNull() ?: return null
        } else {
            val cookie = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(url) }
            val ua = withContext(Dispatchers.Main) { web?.settings?.userAgentString.orEmpty() }
            withContext(Dispatchers.IO) {
                val out = ByteArrayOutputStream()
                BrowserDownload.fetch(BrowserDownload.Pending(url, "qr", "", -1, ua, address), cookie, route, 10L * 1024 * 1024,
                    open = { out.also { it.reset() } }, reset = { out.reset() })
                out.toByteArray()
            }
        }
        return withContext(Dispatchers.Default) { com.jack.ai.data.QrImage.decode(bytes) }
    }

    private fun saveLists() {
        prefs().edit().putString("visits", listJson.encodeToString(visitList, visits)).putString("marks", listJson.encodeToString(visitList, marks)).apply()
    }

    /** 当前网页加进书签或从书签去掉。 */
    fun toggleMark() {
        marks = BrowserPages.toggle(marks, address, title, System.currentTimeMillis())
        saveLists()
    }

    /** 网页跟着 App 的深浅色：浅色主题时不变暗。跟着系统深色走（网页组件看的是系统设置）。 */
    fun setDark(allowed: Boolean) {
        darkAllowed = allowed
        val w = web ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, allowed)
        }
    }

    /** 回到起始页（书签、最近访问）。能后退回刚才的网页。 */
    fun home() {
        val w = web ?: return
        clearError()
        w.loadUrl("about:blank")
        address = ""; title = ""
    }

    /** 用户或 Jack 开始一次新的跳转：上一页的出错提示不再适用。 */
    fun clearError() { error = null; pageFailed = false }

    /** 长按链接或图片：记下来让浏览器页弹菜单；长按文字照旧选字。Jack 在操作时不弹。 */
    private fun longPress(w: WebView): Boolean {
        if (!userViewing) return false
        val r = w.hitTestResult
        return when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { pressed = Pressed(r.extra, null); true }
            WebView.HitTestResult.IMAGE_TYPE -> { pressed = Pressed(null, r.extra); true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // 图片外面包着链接：链接地址要另外问网页
                val img = r.extra
                val h = android.os.Handler(android.os.Looper.getMainLooper()) { m -> pressed = Pressed(m.data.getString("url"), img); true }
                w.requestFocusNodeHref(h.obtainMessage())
                true
            }
            else -> false
        }
    }

    /** 长按图片选了「下载」：和网页里点下载一样，先弹窗问。 */
    fun downloadImage(url: String) {
        if (!BrowserPages.isWeb(url)) { notice = "这张图片是网页内嵌的，没法单独下载。可以截屏保存。"; return }
        pending = BrowserDownload.Pending(url, BrowserDownload.nameFor(url, null, "image/jpeg"), "", -1,
            web?.settings?.userAgentString.orEmpty(), address, byUser = true)
    }

    /** 放进某个界面：先从原来的地方拿出来。ctx 是那个界面所在的页面。 */
    fun claim(ctx: Context): WebView {
        val w = webView(ctx)
        (w.parent as? ViewGroup)?.removeView(w)
        wrapper.baseContext = ctx
        return w
    }

    /** 那个界面不用了：Context 换回 App；handBack 时让别的地方（聊天小窗）重新来要。 */
    fun release(ctx: Context, handBack: Boolean = true) {
        if (::wrapper.isInitialized && wrapper.baseContext === ctx) wrapper.baseContext = app
        if (handBack) hostGen++
    }

    fun changeRoute(r: String) {
        route = r
        if (::app.isInitialized) app.getSharedPreferences("browser", Context.MODE_PRIVATE).edit().putString("route", r).apply()
    }

    // ---------- 打开网页（用户和 Jack 共用） ----------

    /** 按线路设好代理再执行 then；走代理但代理起不来就不执行，返回原因。 */
    suspend fun prepare(ctx: Context): String? {
        withContext(Dispatchers.Main) { webView(ctx) }
        val plan = withContext(Dispatchers.IO) { BrowserNet.plan(route) }
        return withContext(Dispatchers.Main) {
            when (plan) {
                is BrowserNet.Plan.Blocked -> plan.reason
                BrowserNet.Plan.Direct -> apply(0, emptyList())
                is BrowserNet.Plan.Proxy -> apply(plan.port, plan.bypass)
            }
        }
    }

    private suspend fun apply(port: Int, bypass: List<String>): String? {
        if (port == appliedPort && route == appliedRoute) return null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (port == 0) { appliedPort = 0; appliedRoute = route; return null }
            return "这台手机的网页组件版本太旧，不能单独给浏览器设代理。到应用商店更新「Android System WebView」后再试。"
        }
        val controller = ProxyController.getInstance()
        suspendCancellableCoroutine { cont ->
            val done = Runnable { if (cont.isActive) cont.resume(Unit) }
            if (port == 0) controller.clearProxyOverride({ it.run() }, done)
            else controller.setProxyOverride(
                ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").apply { bypass.forEach { addBypassRule(it) } }.build(),
                { it.run() }, done,
            )
        }
        appliedPort = port; appliedRoute = route
        return null
    }

    /** 浏览器关了、Jack 也有一阵没用：撤掉代理设置，小工具的网页窗口照旧直连。 */
    fun clearProxyIfIdle() {
        if (userViewing || agentActive()) return
        if (appliedPort > 0 && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
            appliedPort = -1
        }
    }

    /** 打开网址；等到加载完（最多 timeoutMs）。返回出错原因，成功返回 null。 */
    suspend fun load(ctx: Context, url: String, timeoutMs: Long = 25_000): String? {
        withContext(Dispatchers.Main) { clearError() }
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.OPEN -> {}
            BrowserNet.Kind.EXTERNAL -> return "这是打开别的 App 的链接，浏览器里打不开：$url"
            BrowserNet.Kind.BLOCKED -> return "不能打开这个地址（本机、局域网和文件地址都不开）：$url"
        }
        prepare(ctx)?.let { reason -> withContext(Dispatchers.Main) { error = reason; pageFailed = true }; return reason }
        val done = withContext(Dispatchers.Main) {
            CompletableDeferred<Unit>().also { pageDone = it; webView(ctx).let { w -> applyUa(w, url); w.loadUrl(url) } }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        return withContext(Dispatchers.Main) { error }
    }

    fun clearData() {
        val w = web ?: return
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(app).apply { clearHttpAuthUsernamePassword(); clearFormData() }
        w.clearCache(true)
        w.clearHistory()
        w.clearFormData()
        w.loadUrl("about:blank")
        address = ""; title = ""
        visits = emptyList(); saveLists()
        notice = "已清除：Cookie（登录状态）、缓存、网页存储、浏览记录和最近访问（书签留着）。"
    }

    private fun describe(code: Int, desc: String): String = when {
        "PROXY" in desc && "AUTH" in desc -> "代理不接受浏览器给的口令（$desc）。把这个提示告诉开发者。"
        "PROXY" in desc || "TUNNEL" in desc -> "连不上 Jack 专用代理（$desc）。" + (ProxyCore.lastError?.let { "代理：$it" } ?: "可以点刷新重试，或到设置里测试代理。")
        code == WebViewClient.ERROR_HOST_LOOKUP -> "找不到这个网站（$desc）。" + if (route == BrowserNet.DIRECT) "国外网站可以把线路换成「自动」或「全部走代理」。" else ""
        code == WebViewClient.ERROR_TIMEOUT || code == WebViewClient.ERROR_CONNECT ->
            "连接失败（$desc）。" + if (route == BrowserNet.DIRECT || !Net.config.enabled) "国外网站需要开 Jack 专用代理。" else "节点可能不通，到设置里测试一下代理。"
        else -> "打不开（$desc）"
    }

    private class Client(val t: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (BrowserNet.classify(url)) {
                BrowserNet.Kind.OPEN -> {
                    if (!request.isForMainFrame) false
                    // 网页没经过用户点击就自己跳到广告网站：不去
                    else if (!request.hasGesture() && BrowserAds.isAd(request.url.host.orEmpty()) && blocksAds(t.site)) true
                    else {
                        t.clearError()
                        // 从手机版网站跳到电脑版网站（或反过来）：换好标识重新打开这个网址
                        if (applyUa(view, url)) { view.loadUrl(url); true } else false
                    }
                }
                BrowserNet.Kind.EXTERNAL -> { if (userViewing && request.hasGesture()) external = url; true }
                BrowserNet.Kind.BLOCKED -> { if (request.isForMainFrame) t.error = "已拦下：网页想打开本机、局域网或文件地址（$url）"; true }
            }
        }

        // 网页里的图片、脚本、请求也不许碰本机和局域网
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host ?: return null
            if (request.url.scheme in setOf("http", "https", "ws", "wss") && BrowserNet.isLocal(host)) {
                return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            // 去广告：网页里的广告和跟踪请求直接不发（网页本身不拦）
            if (!request.isForMainFrame && BrowserAds.isAd(host) && blocksAds(t.site)) {
                val n = t.blockedNow.incrementAndGet()
                main.post { if (t.blockedNow.get() == n) t.blocked = n }
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            if (BrowserNet.isOurProxy(host, realm) && ProxyCore.user.isNotEmpty()) {
                // 并行的连接各要一次（实测一个页面 6 次）
                val now = System.currentTimeMillis()
                while (authTimes.isNotEmpty() && now - authTimes.first() > 10_000) authTimes.removeFirst()
                if (authTimes.size < 30) { authTimes.addLast(now); handler.proceed(ProxyCore.user, ProxyCore.pass); return }
                t.error = "浏览器没能通过 Jack 专用代理的口令验证。把这个提示告诉开发者。"
            }
            // 网站自己要的账号密码（很少见）：不支持，取消
            handler.cancel()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: SslError) {
            // 证书不对一律不继续：可能是假网站或被劫持
            handler.cancel()
            if (e.url == view.url || e.url == t.address) { t.error = "这个网站的安全证书有问题，已停止打开（${e.url}）"; t.pageFailed = true }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
            if (request.isForMainFrame) { t.error = describe(e.errorCode, e.description?.toString().orEmpty()); t.pageFailed = true }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (t.pageDone?.isCompleted != false) t.pageDone = CompletableDeferred()
            t.translated = false
            t.address = url
            t.site = BrowserPages.site(url)
            t.blockedNow.set(0); t.blocked = 0
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            widenIfDesktop(view, url)
            if (blocksAds(t.site)) view.evaluateJavascript(BrowserAds.INJECT_JS, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            widenIfDesktop(view, url)
            if (blocksAds(t.site)) view.evaluateJavascript(BrowserAds.INJECT_JS, null)
            t.pageDone?.complete(Unit)
            if (url != "about:blank") t.address = url
            // 最近访问只记用户自己看的、打开成功的
            if (userViewing && !t.pageFailed && BrowserPages.isWeb(url)) {
                visits = BrowserPages.visit(visits, url, view.title.orEmpty().takeUnless { it == url }.orEmpty(), System.currentTimeMillis())
                saveLists()
            }
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url != "about:blank") t.address = url
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }
    }

    private class Chrome(val t: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { t.loading = newProgress }
        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            t.title = newTitle.orEmpty()
            val url = view.url.orEmpty()
            if (newTitle != null && newTitle != url) BrowserPages.retitle(visits, url, newTitle).let { if (it !== visits) { visits = it; saveLists() } }
        }
        // 视频全屏：交给浏览器页盖满屏幕
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!userViewing) { callback.onCustomViewHidden(); return }
            fullscreen = view to callback
        }
        override fun onHideCustomView() { fullscreen = null }
        // 网页要选文件上传：只有用户开着浏览器页时才弹选择器（Jack 操作时不替用户选文件）
        override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
            val c = chooser
            return if (userViewing && c != null) c(callback, params) else false
        }
        // 不给网页任何手机权限（相机、麦克风、位置）
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
        // 网页弹的 alert/confirm：Jack 操作时没人点，直接按「确定」的默认处理会卡住页面，所以都按取消
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
            if (!userViewing) { lastDialog = message; result.confirm(); return true }
            return false
        }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
            if (!userViewing) { lastDialog = message; result.cancel(); return true }
            return false
        }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
            if (!userViewing) { lastDialog = message; result.cancel(); return true }
            return false
        }
    }

    /** Jack 操作时网页弹出的提示框内容（已自动关掉），下次读页面时告诉它。 */
    @Volatile private var lastDialog: String? = null

    // ---------- 下载 ----------

    private fun progressText(name: String, got: Long, total: Long) =
        "正在下载「$name」：" + if (total > 0) "${got * 100 / total}%（${BrowserDownload.sizeText(got)} / ${BrowserDownload.sizeText(total)}）"
        else BrowserDownload.sizeText(got)

    /**
     * 用户点了下载：存到手机的「下载/JackAI」文件夹（Android 10 以上，文件管理器里能找到）；
     * 更老的系统存到 App 自己的下载目录。在后台下，进度和结果显示在浏览器页。
     */
    fun saveToPhone(ctx: Context, p: BrowserDownload.Pending) {
        val app = ctx.applicationContext
        pending = null
        downloading = progressText(p.name, 0, p.size)
        scope.launch {
            val cookie = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(p.url) }
            val mime = p.mime.ifBlank { "application/octet-stream" }
            try {
                val result = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val resolver = app.contentResolver
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, p.name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/JackAI")
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw java.io.IOException("没能在「下载」文件夹里建文件")
                    try {
                        BrowserDownload.fetch(p, cookie, route, BrowserDownload.HARD_LIMIT,
                            open = { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("打不开要写的文件") }, reset = {},
                            progress = { got, total -> downloading = progressText(p.name, got, total) })
                        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null); throw e
                    }
                    // 重名时系统会自动改名，以系统里的为准
                    val name = resolver.query(uri, arrayOf(android.provider.MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: p.name
                    Saved(name, uri, mime, "手机的「下载/JackAI」文件夹")
                } else {
                    val dir = java.io.File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "")
                    val f = com.jack.ai.data.Workspace.uniqueFile(dir, p.name)
                    try {
                        BrowserDownload.fetch(p, cookie, route, BrowserDownload.HARD_LIMIT, open = { f.outputStream() }, reset = {},
                            progress = { got, total -> downloading = progressText(p.name, got, total) })
                    } catch (e: Exception) {
                        f.delete(); throw e
                    }
                    Saved(f.name, androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", f), mime,
                        "Jack 的下载目录（Android/data/${app.packageName}/files/Download）")
                }
                saved = result
                downloading = null
            } catch (e: Exception) {
                downloading = null
                notice = "「${p.name}」没下载成功：" + (e.message ?: e.javaClass.simpleName) + Net.hint()
            }
        }
    }

    /** Jack 确认下载：存进对话文件夹（同名加编号），返回文件。 */
    suspend fun saveTo(dir: java.io.File, p: BrowserDownload.Pending): java.io.File {
        val cookie = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(p.url) }
        return withContext(Dispatchers.IO) {
            val f = com.jack.ai.data.Workspace.uniqueFile(dir, p.name)
            try {
                downloading = progressText(p.name, 0, p.size)
                BrowserDownload.fetch(p, cookie, route, BrowserDownload.HARD_LIMIT, open = { f.outputStream() }, reset = {},
                    progress = { got, total -> downloading = progressText(p.name, got, total) })
                f
            } catch (e: Exception) {
                f.delete(); throw e
            } finally {
                downloading = null
                if (pending == p) pending = null
            }
        }
    }

    // ---------- Jack 操作网页 ----------

    private val json = Json { ignoreUnknownKeys = true }

    /** 页面的一项可操作元素。坐标是网页像素（CSS px），截图上画框用。 */
    data class Item(val n: Int, val kind: String, val label: String, val value: String?, val checked: Boolean?,
                    val options: List<String>, val href: String?, val x: Float, val y: Float, val w: Float, val h: Float)

    data class Page(val url: String, val title: String, val items: List<Item>, val above: Int, val below: Int,
                    val scroll: Int, val height: Int, val vh: Int, val text: String,
                    /** 可视区在网页里的位置和宽度（CSS px），截图时把元素坐标换成屏幕像素。 */
                    val vx: Float, val vy: Float, val vw: Float, val error: String?, val dialog: String?,
                    /** 网页要下载、等确认的文件。 */
                    val download: BrowserDownload.Pending? = null)

    /** 没放进任何界面时，按手机屏幕大小排版，网页才有正常的宽高。 */
    private fun ensureLaidOut(w: WebView) {
        if (w.parent != null && w.width > 0) return
        val dm = app.resources.displayMetrics
        w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private suspend fun eval(ctx: Context, js: String): String = withContext(Dispatchers.Main) {
        val w = webView(ctx)
        ensureLaidOut(w)
        suspendCancellableCoroutine { cont -> w.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r ?: "null") } }
    }

    /** evaluateJavascript 把返回的字符串再包了一层 JSON 引号，解开。 */
    private fun unwrap(r: String): String = runCatching { (json.parseToJsonElement(r) as? JsonPrimitive)?.content }.getOrNull() ?: r

    /** 读当前页面：可操作元素编上号，加上当前屏附近的文字。 */
    suspend fun read(ctx: Context): Page {
        agentAt = System.currentTimeMillis()
        val raw = unwrap(eval(ctx, BrowserScripts.snapshot(ctx)))
        val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return Page(address, title, emptyList(), 0, 0, 0, 0, 0, "", 0f, 0f, 0f, error ?: "读不到页面内容（页面可能还没加载出来）", null)
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        fun JsonObject.f(k: String) = s(k).toFloatOrNull() ?: 0f
        val items = (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { e ->
            val i = e.jsonObject
            Item(
                i.s("n").toIntOrNull() ?: 0, i.s("kind"), i.s("label"), (i["value"] as? JsonPrimitive)?.content,
                (i["checked"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                (i["options"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { (it as JsonPrimitive).content },
                (i["href"] as? JsonPrimitive)?.content, i.f("x"), i.f("y"), i.f("w"), i.f("h"),
            )
        }
        val dialog = lastDialog.also { lastDialog = null }
        return Page(o.s("url"), o.s("title"), items, o.f("above").toInt(), o.f("below").toInt(), o.f("scroll").toInt(),
            o.f("height").toInt(), o.f("vh").toInt(), o.s("text"), o.f("vx"), o.f("vy"), o.f("vw"),
            withContext(Dispatchers.Main) { error }, dialog, pending)
    }

    /** 某个编号的元素是什么（决定要不要先问用户）。找不到返回 null。 */
    suspend fun inspect(ctx: Context, n: Int): JsonObject? {
        val raw = unwrap(eval(ctx, BrowserScripts.inspect(ctx, n)))
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    /** 对某个编号的元素做一个动作（click / type / delete / select / key），做完等页面稳定。返回出错原因。 */
    suspend fun act(ctx: Context, n: Int, op: String, arg: String = "", clear: Boolean = false): String? {
        agentAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) { error = null; pageDone = CompletableDeferred() }
        val raw = unwrap(eval(ctx, BrowserScripts.act(ctx, n, op, arg, clear)))
        val err = runCatching { (json.parseToJsonElement(raw).jsonObject["err"] as? JsonPrimitive)?.content }.getOrNull()
        settle()
        return err ?: withContext(Dispatchers.Main) { error }
    }

    /** 翻页：dir = 1 往下，-1 往上。 */
    suspend fun scroll(ctx: Context, dir: Int) {
        agentAt = System.currentTimeMillis()
        eval(ctx, "window.scrollBy(0, ${dir} * window.innerHeight * 0.8); 1")
        delay(400)
    }

    suspend fun back(ctx: Context): Boolean {
        agentAt = System.currentTimeMillis()
        val ok = withContext(Dispatchers.Main) {
            val w = webView(ctx)
            if (w.canGoBack()) { pageDone = CompletableDeferred(); clearError(); w.goBack(); true } else false
        }
        if (ok) settle()
        return ok
    }

    /** 动作做完等一会儿：如果引起了跳转就等新页面加载完（最多 15 秒），再给脚本一点时间把内容画出来。 */
    private suspend fun settle() {
        delay(700)
        val pending = withContext(Dispatchers.Main) { pageDone?.takeIf { loading < 100 } }
        if (pending != null) withTimeoutOrNull(15_000) { pending.await() }
        delay(400)
    }

    /**
     * 截图（画上和清单一样的编号框），缩到最宽 720 像素的 JPEG，返回 data URL。
     * 网页窗口没放在任何界面里时可能截不出东西，返回 null。
     */
    suspend fun screenshot(ctx: Context, page: Page): String? = withContext(Dispatchers.Main) {
        val w = webView(ctx)
        ensureLaidOut(w)
        if (w.width <= 0 || w.height <= 0) return@withContext null
        val scale = minOf(1f, 720f / w.width)
        val bmp = Bitmap.createBitmap((w.width * scale).toInt(), (w.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        // 网页内容自己会按滚动位置画；画框的坐标是相对可视区的
        c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
        w.draw(c)
        c.translate(w.scrollX.toFloat(), w.scrollY.toFloat())
        // 全白（或全一色）就是没画出来
        val sample = IntArray(16) { i -> bmp.getPixel((bmp.width * (i % 4 + 1) / 5).coerceAtMost(bmp.width - 1), (bmp.height * (i / 4 + 1) / 5).coerceAtMost(bmp.height - 1)) }
        if (sample.distinct().size == 1) { bmp.recycle(); return@withContext null }
        // 元素坐标（相对网页可视区左上角的 CSS px）→ 网页窗口像素
        val k = if (page.vw > 0) w.width / page.vw else 1f
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f / scale; color = Color.rgb(230, 40, 40) }
        val tag = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(230, 40, 40) }
        val txt = Paint().apply { color = Color.WHITE; textSize = 26f / scale; isAntiAlias = true; isFakeBoldText = true }
        page.items.forEach { i ->
            val l = (i.x - page.vx) * k; val t = (i.y - page.vy) * k
            if (t > w.height || t + i.h * k < 0) return@forEach
            c.drawRect(l, t, l + i.w * k, t + i.h * k, box)
            val label = i.n.toString()
            val tw = txt.measureText(label) + 8f / scale
            c.drawRect(l, t, l + tw, t + txt.textSize + 6f / scale, tag)
            c.drawText(label, l + 4f / scale, t + txt.textSize, txt)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        bmp.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
