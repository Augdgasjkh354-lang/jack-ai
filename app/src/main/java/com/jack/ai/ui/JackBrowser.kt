package com.jack.ai.ui

import kotlinx.serialization.builtins.serializer
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
import com.jack.ai.api.MediaGrab
import com.jack.ai.data.Downloads
import com.jack.ai.data.backgroundEndpoint
import com.wangzan101.mika.core.cap.web.ElementFusion
import com.wangzan101.mika.core.cap.web.ElementProbe
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
 * Kavi 浏览器：用户自己翻网页（BrowserActivity）和 Kavi 替用户操作网页（browser 工具）用的是同一个网页窗口，
 * 登录状态共用，用户随时能接手。可以走 Kavi 专用代理（见 BrowserNet）。
 * 和小工具的网页窗口完全分开：没有 JackBridge，不能读手机文件，不碰本机和局域网地址。
 * 网页窗口只能在主线程用；下面的挂起函数自己切到主线程。
 */
object JackBrowser {
    // ---------- 界面看的状态 ----------
    /**
     * 一个标签页：自己的网页窗口和这一页的状态。现在只开一个；多标签就是往 [tabs] 里加，
     * 网页窗口的回调都写自己这一页（[Tab]），界面和 Kavi 看的是当前这一页。
     */
    /** incognito：隐身标签页，Cookie、登录状态、网页存储和别的标签页分开，关掉最后一个隐身页就全清掉，也不记最近访问。 */
    /** account：分身（1 到 [ACCOUNTS]），和主账号、别的分身的登录状态分开，同一个网站能登不同的账号；0 是平常的。 */
    class Tab(val id: Int, val incognito: Boolean = false, val account: Int = 0) {
        var mediaFromUser = false
        val mediaFrames = mutableMapOf<String, Pair<Boolean, Long>>()
        val audible get() = mediaFrames.values.any { it.first && System.currentTimeMillis() - it.second < 5000 }
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
        /** 这一页拦下的跟踪器请求数（同上）。 */
        val trackersNow = java.util.concurrent.atomic.AtomicInteger(0)
        var trackers by mutableIntStateOf(0)
        /** 只用加密连接：这次自动换成的 https 网址（还没打开成功）。 */
        var httpsTry: String? = null
        /** 加密打不开：用户可以选「仍然打开」去的 http 网址。 */
        var httpFallback by mutableStateOf<String?>(null)
        /** 这一页在跑的网页脚本。 */
        var scriptsRan by mutableStateOf(listOf<String>())
        /** 这一页现在显示的是译文；translating 是翻译进度（null = 没在翻）。 */
        var translated by mutableStateOf(false)
        var translating by mutableStateOf<String?>(null)
        /** 当前网页的网站名，给后台线程的拦截判断用（不能在那里读网页窗口）。 */
        @Volatile var site = ""
        /** 正在加载的页面加载完时完成。 */
        var pageDone: CompletableDeferred<Unit>? = null
        /** 网页弹出的窗口（登录窗口之类）：是哪个标签页弹的，关掉时回到它。 */
        var openedBy: Int? = null
        /** 网页诊断：出错的平时就记；diagOn 时网页里加了钩子，点击、弹框、请求也记。 */
        val diag = BrowserDiag.Log()
        /** 上一次按编号操作是否已由 CDP 送出；避免再模拟触摸造成重复点击。 */
        @Volatile var lastCdpInput = false
        /** 主文档返回的 HTTP 状态；子资源 403 不拿来当整站限流。 */
        @Volatile var mainHttpStatus: Int? = null
        var diagOn by mutableStateOf(false)
        /** 数后台请求的钩子（见 BrowserNetWatch）；旧网页组件装不上开头注入时是 null，页面露出来再补。 */
        var netScript: androidx.webkit.ScriptHandler? = null
        var diagScript: androidx.webkit.ScriptHandler? = null
        var teachScript: androidx.webkit.ScriptHandler? = null
        /** 防 IP 泄漏的脚本（关着 WebRTC 时装上）。 */
        var rtcScript: androidx.webkit.ScriptHandler? = null
        /** 标签页列表里的缩略图（离开这一页、打开列表时拍）。 */
        var thumb by mutableStateOf<android.graphics.Bitmap?>(null)
        /** 电脑版脚本（按放大比例装的，换比例时重装）。 */
        var deskScript: androidx.webkit.ScriptHandler? = null
        /** 这一页里播放器拉过的视频、音频地址（拦截请求时记下，最新的在后面），存视频时从这里挑。 */
        val media = java.util.concurrent.ConcurrentLinkedDeque<MediaGrab.Hit>()
        /** 标签页名字前面的标记：隐身、分身几。 */
        val badge get() = if (incognito) "隐身 · " else if (account > 0) "分身$account · " else ""
        /** 关掉 App 前存下的标签页刚建回来、还没加载：切到它时再打开这个网址（省得一开 App 十个网页一起加载）。 */
        var restore: String? = null
        /** 视频倍速：这个标签页往后打开的网页都按这个放，选回 1× 才恢复。 */
        var rate by androidx.compose.runtime.mutableFloatStateOf(1f)
        /** 网页的小图标（加到主屏幕用）。 */
        var icon: Bitmap? = null
        /** 分组名（空 = 不分组）。 */
        var group by mutableStateOf("")
        /** 上次切到这一页的时间：标签页多了，最久没看的先休眠。 */
        var usedAt = System.currentTimeMillis()
        /** 休眠时存下的前进后退记录：醒来时恢复，后退还能用。 */
        var sleepState: android.os.Bundle? = null
        /** 休眠了：网页窗口收掉省内存，切过来再打开（见 [restore]）。 */
        val asleep get() = web == null
        /**
         * 网页窗口是新建的（恢复的、休眠醒来的、崩溃后重开的），还没有任何网页显示出来：
         * 这时窗口里是一整块空白（深色下是灰的），浏览器页盖一层「正在打开」。
         */
        var fresh by mutableStateOf(true)

        fun clearError() { error = null; pageFailed = false }
    }

    val tabs = androidx.compose.runtime.mutableStateListOf<Tab>()

    /** 网页里选中文字点「问 Kavi」时调（浏览器页开着时才有）。 */
    var askSelection: ((String) -> Unit)? = null

    internal const val INCOGNITO = "jack-incognito"

    /** 网页组件支不支持隐身和分身（分开的 Cookie 和存储，要较新的 WebView）。 */
    fun incognitoSupported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** 最多几个分身。 */
    const val ACCOUNTS = 3
    internal fun accountProfile(n: Int) = "jack-account-$n"

    /** 这个标签页用的 Cookie（分身、隐身各有各的）。 */
    fun cookiesOf(t: Tab): CookieManager {
        val name = if (t.incognito) INCOGNITO else if (t.account > 0) accountProfile(t.account) else null
        return name?.takeIf { incognitoSupported() }?.let { androidx.webkit.ProfileStore.getInstance().getProfile(it)?.cookieManager } ?: CookieManager.getInstance()
    }

    /** 离开浏览器时把各个分身的 Cookie 也写到磁盘。 */
    fun flushCookies() {
        CookieManager.getInstance().flush()
        if (!incognitoSupported()) return
        (1..ACCOUNTS).forEach { n -> androidx.webkit.ProfileStore.getInstance().getProfile(accountProfile(n))?.cookieManager?.flush() }
    }

    /** 隐身的 Cookie、网页存储全删掉（没有隐身页在用时整个删）。 */
    internal fun clearIncognito() {
        if (!incognitoSupported()) return
        val store = androidx.webkit.ProfileStore.getInstance()
        if (runCatching { store.deleteProfile(INCOGNITO) }.getOrDefault(false)) return
        store.getProfile(INCOGNITO)?.let { p -> p.cookieManager.removeAllCookies(null); p.webStorage.deleteAllData() }
    }
    var current by mutableIntStateOf(0)
        internal set
    private var nextTabId = 0
    /** 还没建网页窗口时，界面读到的空白页。 */
    private val blank = Tab(-1)
    /**
     * 当前这一页。Kavi 在自己的后台标签页里做事时（[PinTab] 钉住的那段操作里），读到的是它那一页，
     * 下面的 address、title、error 这些也跟着是那一页；界面和用户看的始终是 tabs[current]。
     */
    internal val pinLocal = ThreadLocal<PinTab?>()
    val tab: Tab get() = pinLocal.get()?.t ?: tabs.getOrNull(current) ?: blank

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
    var searchEngine by mutableStateOf(BrowserNet.AUTO)
        internal set
    fun changeSearchEngine(engine: String) {
        searchEngine = engine
        prefs().edit().putString("searchEngine", engine).apply()
    }
    var route by mutableStateOf(BrowserNet.AUTO)
        internal set
    /** 网页要下载、还没决定下不下的文件。 */
    var pending by mutableStateOf<BrowserDownload.Pending?>(null)
    /** 这一页（Kavi 钉住的那页，或当前页）要下载的文件；别的标签页的不算（几个回答同时用浏览器时各管各的）。 */
    fun pendingHere(): BrowserDownload.Pending? = pending?.takeIf { it.tab < 0 || it.tab == tab.id }
    /** 正在下载的进度文字（null = 没在下）。 */
    var downloading by mutableStateOf<String?>(null)
    /** 刚存到手机「下载」文件夹的文件，浏览器页给个「打开」按钮。 */
    var saved by mutableStateOf<Downloads.Saved?>(null)

    /** 网页里的视频要全屏：全屏的画面和退出用的回调。 */
    var fullscreen by mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null)
    /** 浏览器页开着时由它接手「网页要选文件」；返回 false 表示没接。 */
    var chooser: ((android.webkit.ValueCallback<Array<android.net.Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    /**
     * 网页要用相机、麦克风或位置，等用户点头（只在用户自己看着浏览器时问；Kavi 操作时一律不给）。
     * answer(给不给, 记不记住)。
     */
    class PermAsk(val site: String, val kinds: Set<String>, val incognito: Boolean, val answer: (Boolean, Boolean) -> Unit)
    var permAsk by mutableStateOf<PermAsk?>(null)
    /** 浏览器页开着时由它向系统要手机权限（相机、麦克风、位置），回调给没给。 */
    var systemPerms: ((Set<String>, (Boolean) -> Unit) -> Unit)? = null
    /** 记住的网站权限：允许的、不允许的（「网站|权限」）。 */
    internal var permYes = setOf<String>()
    internal var permNo = setOf<String>()
    /** 用户长按了链接或图片，等浏览器页弹菜单。 */
    var pressed by mutableStateOf<Pressed?>(null)
    data class Pressed(val link: String?, val image: String?)
    /** 最近访问（只记用户自己看的）和书签，存在 browser 偏好里。 */
    var visits by mutableStateOf(listOf<BrowserPages.Visit>())
    var later by mutableStateOf(listOf<BrowserPages.Later>())
    var marks by mutableStateOf(listOf<BrowserPages.Visit>())
    /** 网页跟着 App 变深色（浅色主题时不变）。 */
    internal var darkAllowed = true
    /** 去广告开没开、哪些网站不去（网站名）。拦截在后台线程读，所以另存一份。 */
    var adBlock by mutableStateOf(true)
        internal set
    var adAllow by mutableStateOf(setOf<String>())
        internal set
    @Volatile internal var adBlockNow = true
    @Volatile internal var adAllowNow = setOf<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 这个网站名下拦不拦广告。 */
    private fun blocksAds(site: String) = adBlockNow && adAllowNow.none { site == it || site.endsWith(".$it") }

    // ---------- 网页脚本（像油猴脚本） ----------
    var scripts by mutableStateOf(listOf<com.jack.ai.api.UserScripts.Script>())
        internal set
    private val scriptList = kotlinx.serialization.builtins.ListSerializer(com.jack.ai.api.UserScripts.Script.serializer())
    private fun scriptsFile() = java.io.File(app.filesDir, "user_scripts.json")
    internal fun loadScripts() {
        scripts = runCatching { listJson.decodeFromString(scriptList, scriptsFile().readText()) }.getOrNull().orEmpty()
    }
    private fun saveScripts() { runCatching { scriptsFile().writeText(listJson.encodeToString(scriptList, scripts)) } }

    /** 新建或改一个（id 一样就是改）。返回存下的。 */
    fun saveScript(s: com.jack.ai.api.UserScripts.Script): com.jack.ai.api.UserScripts.Script {
        if (!::app.isInitialized) return s
        val clean = s.copy(name = s.name.trim().take(40).ifEmpty { "网页脚本" }, code = s.code.take(com.jack.ai.api.UserScripts.MAX_CODE))
        scripts = (if (scripts.any { it.id == clean.id }) scripts.map { if (it.id == clean.id) clean else it } else scripts + clean)
            .takeLast(com.jack.ai.api.UserScripts.MAX)
        saveScripts()
        return clean
    }

    fun removeScript(id: String) { scripts = scripts.filterNot { it.id == id }; saveScripts() }
    fun toggleScript(id: String) { scripts = scripts.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }; saveScripts() }

    /** 在这个网站上会跑的脚本。 */
    fun scriptsFor(url: String): List<com.jack.ai.api.UserScripts.Script> {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return emptyList()
        return scripts.filter { com.jack.ai.api.UserScripts.matches(it, host) }
    }

    /** 网页加载完：跑这个网站的脚本（每个脚本一页只跑一次）。 */
    private fun runScripts(t: Tab, view: WebView, url: String) {
        if (!BrowserPages.isWeb(url)) return
        val list = scriptsFor(url)
        if (list.isEmpty()) return
        list.forEach { sc -> view.evaluateJavascript(com.jack.ai.api.UserScripts.wrap(sc), null) }
        t.scriptsRan = list.map { it.name }
    }

    /** 改了脚本后让当前网页马上用上（重新打开这一页）。 */
    fun rerunScripts() = reloadCurrent()

    // ---------- 跟踪器、只用加密连接 ----------
    var trackBlock by mutableStateOf(true)
        internal set
    @Volatile internal var trackBlockNow = true
    fun changeTrackBlock(on: Boolean) {
        trackBlock = on; trackBlockNow = on
        prefs().edit().putBoolean("trackblock", on).apply()
        reloadCurrent()
    }
    private fun blocksTrackers(site: String) = trackBlockNow && adAllowNow.none { site == it || site.endsWith(".$it") }

    var httpsOnly by mutableStateOf(true)
        internal set
    /** 用户选过「仍然打开」的不加密网站（按网站记住）。 */
    internal var httpOk = setOf<String>()
    fun changeHttpsOnly(on: Boolean) {
        httpsOnly = on
        prefs().edit().putBoolean("httpsonly", on).apply()
    }

    /** 这个 http 网址要不要换成 https：开着「只用加密连接」、用户没放过这个网站。 */
    private fun upgradeOf(url: String): String? {
        if (!httpsOnly || BrowserNet.isLoginCallback(url)) return null
        val up = BrowserNet.httpsOf(url) ?: return null
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        return up.takeIf { httpOk.none { host == it || host.endsWith(".$it") } }
    }

    /** 加密打不开、用户选「仍然打开」：记住这个网站，用 http 打开。 */
    fun allowHttp(url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.").orEmpty()
        if (host.isNotEmpty()) { httpOk = httpOk + host; prefs().edit().putStringSet("httpok", httpOk).apply() }
        tab.httpFallback = null; tab.httpsTry = null
        return url
    }

    /** 加密打不开：出错页上给「仍然打开」。 */
    private fun httpsFailed(t: Tab, httpsUrl: String) {
        t.httpsTry = null
        t.httpFallback = BrowserNet.httpOf(httpsUrl)
        t.error = "这个网站不支持加密连接（HTTPS），为了安全没打开。不加密的网页，同一个网络里的人能看到、改掉你看的内容和填的东西。\n\n" +
            "确定要看就点「仍然打开」（这个网站以后不再拦）。"
        t.pageFailed = true
    }

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

    /** 开始记录：往网页开头加钩子（记点击、弹框、请求），清掉旧记录，刷新。 */
    fun startDiag() {
        val t = tab
        val w = t.web ?: return
        t.diag.clear()
        t.diagOn = true
        if (t.diagScript == null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            t.diagScript = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, BrowserDiag.HOOK_JS, setOf("*"))
        }
        t.diag.add("开始记录")
        w.reload()
    }

    /** 停止记录：去掉钩子（已经打开的这一页刷新后才干净）。 */
    fun stopDiag() {
        val t = tab
        t.diagScript?.remove()
        t.diagScript = null
        t.diagOn = false
        t.diag.add("停止记录")
    }

    /** 给开发者看的诊断报告。 */
    suspend fun diagnose(ctx: Context): String {
        val t = tab
        val pkg = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(ctx)
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val url = t.address
        val header = listOf(
            "App $ver，安卓 ${android.os.Build.VERSION.RELEASE}，${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "网页组件 ${pkg?.packageName.orEmpty()} ${pkg?.versionName.orEmpty()}",
            "线路 $route，代理" + (if (Net.config.enabled) "开" else "关") + "，电脑版" + (if (isDesktopAt(url)) "开" else "关") + (if (desktopAll) "（默认全开）" else "") +
                "，去广告" + (if (blocksAds(BrowserPages.site(url))) "开（这页拦了 ${t.blocked} 个）" else "关") + "，标签页 ${tabs.size} 个",
        )
        val page = withContext(Dispatchers.Main) {
            val w = t.web ?: return@withContext ""
            suspendCancellableCoroutine<String> { cont -> w.evaluateJavascript(BrowserDiag.SNAPSHOT_JS) { r -> if (cont.isActive) cont.resume(r ?: "") } }
        }
        return BrowserDiag.report(header, unwrap(page), t.diag.lines(), t.diagOn)
    }

    /** 当前网站是不是设了「总走代理」。 */
    fun proxyForcedHere() = Net.config.hardened && BrowserPages.site(address).let { s -> s.isNotEmpty() && proxySites.any { s == it || s.endsWith(".$it") } }

    /** 要不要拦 WebRTC 查 IP：加强保护开着、用户也没放开视频通话。 */
    fun rtcBlocked() = !webrtcAllowed && Net.config.hardened

    /** 当前网站「总走代理」开 / 关；设好后重新打开这一页。返回给用户看的一句话。 */
    suspend fun toggleProxySite(ctx: Context): String {
        val site = BrowserPages.site(address).ifEmpty { return "先打开一个网页" }
        val on = !proxyForcedHere()
        if (on && !BrowserNet.canForceProxy(site)) return "$site 是按后缀直连的（比如 .cn），没法单独让它走代理；要的话把线路换成「全部走代理」"
        proxySites = if (on) proxySites + site else proxySites.filterNot { site == it || site.endsWith(".$it") }.toSet()
        prefs().edit().putStringSet("proxysites", proxySites).apply()
        prepare(ctx)?.let { return it }
        withContext(Dispatchers.Main) { reloadCurrent() }
        return if (on) "$site 以后总走代理，它看不到你的真实 IP（购物、支付网站这样可能要重新登录或验证）" else "$site 恢复按规则走"
    }

    /** 允许 / 不许网页用 WebRTC：每个标签页装上或拆掉防泄漏脚本，再重新打开当前页。 */
    fun changeWebrtc(allowed: Boolean) {
        webrtcAllowed = allowed
        prefs().edit().putBoolean("webrtc", allowed).apply()
        applyRtc()
        reloadCurrent()
    }

    /** 每个标签页按现在的设置装上或拆掉防泄漏脚本。 */
    private fun applyRtc() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val block = rtcBlocked()
        tabs.forEach { t ->
            if (!block) { t.rtcScript?.remove(); t.rtcScript = null }
            else if (t.rtcScript == null) t.web?.let { w -> t.rtcScript = runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, RTC_JS, setOf("*")) }.getOrNull() }
        }
    }

    /** 设置里的「加强保护」开关变了：防泄漏脚本跟着装拆，当前页按新规则重新打开。在主线程调。 */
    fun protectionChanged() {
        if (tabs.isEmpty()) return
        applyRtc()
        appliedBypass = listOf("-")
        // 先按新规则重设代理（「总走代理」的网站进出直连名单），再重新打开；只 reload 的话这一页还按旧规则走
        scope.launch { if (prepare(app) == null) withContext(Dispatchers.Main) { reloadCurrent() } }
    }

    /**
     * 防 WebRTC 泄漏 IP：网页建的连接一律只走中继（iceTransportPolicy=relay），不收集本机和公网地址，
     * 所以查不到真实 IP；网页照样能用 WebRTC 的其他功能，只是没有中继服务器时连不上（视频通话会失败）。
     */
    internal const val RTC_JS = """(function(){if(window.__kaviRtc)return;window.__kaviRtc=1;
function relay(c){var o={};if(c)for(var k in c)o[k]=c[k];o.iceTransportPolicy='relay';return o;}
['RTCPeerConnection','webkitRTCPeerConnection'].forEach(function(n){var O=window[n];if(!O)return;
var W=function(c,x){return new O(relay(c),x);};W.prototype=O.prototype;
if(O.generateCertificate)W.generateCertificate=O.generateCertificate.bind(O);
var sc=O.prototype.setConfiguration;if(sc)O.prototype.setConfiguration=function(c){return sc.call(this,relay(c));};
try{Object.defineProperty(window,n,{value:W,writable:true,configurable:true});}catch(e){window[n]=W;}});})();"""

    fun adAllowedHere() = BrowserPages.site(address).let { s -> s.isNotEmpty() && adAllow.any { s == it || s.endsWith(".$it") } }

    internal fun reloadCurrent() {
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

    /** 用电脑版打开的网站（网站名，不带 www.）。只在没开「所有网站用电脑版」时用。 */
    var desktopSites by mutableStateOf(setOf<String>())
    /** 所有网站默认用电脑版（默认开：很多报名、办事网站手机上打不开或缺功能）；开着时 [mobileSites] 里的网站例外用手机版。 */
    var desktopAll by mutableStateOf(true)
        internal set
    /** 网页字号（百分比，所有网页通用）。 */
    var textZoom by mutableIntStateOf(100)
        internal set

    fun changeTextZoom(z: Int) {
        textZoom = z
        prefs().edit().putInt("textzoom", z).apply()
        tabs.forEach { it.web?.settings?.textZoom = z }
    }

    /** 往下翻网页时收起顶栏和底栏，往回翻一点就出来（JackWebView 按滚动方向改它）。 */
    var barsHidden by mutableStateOf(false)

    /** 从屏幕边缘滑：哪边（-1 左 = 后退，1 右 = 前进）和拉了多少（到 1 松手就跳）。 */
    var edgeSwipe by mutableStateOf<Pair<Int, Float>?>(null)

    /** 边缘滑到底松手：后退或前进。 */
    fun edgeGo(side: Int) {
        val w = web ?: return
        clearError()
        if (side < 0 && w.canGoBack()) w.goBack() else if (side > 0 && w.canGoForward()) w.goForward()
    }

    /** 拍当前标签页的缩略图（标签页列表用）：按三分之一大小画，没显示出来的不拍。 */
    fun snap(t: Tab = tab) {
        val w = t.web ?: return
        if (w.width <= 0 || w.height <= 0 || !w.isAttachedToWindow || t.address.isEmpty()) return
        val s = 0.25f
        runCatching {
            val bmp = android.graphics.Bitmap.createBitmap((w.width * s).toInt().coerceAtLeast(1), (w.height * s).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(bmp)
            c.scale(s, s)
            c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            t.thumb = bmp
        }
    }

    /** 暂停所有标签页里在放的声音和视频。 */
    fun pauseAll() {
        tabs.forEach { mediaControl(it.id, "stop"); it.mediaFrames.clear() }
        com.jack.ai.BrowserPlaybackService.stop()
    }
    fun mediaControl(id: Int, action: String) {
        if (action == "stop") tabs.find { it.id == id }?.mediaFromUser = false
        val fallback = if (action == "play") "" else BrowserPages.PAUSE_ALL_JS
        tabs.find { it.id == id }?.web?.evaluateJavascript("if(window.__kaviMediaCommand){window.__kaviMediaCommand('$action');}else{$fallback}", null)
    }
    /** 当前标签页的视频倍速。 */
    fun changeRate(r: Float) {
        tab.rate = r
        tab.web?.evaluateJavascript(BrowserPages.rateJs(r), null)
    }

    fun backgroundPlayback() {
        if (::app.isInitialized) tabs.lastOrNull { it.audible && it.mediaFromUser }?.let { com.jack.ai.BrowserPlaybackService.start(app, it) }
    }

    /** 电脑版放大多少（百分比，见 BrowserPages.desktopWidth）。 */
    var desktopZoom by mutableIntStateOf(100)
        internal set

    internal fun desktopWidth() = BrowserPages.desktopWidth(desktopZoom)

    /** 换电脑版放大比例：每个标签页的电脑版脚本按新宽度重装，当前是电脑版就重新打开。 */
    fun changeDesktopZoom(zoom: Int) {
        if (zoom == desktopZoom) return
        desktopZoom = zoom
        prefs().edit().putInt("deskzoom", zoom).apply()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) tabs.forEach { t ->
            val w = t.web ?: return@forEach
            runCatching { t.deskScript?.remove() }
            t.deskScript = runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, BrowserPages.desktopJs(desktopWidth()), setOf("*")) }.getOrNull()
        }
        val w = web ?: return
        if (address.isNotEmpty() && isDesktopAt(address)) { clearError(); w.reload() }
    }
    var mobileSites by mutableStateOf(setOf<String>())
        internal set

    /** 这个网址用不用电脑版。 */
    fun isDesktopAt(url: String): Boolean =
        if (BrowserPages.isMobileOnly(url)) false
        else if (desktopAll) BrowserPages.site(url).isNotEmpty() && !BrowserPages.isDesktop(mobileSites, url) else BrowserPages.isDesktop(desktopSites, url)
    /** 用户设了「总走代理」的网站（自动线路下也不直连，真实 IP 不暴露给它）。 */
    var proxySites by mutableStateOf(setOf<String>())
        internal set
    /** 允不允许网页用 WebRTC（视频通话要它，但它能绕过代理查到真实 IP）；默认不许。 */
    var webrtcAllowed by mutableStateOf(false)
        internal set
    /** 手机版的浏览器标识（去掉了 App 内嵌标记）；电脑版的由它换算。 */
    internal var mobileUa = ""

    /** 下载在后台跑，离开浏览器页也不停。 */
    internal val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Kavi 最近一次操作网页的时刻；聊天页据此留一个放网页窗口的落点（见 BrowserHiddenHost）。 */
    var agentAt by mutableLongStateOf(0L)
    /** 用户最后一次碰网页的时间（弹窗是不是点出来的，要看它）。 */
    @Volatile var touchedAt = 0L
    /** 网页窗口换了地方（浏览器页 ↔ 聊天页）就加一，让原来的地方重新来要。 */
    var hostGen by mutableIntStateOf(0)
    /** Kavi 在等用户亲手做的事（登录、人机验证），回答里的网页画面上会写出来；null = 没在等。 */
    var waitingUser by mutableStateOf<String?>(null)

    /** 对话里正显示着 Kavi 操作网页的实时画面（这时聊天页那个看不见的落点就不用了）。 */
    var inlineHosts by mutableIntStateOf(0)
    /** 用户正开着浏览器页。聊天页放网页窗口的地方也看它：浏览器页在前台时不来抢眼前这一页。 */
    var userViewing by mutableStateOf(false)

    /**
     * 聊天页（回答里的实时画面、看不见的落点）现在能不能把这个网页窗口拿过去：
     * 用户正开着浏览器页、它又是眼前那一页时不行——拿走的话浏览器页里就只剩一块空底。
     */
    fun chatMayHost(w: WebView?): Boolean = !(userViewing && w != null && w === tabs.getOrNull(current)?.web)

    internal val web: WebView? get() = tab.web
    internal lateinit var wrapper: MutableContextWrapper
    internal lateinit var app: Context
    /** 拆到别的文件的扩展函数看不到 lateinit 有没有赋值，用这两个问。 */
    internal val appReady get() = ::app.isInitialized
    internal val wrapperReady get() = ::wrapper.isInitialized

    /** 网页上次是按哪个代理端口设的（0 = 直连）；内核重启换了端口就要重设。 */
    private var appliedPort = -1
    private var appliedRoute = ""
    private var appliedBypass = emptyList<String>()
    private var appliedReverse = false
    internal var pageDone: CompletableDeferred<Unit>? get() = tab.pageDone; set(v) { tab.pageDone = v }
    /** 最近给代理口令的时刻：10 秒内要了 30 次以上，说明口令没被接受。 */
    private val authTimes = ArrayDeque<Long>()

    fun agentActive(now: Long = System.currentTimeMillis()) = web != null && now - agentAt < 5 * 60_000

    // ---------- 网页窗口 ----------

    /** 拿到（第一次就建好）网页窗口。只能在主线程调。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context): WebView {
        web?.let { return it }
        // 眼前这页在休眠：补开网页窗口，不另开新页
        if (::wrapper.isInitialized && tab in tabs) { wake(tab); web?.let { return it } }
        // 已经初始化过（只是这一页没有网页窗口，比如 Kavi 用着的那页被关了）：只补开一页。
        // 再走一遍初始化会换掉 wrapper（别的标签页的弹窗、下拉框就没了 Context）、清掉开着的隐身页
        if (::wrapper.isInitialized) return newTab().web!!
        app = ctx.applicationContext
        route = app.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("route", BrowserNet.AUTO) ?: BrowserNet.AUTO
        // 用可换底的 Context：放进浏览器页时换成那个页面（下拉框、弹窗要它），离开后换回 App
        wrapper = MutableContextWrapper(app)
        loadLists()
        // 上次没关干净的隐身数据（App 被杀掉之类）
        runCatching { clearIncognito() }
        // 系统 WebView 太旧：新式网页（ChatGPT 的登录框这类）会点了没反应，提醒用户去更新
        androidx.webkit.WebViewCompat.getCurrentWebViewPackage(app)?.versionName?.substringBefore('.')?.toIntOrNull()?.let { v ->
            if (v < OLD_WEBVIEW) notice = "手机的系统网页组件（Android System WebView）是 $v 版，比较旧，有些网站的弹窗、按钮会点了没反应。到应用商店搜「Android System WebView」或「Chrome」更新一下。"
        }
        return newTab().web!!
    }

    /** 低于这个版本的系统 WebView 算旧。 */
    private const val OLD_WEBVIEW = 120

    /**
     * 新开一个标签页（switch 就切过去）；incognito 开隐身的（网页组件不支持时开普通的）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(switch: Boolean = true, incognito: Boolean = false, account: Int = 0, group: String = "", build: Boolean = true): Tab {
        val ok = incognitoSupported()
        val t = Tab(nextTabId++, incognito && ok, if (ok && !incognito) account.coerceIn(0, ACCOUNTS) else 0)
        t.group = group
        if (build) t.web = buildWeb(t)
        tabs += t
        // Kavi 在后台开的（switch = false）不在这里收别的页：它刚开的页还没登记成 Kavi 的，可能被误收
        if (switch) switchTo(tabs.lastIndex)
        return t
    }

    // ---------- 网页要用相机、麦克风、位置 ----------

    // ---------- 关掉 App 再打开时恢复标签页 ----------

    internal val savedTabs = BrowserPages.SavedTabs.serializer()

    // ---------- 地址栏联想 ----------

    /** 最多开几个标签页（每个都是一个网页窗口，占内存）。 */
    const val MAX_TABS = 50

    // ---------- 标签页休眠 ----------
    /** 最多几个标签页同时开着网页（别的休眠，切过去再打开）。 */
    const val LIVE_MAX = 6

    internal val listJson = Json { ignoreUnknownKeys = true }
    internal val laterList = kotlinx.serialization.builtins.ListSerializer(BrowserPages.Later.serializer())

    internal val visitList = kotlinx.serialization.builtins.ListSerializer(BrowserPages.Visit.serializer())

    /** 手机版原本的新式设备标识（第一个网页窗口建好时记下）；网页组件不支持改它时是 null。 */
    internal var mobileMeta: androidx.webkit.UserAgentMetadata? = null

    // ---- 长的浏览记录 ----
    internal val historyIo = Dispatchers.IO.limitedParallelism(1)
    @Volatile internal var historyList: List<com.jack.ai.data.browser.HistoryEntry>? = null
    internal var historySave: kotlinx.coroutines.Job? = null

    // ---- 屏蔽网页上的某一块 ----
    internal val blockMap = kotlinx.serialization.builtins.MapSerializer(String.serializer(), kotlinx.serialization.builtins.ListSerializer(String.serializer()))
    /** 网站 → 要藏起来的选择器。 */
    var blocks by mutableStateOf(mapOf<String, List<String>>())
    /** 正在选要屏蔽的那块；picked 是点中的。 */
    var picking by mutableStateOf(false)
    var picked by mutableStateOf<BrowserBlocks.Picked?>(null)

    // ---- 演示教学 ----
    /** 正在记用户的操作；teachSteps 是记下的步骤。 */
    var teaching by mutableStateOf(false)
    var teachSteps by mutableStateOf(listOf<String>())

    // ---------- 离线网页（整页存成 MHTML） ----------
    var offline by mutableStateOf(listOf<BrowserPages.OfflinePage>())
        internal set
    internal val offlineList = kotlinx.serialization.builtins.ListSerializer(BrowserPages.OfflinePage.serializer())

    // ---------- 这个网站的数据 ----------
    /** 一个网站（主域名）在这个标签页的资料夹里存了什么。 */
    data class SiteData(val root: String, val cookies: Int, val storage: Long, val origins: List<String>)

    // ---------- 打开网页（用户和 Kavi 共用） ----------

    /** 按线路设好代理再执行 then；走代理但代理起不来就不执行，返回原因。 */
    suspend fun prepare(ctx: Context): String? {
        withContext(Dispatchers.Main) { webView(ctx) }
        // 「总走代理」的网站是加强保护的一部分：关着时照原来的规则走
        val plan = withContext(Dispatchers.IO) { BrowserNet.plan(route, always = if (Net.config.hardened) proxySites else emptySet()) }
        return withContext(Dispatchers.Main) {
            when (plan) {
                is BrowserNet.Plan.Blocked -> plan.reason
                BrowserNet.Plan.Direct -> apply(0, emptyList())
                is BrowserNet.Plan.Proxy -> apply(plan.port, plan.bypass, plan.reverse)
            }
        }
    }

    private suspend fun apply(port: Int, bypass: List<String>, reverse: Boolean = false): String? {
        // 只让 OpenAI 走代理要「反向」规则，旧的网页组件没有：那就照旧直连
        if (reverse && !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) return apply(0, emptyList())
        if (port == appliedPort && route == appliedRoute && bypass == appliedBypass && reverse == appliedReverse) return null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (port == 0) { appliedPort = 0; appliedRoute = route; return null }
            return "这台手机的网页组件版本太旧，不能单独给浏览器设代理。到应用商店更新「Android System WebView」后再试。"
        }
        val controller = ProxyController.getInstance()
        suspendCancellableCoroutine { cont ->
            val done = Runnable { if (cont.isActive) cont.resume(Unit) }
            if (port == 0) controller.clearProxyOverride({ it.run() }, done)
            else controller.setProxyOverride(
                ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").apply {
                    bypass.forEach { addBypassRule(it) }
                    if (reverse) setReverseBypassEnabled(true)
                }.build(),
                { it.run() }, done,
            )
        }
        appliedPort = port; appliedRoute = route; appliedBypass = bypass; appliedReverse = reverse
        return null
    }

    /** 浏览器关了、Kavi 也有一阵没用：撤掉代理设置，小工具的网页窗口照旧直连。 */
    fun clearProxyIfIdle() {
        if (userViewing || agentActive()) return
        if (appliedPort > 0 && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
            appliedPort = -1
        }
    }

    /** 打开网址；等到加载完（最多 timeoutMs）。返回出错原因，成功返回 null。 */
    suspend fun load(ctx: Context, url: String, timeoutMs: Long = 25_000, agent: Boolean = true): String? {
        // 并行抓取时在指定的标签页里开，平时在当前页
        val t = withContext(Dispatchers.Main) { pinned()?.also { wake(it) } ?: webView(ctx).let { tab } }
        // 恢复的标签页等真开始加载了才算恢复完：半路被打断（页面关掉、代理没起来）下次切过来还会再开，不会一直空白
        withContext(Dispatchers.Main) { t.clearError() }
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.OPEN -> {}
            BrowserNet.Kind.EXTERNAL -> return "这是打开别的 App 的链接，浏览器里打不开：$url"
            BrowserNet.Kind.BLOCKED -> return "不能打开这个地址（本机、局域网和文件地址都不开）：$url"
        }
        // 设代理、起代理内核可能要几秒，这期间网页窗口还没动静：先让进度条露出来，不然像是点了没反应
        withContext(Dispatchers.Main) { if (t.loading >= 100) t.loading = STARTING }
        try {
            return loadPrepared(ctx, t, url, timeoutMs, agent)
        } finally {
            // 没真开始加载就结束了（代理没起来、被取消）：进度条收回去
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { if (t.loading == STARTING) t.loading = 100 }
        }
    }

    private suspend fun loadPrepared(ctx: Context, t: Tab, url: String, timeoutMs: Long, agent: Boolean): String? {
        prepare(ctx)?.let { reason -> withContext(Dispatchers.Main) { t.error = reason; t.pageFailed = true }; return reason }
        // 节奏和停手只管 Kavi 自己的操作；用户在地址栏打开网页（包括去处理验证）不拦也不等
        if (agent) {
            if (BrowserPacing.blocked(t, url) != null) return "网站已提示访问受限，先交给用户处理，暂停同站打开新页面。"
            BrowserPacing.before(t, url)
        }
        BrowserCdp.observeNetwork(t)
        val done = withContext(Dispatchers.Main) {
            CompletableDeferred<Unit>().also { d ->
                val w = t.web ?: webView(ctx)
                // 休眠醒来：连前进后退记录一起恢复（恢复不了就直接打开）
                val state = t.sleepState?.takeIf { t.restore == url }
                t.pageDone = d; t.restore = null; t.sleepState = null
                applyUa(w, url)
                val go = upgradeOf(url)?.also { t.httpsTry = it } ?: url.also { if (it != t.httpFallback) t.httpsTry = null }
                if (state == null || runCatching { w.restoreState(state) }.getOrNull() == null) w.loadUrl(go)
            }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        BrowserPacing.seen(t, withContext(Dispatchers.Main) { t.address })
        return withContext(Dispatchers.Main) { t.error }
    }

    /** [load] 刚开始、网页窗口还没报进度时给进度条的值（网页窗口自己报的从 10 起）。 */
    private const val STARTING = 3

    fun clearData() {
        web ?: return
        // 别的标签页也关掉：它们的网页和记录同样要清
        for (i in tabs.indices.reversed()) if (i != current) closeTab(i)
        val w = web ?: return
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        // 分身的登录也一起清
        if (incognitoSupported()) (1..ACCOUNTS).forEach { n ->
            androidx.webkit.ProfileStore.getInstance().getProfile(accountProfile(n))?.let { p -> p.cookieManager.removeAllCookies(null); p.webStorage.deleteAllData() }
        }
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(app).apply { clearHttpAuthUsernamePassword(); clearFormData() }
        w.clearCache(true)
        w.clearHistory()
        w.clearFormData()
        w.loadUrl("about:blank")
        address = ""; title = ""; tab.restore = null
        visits = emptyList(); saveLists()
        // 记住的网站权限（相机、麦克风、位置）也清掉
        permYes = emptySet(); permNo = emptySet()
        prefs().edit().remove("permyes").remove("permno").remove("tabs").apply()
        // 长的浏览记录也清掉
        historySave?.cancel()
        scope.launch(historyIo) { historyList = emptyList(); com.jack.ai.data.Store(app).saveHistory(emptyList()) }
        notice = "已清除：Cookie（登录状态）、缓存、网页存储、浏览记录、最近访问和网站权限（书签留着）。"
    }

    private fun describe(code: Int, desc: String): String = when {
        "PROXY" in desc && "AUTH" in desc -> "代理不接受浏览器给的口令（$desc）。把这个提示告诉开发者。"
        "PROXY" in desc || "TUNNEL" in desc -> "连不上 Kavi 专用代理（$desc）。" + (ProxyCore.lastError?.let { "代理：$it" } ?: "可以点刷新重试，或到设置里测试代理。")
        code == WebViewClient.ERROR_HOST_LOOKUP -> "找不到这个网站（$desc）。" + if (route == BrowserNet.DIRECT) "国外网站可以把线路换成「自动」或「全部走代理」。" else ""
        code == WebViewClient.ERROR_TIMEOUT || code == WebViewClient.ERROR_CONNECT ->
            "连接失败（$desc）。" + if (route == BrowserNet.DIRECT || !Net.config.enabled) "国外网站需要开 Kavi 专用代理。" else "节点可能不通，到设置里测试一下代理。"
        else -> "打不开（$desc）"
    }

    internal class Client(val t: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (BrowserNet.classify(url)) {
                BrowserNet.Kind.OPEN -> {
                    if (!request.isForMainFrame) false
                    // 只用加密连接：跳到 http 的换成 https；换过一次又被网站跳回 http，就是不支持加密
                    else if (upgradeOf(url) != null) {
                        val up = upgradeOf(url)!!
                        if (t.httpsTry == up) { t.diag.add("网站从 https 又跳回 http：$url"); httpsFailed(t, up) }
                        else { t.httpsTry = up; view.post { view.loadUrl(up) } }
                        true
                    }
                    // 网页没经过用户点击就自己跳到广告网站：不去
                    else if (!request.hasGesture() && BrowserAds.isAd(request.url.host.orEmpty()) && blocksAds(t.site)) { t.diag.add("没让网页自己跳到广告网站 $url"); true }
                    else {
                        t.clearError()
                        // 从手机版网站跳到电脑版网站（或反过来）：换好标识重新打开这个网址。
                        // 不能在这个回调里直接换：网页组件正处在跳转当中，换标识会让它马上重载，跳转里套跳转，
                        // 新版网页组件直接断言崩掉（SIGTRAP @ setUserAgentString）。先拦下，等回调返回后再换、再打开
                        if (needsUa(view, url)) { view.post { if (applyUa(view, url)) view.loadUrl(url) }; true } else false
                    }
                }
                BrowserNet.Kind.EXTERNAL -> { t.diag.add("网页要打开别的 App：${url.take(120)}"); if (userViewing && request.hasGesture()) external = url; true }
                BrowserNet.Kind.BLOCKED -> { t.diag.add("拦下：本机或局域网地址 $url"); if (request.isForMainFrame) t.error = "已拦下：网页想打开本机、局域网或文件地址（$url）"; true }
            }
        }

        // 网页里的图片、脚本、请求也不许碰本机和局域网
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host ?: return null
            // 正在等的登录回调（ChatGPT 登录跳回本机）照常放行，其余本机地址一律拦
            if (request.url.scheme in setOf("http", "https", "ws", "wss") && BrowserNet.isLocal(host) && !BrowserNet.isLoginCallback(request.url.toString())) {
                t.diag.add("拦下请求：本机或局域网 $host")
                return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            // 播放器拉的视频、音频记下来（存视频时用），最多记 40 条
            if (!request.isForMainFrame) MediaGrab.kindOf(request.url.toString(), request.requestHeaders["Range"] ?: request.requestHeaders["range"])?.let { kind ->
                val url = request.url.toString()
                val key = MediaGrab.keyOf(url)
                t.media.removeIf { MediaGrab.keyOf(it.url) == key }
                t.media.addLast(MediaGrab.Hit(url, kind, System.currentTimeMillis()))
                while (t.media.size > 40) t.media.pollFirst()
            }
            // 去广告：网页里的广告和跟踪请求直接不发（网页本身不拦）
            if (!request.isForMainFrame && BrowserAds.isTracker(host) && blocksTrackers(t.site)) {
                t.diag.add("拦下跟踪器 $host")
                val n = t.trackersNow.incrementAndGet()
                main.post { if (t.trackersNow.get() == n) t.trackers = n }
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            if (!request.isForMainFrame && BrowserAds.isAd(host) && blocksAds(t.site)) {
                t.diag.add("去广告拦下请求 $host")
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
                t.error = "浏览器没能通过 Kavi 专用代理的口令验证。把这个提示告诉开发者。"
            }
            // 网站自己要的账号密码（很少见）：不支持，取消
            t.diag.add("要账号密码的请求，取消了：$host（$realm）")
            handler.cancel()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: SslError) {
            // 证书不对一律不继续：可能是假网站或被劫持
            handler.cancel()
            t.diag.add("证书有问题，没打开：${e.url}（${e.primaryError}）")
            if (e.url == t.httpsTry) httpsFailed(t, e.url)
            else if (e.url == view.url || e.url == t.address) { t.error = "这个网站的安全证书有问题，已停止打开（${e.url}）"; t.pageFailed = true }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
            t.diag.add((if (request.isForMainFrame) "网页没打开" else "加载失败") + "（${e.errorCode} ${e.description}）${request.url.toString().take(160)}")
            if (request.isForMainFrame) {
                val u = request.url.toString()
                // 自动换成 https 后连不上（不是没网）：多半是网站不支持加密
                if (u == t.httpsTry && e.errorCode != WebViewClient.ERROR_HOST_LOOKUP && e.errorCode != WebViewClient.ERROR_TIMEOUT) httpsFailed(t, u)
                else { t.error = describe(e.errorCode, e.description?.toString().orEmpty()); t.pageFailed = true }
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: WebResourceResponse) {
            if (request.isForMainFrame) t.mainHttpStatus = r.statusCode
            t.diag.add((if (request.isForMainFrame) "网页 " else "请求 ") + "${r.statusCode} ${request.method} ${request.url.toString().take(160)}")
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            t.diag.add("网页进程没了（" + (if (detail.didCrash()) "崩溃" else "被系统收回内存") + "）")
            // 这个网页窗口已经不能用了：换新的接着用，返回 true 告诉系统已经处理（不然整个 App 被关掉）
            if (t.web === view) recover(t, view) else runCatching { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            BrowserCdp.forget(t)
            BrowserNetwork.navigated(t.id)
            t.mainHttpStatus = null
            t.icon = favicon
            if (url.startsWith("http://") || url.startsWith("https://")) BrowserPacing.navigated(t, url)
            else BrowserPacing.release(t.id)
            if (t === tab) barsHidden = false
            t.diag.add("开始打开 ${url.take(160)}")
            if (t.pageDone?.isCompleted != false) t.pageDone = CompletableDeferred()
            t.translated = false
            t.media.clear()
            t.mediaFrames.clear()
            t.mediaFromUser = false
            t.address = shownAddress(t, url)
            t.site = BrowserPages.site(url)
            t.blockedNow.set(0); t.blocked = 0
            t.trackersNow.set(0); t.trackers = 0
            if (url != t.httpsTry && url != t.httpFallback) t.httpFallback = null
            t.scriptsRan = emptyList()
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            t.fresh = false
            // 不支持开头注入的旧网页组件：页面露出来时补上防泄漏（晚了一点，聊胜于无）
            if (rtcBlocked() && t.rtcScript == null) view.evaluateJavascript(RTC_JS, null)
            // 不支持开头注入的旧网页组件：页面露出来时补装钩子
            if (t.diagOn && t.diagScript == null) view.evaluateJavascript(BrowserDiag.HOOK_JS, null)
            if (t.netScript == null) view.evaluateJavascript(BrowserNetWatch.JS, null)
            view.evaluateJavascript(BrowserPages.KEEP_PLAYING_JS + BrowserPages.MEDIA_JS + if (t.rate != 1f) BrowserPages.rateJs(t.rate) else "", null)
            widenIfDesktop(view, url)
            if (blocksAds(t.site)) view.evaluateJavascript(BrowserAds.INJECT_JS, null)
            applyBlocks(view, t.site)
        }

        override fun onPageFinished(view: WebView, url: String) {
            t.fresh = false
            if (!t.pageFailed && url == t.httpsTry) t.httpsTry = null
            view.evaluateJavascript(BrowserPages.KEEP_PLAYING_JS + BrowserPages.MEDIA_JS + if (t.rate != 1f) BrowserPages.rateJs(t.rate) else "", null)
            if (!t.pageFailed) runScripts(t, view, url)
            widenIfDesktop(view, url)
            if (blocksAds(t.site)) view.evaluateJavascript(BrowserAds.INJECT_JS, null)
            t.pageDone?.complete(Unit)
            t.diag.add("加载完 ${url.take(160)}")
            applyBlocks(view, t.site)
            // 选要屏蔽的东西时网页跳了：新页面上接着能选
            if (picking && t === tab) { picked = null; view.evaluateJavascript(BrowserBlocks.PICK_JS, null) }
            if (teaching && t === tab && BrowserPages.isWeb(url)) teachSteps = BrowserTeach.append(teachSteps, "→ 到了「${view.title.orEmpty().take(40)}」$url")
            t.address = shownAddress(t, url)
            if (t.address.isEmpty()) t.title = ""
            // 最近访问只记用户自己看的、打开成功的
            if (userViewing && !t.pageFailed && !t.incognito && BrowserPages.isWeb(url)) {
                visits = BrowserPages.visit(visits, url, view.title.orEmpty().takeUnless { it == url }.orEmpty(), System.currentTimeMillis())
                saveLists()
            }
            // 长的浏览记录：用户和 Kavi 打开的都记（隐身不记），Kavi 能按关键词翻
            if (!t.pageFailed && !t.incognito && BrowserPages.isWeb(url)) recordHistory(url, view.title.orEmpty().takeUnless { it == url }.orEmpty())
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url != t.address) {
                BrowserCdp.forget(t)
                BrowserNetwork.navigated(t.id)
                BrowserPacing.navigated(t, url)
            }
            t.address = shownAddress(t, url)
            if (t.address.isEmpty()) t.title = ""
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }
    }

    internal class Chrome(val t: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            t.loading = newProgress
            if (newProgress >= 100 && t.address.isNotEmpty()) t.fresh = false
        }
        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            val text = m.message().orEmpty()
            when {
                text.startsWith(BrowserPages.MEDIA_PREFIX) -> {
                    runCatching { json.parseToJsonElement(text.removePrefix(BrowserPages.MEDIA_PREFIX)).jsonObject }.getOrNull()?.let { o ->
                        val id = (o["id"] as? JsonPrimitive)?.content ?: return@let
                        val wasPlaying = t.audible
                        t.mediaFrames.entries.removeAll { System.currentTimeMillis() - it.value.second > 5000 }
                        t.mediaFrames[id] = ((o["on"] as? JsonPrimitive)?.content == "true") to System.currentTimeMillis()
                        if (t.audible && userViewing && t === tab) t.mediaFromUser = true
                        if (!wasPlaying && t.audible && t.mediaFromUser && !userViewing) com.jack.ai.BrowserPlaybackService.start(app, t)
                    }
                }
                text.startsWith(BrowserDiag.PREFIX) -> t.diag.add(text.removePrefix(BrowserDiag.PREFIX))
                text.startsWith(BrowserBlocks.PREFIX) -> if (picking && t === tab) BrowserBlocks.parsePick(text)?.let { picked = it }
                text.startsWith(BrowserTeach.PREFIX) -> if (teaching) BrowserTeach.stepOf(text)?.let { teachSteps = BrowserTeach.append(teachSteps, it) }
                m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                    t.diag.add("脚本出错：$text（${m.sourceId().orEmpty().substringAfterLast('/').take(60)}:${m.lineNumber()}）")
            }
            return false
        }
        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { t.icon = icon }
        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            val url = view.url.orEmpty()
            // 空白页的「标题」就是 about:blank，不显示
            t.title = if (shownAddress(t, url).isEmpty() && url.isNotEmpty()) "" else newTitle.orEmpty()
            if (newTitle != null && newTitle != url) BrowserPages.retitle(visits, url, newTitle).let { if (it !== visits) { visits = it; saveLists() } }
        }
        /** 网页要弹新窗口：用户点的、或 Kavi 刚点过（登录窗口）才开，开在新标签页并切过去；网页自己弹的（多半是广告）不开。 */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            val now = System.currentTimeMillis()
            // 登录窗口常是点完、等网页请求回来才弹，标记里就不算「点出来的」：刚碰过网页（用户或 Kavi）也开
            if (!isUserGesture && now - agentAt > 5_000 && now - touchedAt > 5_000) { t.diag.add("网页要弹新窗口，不是点出来的，没开"); return false }
            t.diag.add("网页弹新窗口，开成新标签页")
            if (tabs.size >= MAX_TABS) { notice = "标签页开满了，网页要弹的窗口没打开。先关掉几个标签页再试。"; return false }
            // 弹它的是 Kavi 的后台标签页：新窗口也开在后台、交给那个回答，不换掉用户眼前的
            val agentTab = com.jack.ai.tools.BrowserLock.isAgentTab(t)
            val nt = newTab(switch = !agentTab, incognito = t.incognito, account = t.account)
            if (agentTab) com.jack.ai.tools.BrowserLock.repin(t, nt)
            if (teaching) installTeach(nt)
            nt.openedBy = t.id
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = nt.web ?: return false
            resultMsg.sendToTarget()
            return true
        }

        /** 弹出的窗口自己关了（登录完常这样）：关掉那个标签页，回到弹它的那一页。 */
        override fun onCloseWindow(window: WebView) {
            val closed = tabs.firstOrNull { it.web === window } ?: return
            val back = tabs.firstOrNull { it.id == closed.openedBy }
            val agentTab = com.jack.ai.tools.BrowserLock.isAgentTab(closed)
            closeTab(closed)
            if (!agentTab) back?.let { b -> tabs.indexOf(b).takeIf { it >= 0 }?.let { switchTo(it) } }
        }

        // 视频全屏：交给浏览器页盖满屏幕
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!userViewing) { callback.onCustomViewHidden(); return }
            fullscreen = view to callback
        }
        override fun onHideCustomView() { fullscreen = null }
        // 网页要选文件上传：只有用户开着浏览器页时才弹选择器（Kavi 操作时不替用户选文件）
        override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
            // Kavi 正在上传：直接把准备好的文件交给网页
            agentUpload?.let { uri -> agentUpload = null; uploadTaken = true; callback.onReceiveValue(arrayOf(uri)); return true }
            val c = chooser
            return if (userViewing && c != null) c(callback, params) else false
        }
        // 网页要相机、麦克风：用户看着时问一次（按网站记住）；受保护视频（DRM）的播放许可直接给，和手机浏览器一样
        override fun onPermissionRequest(request: PermissionRequest) {
            val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            val kinds = BrowserPages.mediaKinds(request.resources)
            val media = request.resources.filter { BrowserPages.mediaKinds(arrayOf(it)).isNotEmpty() }
            fun finish(ok: Boolean) {
                val give = drm + (if (ok) media else emptyList())
                if (give.isEmpty()) request.deny() else request.grant(give.toTypedArray())
            }
            if (kinds.isEmpty()) { finish(false); return }
            askPerm(t, request.origin.toString(), kinds) { ok -> finish(ok) }
        }
        override fun onPermissionRequestCanceled(request: PermissionRequest) { permAsk = null }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
            askPerm(t, origin.orEmpty(), setOf(BrowserPages.LOCATION)) { ok -> callback.invoke(origin, ok, false) }
        override fun onGeolocationPermissionsHidePrompt() { permAsk = null }
        // 网页弹的 alert/confirm：Kavi 操作时没人点，直接按「确定」的默认处理会卡住页面，所以都按取消
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

    /** Kavi 操作时网页弹出的提示框内容（已自动关掉），下次读页面时告诉它。 */
    @Volatile internal var lastDialog: String? = null

    // ---------- 下载 ----------

    /** 在网页里装好：晚两分钟再收回网页生成的文件；记下链接上写的下载文件名（blob: 网址里没有名字）。 */
    internal const val KEEP_BLOB_JS = """(function(){if(window.__jackKeep)return;window.__jackKeep=1;
try{var rv=URL.revokeObjectURL;URL.revokeObjectURL=function(u){setTimeout(function(){try{rv.call(URL,u);}catch(e){}},120000);};}catch(e){}
function note(a){try{var h=a&&a.href;if(h&&(h.indexOf('blob:')===0||h.indexOf('data:')===0))window.__jackLastDl={href:h.slice(0,200),name:a.getAttribute('download')||''};}catch(e){}}
try{var ck=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){note(this);return ck.apply(this,arguments);};
var de=EventTarget.prototype.dispatchEvent;EventTarget.prototype.dispatchEvent=function(e){if(this instanceof HTMLAnchorElement&&e&&e.type==='click')note(this);return de.apply(this,arguments);};}catch(e){}
document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[href]');if(a)note(a);},true);})();"""

    /** 网页生成的文件最多读多大（要整个经过网页的内存，太大会卡死网页）。 */
    const val BLOB_LIMIT = 200L * 1024 * 1024

    /** 当前页面上的视频、音频：网页里的播放器（地址、时长、大小、在不在放）、B 站的播放信息，加上拦截到的媒体请求。 */
    data class MediaPage(val title: String, val url: String, val elements: List<JsonObject>, val playinfo: String?, val hits: List<MediaGrab.Hit>, val tab: Tab)

    // ---------- Kavi 操作网页 ----------

    internal val json = Json { ignoreUnknownKeys = true }

    /** 页面的一项可操作元素。坐标是网页像素（CSS px），截图上画框用。 */
    data class Item(val n: Int, val kind: String, val label: String, val value: String?, val checked: Boolean?,
                    val options: List<String>, val href: String?, val x: Float, val y: Float, val w: Float, val h: Float)

    data class Page(val url: String, val title: String, val items: List<Item>, val above: Int, val below: Int,
                    val scroll: Int, val height: Int, val vh: Int, val text: String,
                    /** 可视区在网页里的位置和宽度（CSS px），截图时把元素坐标换成屏幕像素。 */
                    val vx: Float, val vy: Float, val vw: Float, val error: String?, val dialog: String?,
                    /** 网页要下载、等确认的文件。 */
                    val download: BrowserDownload.Pending? = null,
                    /** 读不到里面的嵌入框（别的网站的登录、支付框）有几个。 */
                    val frames: Int = 0)

    /** Kavi 要上传的文件：网页弹出选文件时直接交给它（不弹选择器）。uploadTaken 表示网页已经拿走了。 */
    @Volatile var agentUpload: android.net.Uri? = null
    @Volatile var uploadTaken = false

    /**
     * 把一段操作钉在某个标签页上（并行抓取、几个回答同时用浏览器时各干各的）：这段协程跑着的时候，
     * [tab] 和跟着它的 address、title、网页窗口都是这一页。t 会变：在钉住的操作里切标签、网页弹登录窗口时换过去。
     */
    class PinTab(@Volatile var t: Tab) : kotlinx.coroutines.ThreadContextElement<PinTab?> {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<PinTab>
        override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
        override fun updateThreadContext(context: kotlin.coroutines.CoroutineContext): PinTab? =
            pinLocal.get().also { pinLocal.set(this) }
        override fun restoreThreadContext(context: kotlin.coroutines.CoroutineContext, oldState: PinTab?) = pinLocal.set(oldState)
    }

}

/** 网页进程没了以后这个网页窗口就不能用了：从界面上拿下来、销毁，告诉系统已经处理（返回 true）。 */
internal fun WebView.dropAfterRendererGone(): Boolean {
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { destroy() }
    return true
}
