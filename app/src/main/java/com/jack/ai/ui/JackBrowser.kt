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

        fun clearError() { error = null; pageFailed = false }
    }

    val tabs = androidx.compose.runtime.mutableStateListOf<Tab>()

    /** 网页里选中文字点「问 Kavi」时调（浏览器页开着时才有）。 */
    var askSelection: ((String) -> Unit)? = null

    private const val INCOGNITO = "jack-incognito"

    /** 网页组件支不支持隐身和分身（分开的 Cookie 和存储，要较新的 WebView）。 */
    fun incognitoSupported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** 最多几个分身。 */
    const val ACCOUNTS = 3
    private fun accountProfile(n: Int) = "jack-account-$n"

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
    private fun clearIncognito() {
        if (!incognitoSupported()) return
        val store = androidx.webkit.ProfileStore.getInstance()
        if (runCatching { store.deleteProfile(INCOGNITO) }.getOrDefault(false)) return
        store.getProfile(INCOGNITO)?.let { p -> p.cookieManager.removeAllCookies(null); p.webStorage.deleteAllData() }
    }
    var current by mutableIntStateOf(0)
        private set
    private var nextTabId = 0
    /** 还没建网页窗口时，界面读到的空白页。 */
    private val blank = Tab(-1)
    /**
     * 当前这一页。Kavi 在自己的后台标签页里做事时（[PinTab] 钉住的那段操作里），读到的是它那一页，
     * 下面的 address、title、error 这些也跟着是那一页；界面和用户看的始终是 tabs[current]。
     */
    private val pinLocal = ThreadLocal<PinTab?>()
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
        private set
    fun changeSearchEngine(engine: String) {
        searchEngine = engine
        prefs().edit().putString("searchEngine", engine).apply()
    }
    var route by mutableStateOf(BrowserNet.AUTO)
        private set
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
    private var permYes = setOf<String>()
    private var permNo = setOf<String>()
    /** 用户长按了链接或图片，等浏览器页弹菜单。 */
    var pressed by mutableStateOf<Pressed?>(null)
    data class Pressed(val link: String?, val image: String?)
    /** 最近访问（只记用户自己看的）和书签，存在 browser 偏好里。 */
    var visits by mutableStateOf(listOf<BrowserPages.Visit>())
    var later by mutableStateOf(listOf<BrowserPages.Later>())
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

    /** 用电脑版打开的网站（网站名，不带 www.）。只在没开「所有网站用电脑版」时用。 */
    var desktopSites by mutableStateOf(setOf<String>())
    /** 所有网站默认用电脑版（默认开：很多报名、办事网站手机上打不开或缺功能）；开着时 [mobileSites] 里的网站例外用手机版。 */
    var desktopAll by mutableStateOf(true)
        private set
    /** 网页字号（百分比，所有网页通用）。 */
    var textZoom by mutableIntStateOf(100)
        private set

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
        val s = 0.33f
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
    fun backgroundPlayback() {
        if (::app.isInitialized) tabs.lastOrNull { it.audible && it.mediaFromUser }?.let { com.jack.ai.BrowserPlaybackService.start(app, it) }
    }

    /** 电脑版放大多少（百分比，见 BrowserPages.desktopWidth）。 */
    var desktopZoom by mutableIntStateOf(100)
        private set

    private fun desktopWidth() = BrowserPages.desktopWidth(desktopZoom)

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
        private set

    /** 这个网址用不用电脑版。 */
    fun isDesktopAt(url: String): Boolean =
        if (desktopAll) BrowserPages.site(url).isNotEmpty() && !BrowserPages.isDesktop(mobileSites, url) else BrowserPages.isDesktop(desktopSites, url)
    /** 用户设了「总走代理」的网站（自动线路下也不直连，真实 IP 不暴露给它）。 */
    var proxySites by mutableStateOf(setOf<String>())
        private set
    /** 允不允许网页用 WebRTC（视频通话要它，但它能绕过代理查到真实 IP）；默认不许。 */
    var webrtcAllowed by mutableStateOf(false)
        private set
    /** 手机版的浏览器标识（去掉了 App 内嵌标记）；电脑版的由它换算。 */
    private var mobileUa = ""

    /** 下载在后台跑，离开浏览器页也不停。 */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Kavi 最近一次操作网页的时刻；聊天页据此显示小窗。 */
    var agentAt by mutableLongStateOf(0L)
    /** 用户最后一次碰网页的时间（弹窗是不是点出来的，要看它）。 */
    @Volatile var touchedAt = 0L
    /** 网页窗口换了地方（浏览器页 ↔ 聊天小窗）就加一，让原来的地方重新来要。 */
    var hostGen by mutableIntStateOf(0)
    /** Kavi 在等用户亲手做的事（登录、人机验证），回答里的网页画面上会写出来；null = 没在等。 */
    var waitingUser by mutableStateOf<String?>(null)

    /** 对话里正显示着 Kavi 操作网页的实时画面（这时聊天页右上角的小窗就不出来）。 */
    var inlineHosts by mutableIntStateOf(0)
    /** 用户正开着浏览器页。 */
    var userViewing = false

    private val web: WebView? get() = tab.web
    private lateinit var wrapper: MutableContextWrapper
    private lateinit var app: Context

    /** 网页上次是按哪个代理端口设的（0 = 直连）；内核重启换了端口就要重设。 */
    private var appliedPort = -1
    private var appliedRoute = ""
    private var appliedBypass = emptyList<String>()
    private var pageDone: CompletableDeferred<Unit>? get() = tab.pageDone; set(v) { tab.pageDone = v }
    /** 最近给代理口令的时刻：10 秒内要了 30 次以上，说明口令没被接受。 */
    private val authTimes = ArrayDeque<Long>()

    fun agentActive(now: Long = System.currentTimeMillis()) = web != null && now - agentAt < 5 * 60_000

    // ---------- 网页窗口 ----------

    /** 拿到（第一次就建好）网页窗口。只能在主线程调。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context): WebView {
        web?.let { return it }
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
    fun newTab(switch: Boolean = true, incognito: Boolean = false, account: Int = 0): Tab {
        val ok = incognitoSupported()
        val t = Tab(nextTabId++, incognito && ok, if (ok && !incognito) account.coerceIn(0, ACCOUNTS) else 0)
        t.web = buildWeb(t)
        tabs += t
        if (switch) switchTo(tabs.lastIndex)
        return t
    }

    /** 给标签页建网页窗口（新开时，或网页进程崩了重建时）。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb(t: Tab): WebView {
        val profile = if (t.incognito) INCOGNITO else if (t.account > 0) accountProfile(t.account) else null
        // 换资料夹要在网页窗口做任何别的事之前
        return JackWebView(wrapper).also { w -> profile?.let { androidx.webkit.WebViewCompat.setProfile(w, it) } }.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                // 允许网页弹新窗口（「用 Google / 微软 / Apple 登录」这类），开在新标签页里；网页自己乱弹的在 onCreateWindow 里拦掉
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                // 网页要位置时弹窗问（见 askPerm）
                setGeolocationEnabled(true)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                textZoom = this@JackBrowser.textZoom
                loadWithOverviewMode = true
                userAgentString = BrowserPages.cleanUserAgent(userAgentString)
                mobileUa = userAgentString
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA) && mobileMeta == null) {
                mobileMeta = runCatching { androidx.webkit.WebSettingsCompat.getUserAgentMetadata(settings) }.getOrNull()
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkAllowed)
            }
            // 第三方 Cookie 要开：「用 XX 账号登录」的跳转和内嵌登录靠它
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setFindListener { active, count, done -> if (done) { t.findIndex = active; t.findCount = count } }
            // 手机自带的密码管理器（自动填充服务）能给网页里的账号密码框填密码：填的是用户自己的密码管理器，Kavi 不碰
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setOnLongClickListener { v -> longPress(v as WebView) }
            setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchedAt = System.currentTimeMillis(); false }
            webViewClient = Client(t)
            webChromeClient = Chrome(t)
            setDownloadListener { url, userAgent, disposition, mime, length ->
                // 先记下来：用户看着就弹窗问，Kavi 在操作就等它用 download 动作确认
                val p = BrowserDownload.Pending(url, BrowserDownload.nameFor(url, disposition, mime), mime.orEmpty(),
                    length, userAgent.orEmpty(), t.address, byUser = userViewing, tab = t.id)
                pending = p
                // 网页自己生成的文件（blob: / data:）：网址里没有名字，用网页给链接写的 download 名字
                if (!BrowserPages.isWeb(url)) evaluateJavascript("JSON.stringify(window.__jackLastDl || null)") { r ->
                    val o = runCatching { json.parseToJsonElement(unwrap(r)) as? JsonObject }.getOrNull() ?: return@evaluateJavascript
                    val name = (o["name"] as? JsonPrimitive)?.content.orEmpty()
                    if ((o["href"] as? JsonPrimitive)?.content == url.take(200) && name.isNotBlank() && pending == p) {
                        pending = p.copy(name = BrowserDownload.nameFor("", "attachment; filename=\"${name.replace("\"", "")}\"", mime))
                    }
                }
            }
            // 网页生成文件后马上收回（revokeObjectURL）的话就存不下来了：晚两分钟再收；顺便记下链接上写的文件名
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, KEEP_BLOB_JS, setOf("*")) }
                // 切出浏览器时正在放的声音接着放
                runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, BrowserPages.KEEP_PLAYING_JS + BrowserPages.MEDIA_JS, setOf("*")) }
                // 电脑版：网页一开始就当自己在电脑上（只在电脑版标识下起作用）
                t.deskScript = runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, BrowserPages.desktopJs(desktopWidth()), setOf("*")) }.getOrNull()
                // 数着后台请求：动作做完等它们回来再读页面，失败的告诉模型（只在主页面装）
                t.netScript = runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, BrowserNetWatch.JS, setOf("*")) }.getOrNull()
                // 不让网页靠 WebRTC 查到真实 IP（每个嵌入框也装）
                if (rtcBlocked()) t.rtcScript = runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, RTC_JS, setOf("*")) }.getOrNull()
            }
        }
    }

    /**
     * 网页进程没了（崩溃，或手机内存紧被系统收回）：所有标签页共用一个网页进程，每页都会收到。
     * 不接住的话系统会把整个 App 关掉。这一页换个新网页窗口：用户眼前的和 Kavi 正用着的马上重新打开，别的切过去时再开。
     */
    private fun recover(t: Tab, dead: WebView) {
        val url = t.address
        (dead.parent as? ViewGroup)?.removeView(dead)
        BrowserCdp.release(t)
        runCatching { dead.destroy() }
        if (t !in tabs) { t.web = null; return }
        t.netScript = null; t.diagScript = null; t.teachScript = null; t.rtcScript = null; t.deskScript = null
        t.diagOn = false
        t.web = buildWeb(t)
        t.pageDone?.complete(Unit)
        t.loading = 100
        t.media.clear(); t.mediaFrames.clear()
        if (BrowserPages.isWeb(url)) {
            val visible = t === tabs.getOrNull(current) || com.jack.ai.tools.BrowserLock.isAgentTab(t)
            if (visible) { t.web?.let { w -> applyUa(w, url); w.loadUrl(url) } } else t.restore = url
            t.error = "网页崩溃了（多半是手机内存紧），已经重新打开"; t.pageFailed = false
        }
        hostGen++
    }

    // ---------- 网页要用相机、麦克风、位置 ----------

    /**
     * 先看记住的：不允许过就直接拒；都允许过就只向系统要手机权限。没记过的弹窗问用户。
     * Kavi 在操作（用户没看着）、隐身页记住的不算数，也不记。
     */
    private fun askPerm(t: Tab, origin: String, kinds: Set<String>, done: (Boolean) -> Unit) {
        val site = BrowserPages.site(origin)
        val system = systemPerms
        if (!userViewing || system == null || site.isEmpty() || permAsk != null) { done(false); return }
        val keys = kinds.map { BrowserPages.permKey(site, it) }
        if (!t.incognito && keys.any { it in permNo }) { done(false); return }
        if (!t.incognito && keys.all { it in permYes }) { system(kinds, done); return }
        permAsk = PermAsk(site, kinds, t.incognito) { ok, remember ->
            permAsk = null
            if (remember && !t.incognito) {
                if (ok) { permYes = permYes + keys; permNo = permNo - keys.toSet() } else { permNo = permNo + keys; permYes = permYes - keys.toSet() }
                prefs().edit().putStringSet("permyes", permYes).putStringSet("permno", permNo).apply()
            }
            if (ok) system(kinds, done) else done(false)
        }
    }

    /** 当前网站记住了哪些权限（给菜单「重置网站权限」用）。 */
    fun permsHere(): Boolean = BrowserPages.site(address).let { s -> s.isNotEmpty() && (permYes + permNo).any { it.startsWith("$s|") } }

    fun resetPermsHere() {
        val s = BrowserPages.site(address).ifEmpty { return }
        permYes = permYes.filterNot { it.startsWith("$s|") }.toSet(); permNo = permNo.filterNot { it.startsWith("$s|") }.toSet()
        prefs().edit().putStringSet("permyes", permYes).putStringSet("permno", permNo).apply()
    }

    // ---------- 关掉 App 再打开时恢复标签页 ----------

    private val savedTabs = BrowserPages.SavedTabs.serializer()

    /** 记下现在开着的标签页（隐身的不记），离开浏览器页时调。 */
    fun saveTabs() {
        if (!::app.isInitialized || tabs.isEmpty()) return
        val s = BrowserPages.tabsToSave(tabs.map { BrowserPages.SavedTab(it.restore ?: it.address, it.title, it.account) to (it.incognito || com.jack.ai.tools.BrowserLock.isAgentTab(it)) }, current, MAX_TABS)
        prefs().edit().putString("tabs", listJson.encodeToString(savedTabs, s)).apply()
    }

    /**
     * App 重开后第一次进浏览器：把上次的标签页建回来（只建窗口，切过去时才加载，见 [Tab.restore]）。
     * 已经有标签页（比如 Kavi 先用过浏览器）就不动。返回有没有恢复。
     */
    fun restoreTabs(ctx: Context): Boolean {
        if (tabs.isNotEmpty()) return false
        webView(ctx)
        val s = prefs().getString("tabs", null)?.let { runCatching { listJson.decodeFromString(savedTabs, it) }.getOrNull() }
        if (s == null || s.tabs.isEmpty()) return false
        s.tabs.take(MAX_TABS).forEach { st ->
            newTab(switch = false, account = st.account).apply { address = st.url; title = st.title; restore = st.url }
        }
        // 第一个空白页是 webView() 建的，不要了
        closeTab(0)
        switchTo(s.current.coerceIn(0, tabs.lastIndex))
        return true
    }

    // ---------- 地址栏联想 ----------

    /** 长的浏览记录先读进来，打字联想时用。 */
    fun warmHistory() {
        if (!::app.isInitialized || historyList != null) return
        scope.launch(historyIo) { if (historyList == null) historyList = com.jack.ai.data.Store(app).loadHistory() }
    }

    /** 历史记录页要的完整浏览记录。 */
    suspend fun historyEntries(): List<com.jack.ai.data.HistoryEntry> = withContext(historyIo) {
        historyList ?: com.jack.ai.data.Store(app).loadHistory().also { historyList = it }
    }

    /** 删掉一条浏览记录（起始页的最近访问里也去掉）。 */
    fun deleteHistory(url: String) {
        visits = visits.filterNot { it.url == url }; saveLists()
        scope.launch(historyIo) {
            val store = com.jack.ai.data.Store(app)
            historyList = com.jack.ai.data.BrowserHistory.remove(historyList ?: store.loadHistory(), url)
            historySave?.cancel()
            store.saveHistory(historyList.orEmpty())
        }
    }

    /** 清空浏览记录和最近访问（登录状态、缓存不动）。 */
    fun clearHistory() {
        visits = emptyList(); saveLists()
        historySave?.cancel()
        scope.launch(historyIo) { historyList = emptyList(); com.jack.ai.data.Store(app).saveHistory(emptyList()) }
    }

    /** 地址栏输入时的联想：书签、最近访问、浏览记录里对得上的。 */
    fun suggest(query: String): List<BrowserPages.Visit> =
        BrowserPages.suggest(query, marks, visits + historyList.orEmpty().map { BrowserPages.Visit(it.url, it.title, it.at) })

    /** 最多开几个标签页（每个都是一个网页窗口，占内存）。 */
    const val MAX_TABS = 10

    /** 切到第 i 个标签页：网页窗口换了，让浏览器页重新来要。 */
    fun switchTo(i: Int) {
        if (i !in tabs.indices) return
        // Kavi 在自己的后台标签页里切标签：只换它钉住的那页，不动用户眼前的
        pinLocal.get()?.let { it.t = tabs[i]; return }
        if (i != current) snap()
        barsHidden = false
        current = i
        hostGen++
    }

    /** 关掉一个标签页（最后一个不关，只回起始页）。 */
    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        if (tabs.size == 1) { home(); return }
        val t = tabs.removeAt(i)
        (t.web?.parent as? ViewGroup)?.removeView(t.web)
        BrowserCdp.release(t)
        t.web?.destroy()
        if (t.incognito && tabs.none { it.incognito }) runCatching { clearIncognito() }
        val stay = if (i < current) current - 1 else current.coerceAtMost(tabs.lastIndex)
        // 在 Kavi 的后台操作里关的：用户眼前那页只挪位置，不跟着切
        if (pinLocal.get() != null) { current = stay; hostGen++ } else switchTo(stay)
        // 关的是哪个 Kavi 正用着的后台标签页：给它换一页（弹出的登录窗口关了就回到打开它的那页）
        com.jack.ai.tools.BrowserLock.tabClosed(t) { tabs.firstOrNull { it.id == t.openedBy } ?: newTab(switch = false) }
    }

    /** Kavi 在后台标签页里要用户接手（登录、验证）：把用户眼前的换成它那页，打开浏览器就能看到。 */
    fun showToUser() {
        val p = pinLocal.get() ?: return
        val i = tabs.indexOf(p.t).takeIf { it >= 0 } ?: return
        if (i != current) { snap(); current = i; barsHidden = false; hostGen++ }
    }

    private fun prefs() = app.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val listJson = Json { ignoreUnknownKeys = true }
    private val laterList = kotlinx.serialization.builtins.ListSerializer(BrowserPages.Later.serializer())
    private fun laterFile(url: String): java.io.File {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(app.filesDir, "readlater/$hash.md")
    }
    private fun saveLater() { prefs().edit().putString("readlater", listJson.encodeToString(laterList, later)).apply() }
    fun removeLater(url: String) {
        later = BrowserPages.removeLater(later, url); saveLater()
        scope.launch(Dispatchers.IO) { laterFile(url).delete() }
    }
    fun markLaterRead(url: String) { later = BrowserPages.readLater(later, url); saveLater() }
    fun addLater(url: String, name: String = "") {
        if (tab.incognito || !userViewing) return
        val before = later
        later = BrowserPages.addLater(later, url, name, System.currentTimeMillis()); saveLater()
        val kept = later.map { it.url }.toSet()
        scope.launch(Dispatchers.IO) { before.filterNot { it.url in kept }.forEach { laterFile(it.url).delete() } }
        val t = tabs.firstOrNull { !it.incognito && it.address == url } ?: return
        scope.launch(pin(t)) {
            val body = readable(app)?.second ?: return@launch
            withContext(Dispatchers.IO) {
                if (later.any { it.url == url }) laterFile(url).apply { parentFile?.mkdirs(); writeText(body) }
            }
        }
    }
    suspend fun offlineLater(url: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val entry = later.find { it.url == url } ?: return@withContext null
        runCatching { entry.title to laterFile(url).readText() }.getOrNull()
    }

    private val visitList = kotlinx.serialization.builtins.ListSerializer(BrowserPages.Visit.serializer())

    private fun loadLists() {
        fun read(k: String) = prefs().getString(k, null)?.let { runCatching { listJson.decodeFromString(visitList, it) }.getOrNull() }.orEmpty()
        visits = read("visits"); marks = read("marks")
        later = prefs().getString("readlater", null)?.let { runCatching { listJson.decodeFromString(laterList, it) }.getOrNull() }.orEmpty()
        searchEngine = prefs().getString("searchEngine", BrowserNet.AUTO) ?: BrowserNet.AUTO
        desktopSites = prefs().getStringSet("desktop", emptySet()).orEmpty().toSet()
        desktopAll = prefs().getBoolean("desktopall", true)
        desktopZoom = prefs().getInt("deskzoom", 100)
        textZoom = prefs().getInt("textzoom", 100)
        mobileSites = prefs().getStringSet("mobile", emptySet()).orEmpty().toSet()
        proxySites = prefs().getStringSet("proxysites", emptySet()).orEmpty().toSet()
        webrtcAllowed = prefs().getBoolean("webrtc", false)
        adBlock = prefs().getBoolean("adblock", true); adBlockNow = adBlock
        adAllow = prefs().getStringSet("adallow", emptySet()).orEmpty().toSet(); adAllowNow = adAllow
        permYes = prefs().getStringSet("permyes", emptySet()).orEmpty().toSet()
        permNo = prefs().getStringSet("permno", emptySet()).orEmpty().toSet()
        blocks = prefs().getString("blocks", null)?.let { runCatching { listJson.decodeFromString(blockMap, it) }.getOrNull() }.orEmpty()
    }

    private fun uaFor(url: String) = if (isDesktopAt(url)) BrowserPages.desktopUserAgent(mobileUa) else mobileUa

    /** 换网页前按网站选手机版还是电脑版的标识。返回有没有换。 */
    private fun applyUa(w: WebView, url: String): Boolean {
        val ua = uaFor(url)
        if (mobileUa.isEmpty() || w.settings.userAgentString == ua) return false
        w.settings.userAgentString = ua
        // 新式设备标识（请求里的 Sec-CH-UA-*、网页里的 userAgentData）跟着换，不然大网站照样认出是手机
        val meta = if (ua == mobileUa) mobileMeta else mobileMeta?.let { desktopMeta(it) }
        if (meta != null) runCatching { androidx.webkit.WebSettingsCompat.setUserAgentMetadata(w.settings, meta) }
        return true
    }

    /** 手机版原本的新式设备标识（第一个网页窗口建好时记下）；网页组件不支持改它时是 null。 */
    private var mobileMeta: androidx.webkit.UserAgentMetadata? = null

    /** 电脑版的新式设备标识：Windows 上的 Chrome，不是手机；版本号照手机上的。 */
    private fun desktopMeta(m: androidx.webkit.UserAgentMetadata): androidx.webkit.UserAgentMetadata {
        val brands = m.brandVersionList.map { b ->
            if (b.brand.contains("WebView", true)) androidx.webkit.UserAgentMetadata.BrandVersion.Builder(b).setBrand("Google Chrome").build() else b
        }
        return androidx.webkit.UserAgentMetadata.Builder(m).setBrandVersionList(brands).setPlatform("Windows").setPlatformVersion("10.0.0")
            .setArchitecture("x86").setBitness(64).setModel("").setMobile(false).setWow64(false).build()
    }

    /** 当前网站切换电脑版 / 手机版（按网站记住），然后重新打开。默认全用电脑版时，记的是哪些网站例外用手机版。 */
    fun toggleDesktop() {
        val w = web ?: return
        val site = BrowserPages.site(address).ifEmpty { return }
        fun flip(set: Set<String>) = if (BrowserPages.isDesktop(set, address)) set.filterNot { site == it || site.endsWith(".$it") }.toSet() else set + site
        if (desktopAll) { mobileSites = flip(mobileSites); prefs().edit().putStringSet("mobile", mobileSites).apply() }
        else { desktopSites = flip(desktopSites); prefs().edit().putStringSet("desktop", desktopSites).apply() }
        applyUa(w, address)
        clearError()
        w.loadUrl(address)
    }

    /** 所有网站都用电脑版：开关，然后按新设置重新打开当前网页。 */
    fun toggleDesktopAll() {
        desktopAll = !desktopAll
        prefs().edit().putBoolean("desktopall", desktopAll).apply()
        val w = web ?: return
        if (address.isNotEmpty() && applyUa(w, address)) { clearError(); w.loadUrl(address) }
    }

    /** 电脑版：自适应的网站会按手机宽度排，把可视宽度改成电脑的，再缩到屏幕上。 */
    private fun widenIfDesktop(w: WebView, url: String) {
        if (!isDesktopAt(url)) return
        w.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';" +
                "(document.head||document.documentElement).appendChild(m);}m.content='width=${desktopWidth()}';})();", null,
        )
    }

    /** 「问 Kavi」：当前网页的标题、网址和正文（最多 6 万字）。 */
    suspend fun pageText(ctx: Context): String {
        // 先取正文（去掉导航、侧栏、评论），取不出多少再退回整页文字
        val body = readable(ctx)?.second?.takeIf { it.length >= 200 }?.take(60_000)
            ?: unwrap(eval(ctx, "(document.body ? document.body.innerText : '').slice(0, 60000)")).trim()
        val subs = if (isVideoPage(address)) subtitles(ctx).first?.let { "\n\n【视频字幕】\n" + it.take(60_000) }.orEmpty() else ""
        return "网页：$title\n链接：$address\n\n" + body + subs
    }

    /** 阅读模式：(标题, 正文 Markdown)；读不出返回 null。 */
    suspend fun readable(ctx: Context): Pair<String, String>? {
        val o = runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.readable(ctx)))).jsonObject }.getOrNull() ?: return null
        val md = (o["md"] as? JsonPrimitive)?.content.orEmpty().trim()
        return if (md.isEmpty()) null else (o["title"] as? JsonPrimitive)?.content.orEmpty() to md
    }

    /** YouTube、B 站的视频页（能取字幕的）。 */
    fun isVideoPage(url: String): Boolean {
        val site = BrowserPages.site(url)
        return (site.endsWith("youtube.com") && ("watch" in url || "/shorts/" in url)) || site == "youtu.be" ||
            (site.endsWith("bilibili.com") && "/video/" in url)
    }

    /** 视频字幕（YouTube、B 站、网页里带字幕轨的视频），最多等 20 秒。返回 (字幕, 哪来的) 或 (null, 为什么没有)。 */
    suspend fun subtitles(ctx: Context): Pair<String?, String> {
        agentAt = System.currentTimeMillis()
        eval(ctx, BrowserScripts.subtitles(ctx))
        repeat(40) {
            delay(500)
            val o = runCatching { json.parseToJsonElement(unwrap(eval(ctx, "JSON.stringify(window.__jackSubs || null)"))) as? JsonObject }.getOrNull()
            if (o != null) {
                fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                return if (s("ok") == "true" && s("text").isNotBlank()) s("text") to "${s("from")}字幕（${s("lang")}）" else null to s("why").ifBlank { "没取到字幕" }
            }
        }
        return null to "等了 20 秒没取到字幕"
    }

    /** 长按图片「识别二维码」：带着网页的登录状态把图片取下来再读。读不出返回 null。 */
    suspend fun scanQr(url: String): String? {
        val bytes = if (url.startsWith("data:image", true)) {
            runCatching { Base64.decode(url.substringAfter(","), Base64.DEFAULT) }.getOrNull() ?: return null
        } else {
            val cookie = withContext(Dispatchers.Main) { cookiesOf(tab).getCookie(url) }
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

    // ---- 长的浏览记录 ----
    private val historyIo = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var historyList: List<com.jack.ai.data.HistoryEntry>? = null
    private var historySave: kotlinx.coroutines.Job? = null

    private fun recordHistory(url: String, title: String) {
        val now = System.currentTimeMillis()
        scope.launch(historyIo) {
            val store = com.jack.ai.data.Store(app)
            val list = historyList ?: store.loadHistory()
            historyList = com.jack.ai.data.BrowserHistory.record(list, url, title, now)
            // 连着跳好几页只写一次
            historySave?.cancel()
            historySave = scope.launch(historyIo) { delay(2_000); historyList?.let { store.saveHistory(it) } }
        }
    }

    // ---- 屏蔽网页上的某一块 ----
    private val blockMap = kotlinx.serialization.builtins.MapSerializer(String.serializer(), kotlinx.serialization.builtins.ListSerializer(String.serializer()))
    /** 网站 → 要藏起来的选择器。 */
    var blocks by mutableStateOf(mapOf<String, List<String>>())
    /** 正在选要屏蔽的那块；picked 是点中的。 */
    var picking by mutableStateOf(false)
    var picked by mutableStateOf<BrowserBlocks.Picked?>(null)

    private fun applyBlocks(view: WebView, site: String) {
        val sels = BrowserBlocks.forSite(blocks, site)
        if (sels.isNotEmpty() || blocks.isNotEmpty()) view.evaluateJavascript(BrowserBlocks.injectJs(sels), null)
    }

    /** 当前网站屏蔽了哪些。 */
    fun blocksHere(): List<String> = blocks[BrowserPages.site(address)].orEmpty()

    fun startPick() {
        val w = web ?: return
        picked = null
        picking = true
        w.evaluateJavascript(BrowserBlocks.PICK_JS, null)
    }

    /** 选中的范围往外扩一层（点到的是里面的小字、图标时）。 */
    fun pickWider() { web?.evaluateJavascript("window.__jackPickUp&&window.__jackPickUp()", null) }

    fun stopPick() {
        picking = false
        picked = null
        web?.evaluateJavascript("window.__jackPickStop&&window.__jackPickStop()", null)
    }

    /** 把选中的那块存成这个网站的屏蔽规则，马上藏起来。 */
    fun confirmPick() {
        val p = picked ?: return
        val site = BrowserPages.site(address)
        stopPick()
        if (site.isEmpty()) return
        blocks = BrowserBlocks.add(blocks, site, p.selector)
        saveBlocks()
        web?.let { applyBlocks(it, site) }
        notice = "已屏蔽" + (if (p.count > 1) "（这一页有 ${p.count} 处一样的，都藏了）" else "") + "。在菜单 → 屏蔽网页上的东西 里能恢复。"
    }

    fun unblock(selector: String) {
        val site = BrowserPages.site(address)
        blocks = BrowserBlocks.remove(blocks, site, selector)
        saveBlocks()
        web?.let { applyBlocks(it, site) }
    }

    private fun saveBlocks() { prefs().edit().putString("blocks", listJson.encodeToString(blockMap, blocks)).apply() }

    // ---- 演示教学 ----
    /** 正在记用户的操作；teachSteps 是记下的步骤。 */
    var teaching by mutableStateOf(false)
    var teachSteps by mutableStateOf(listOf<String>())

    private fun installTeach(t: Tab) {
        val w = t.web ?: return
        if (t.teachScript == null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            t.teachScript = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, BrowserTeach.RECORD_JS, setOf("*"))
        }
        // 已经打开的这一页也装上
        w.evaluateJavascript(BrowserTeach.RECORD_JS, null)
    }

    fun startTeach() {
        teaching = true
        teachSteps = if (BrowserPages.isWeb(address)) listOf("打开 $address") else emptyList()
        installTeach(tab)
    }

    /** 停止记录，返回记下的步骤。 */
    fun stopTeach(): List<String> {
        teaching = false
        tabs.forEach { t -> t.teachScript?.remove(); t.teachScript = null }
        // 页面里的监听刷新后才没（只往 console 打，不碍事）
        return teachSteps.also { teachSteps = emptyList() }
    }

    // ---- 存进资料库 ----
    /** 把当前网页的正文存进资料库（Kavi 以后能搜到）。返回给用户看的话。 */
    suspend fun saveToLibrary(ctx: Context): String {
        val (t, md) = readable(ctx) ?: (title to unwrap(eval(ctx, "(document.body ? document.body.innerText : '').slice(0, 200000)")).trim())
        if (md.length < 50) return "这一页读不到多少文字，没存。"
        val name = t.ifBlank { title }.ifBlank { BrowserPages.host(address) }.replace(Regex("""[\\/:*?"<>|]"""), "_").take(60)
        val body = "# $name\n\n原网页：$address\n存于：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date()) + "\n\n" + md
        withContext(Dispatchers.IO) {
            val store = com.jack.ai.data.Store(app)
            val doc = com.jack.ai.data.LibraryDoc(name = "$name.md", chars = body.length)
            store.saveLibraryText(doc.id, body)
            store.saveLibrary(store.loadLibrary() + doc)
        }
        return "已存进资料库：「$name」。以后问 Kavi 相关的事，它能搜到这篇。"
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
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        tabs.forEach { t -> t.web?.let { androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(it.settings, allowed) } }
    }

    /** 回到起始页（书签、最近访问）。能后退回刚才的网页。 */
    fun home() {
        val w = web ?: return
        clearError()
        tab.restore = null
        w.loadUrl("about:blank")
        address = ""; title = ""
    }

    /** 用户或 Kavi 开始一次新的跳转：上一页的出错提示不再适用。 */
    fun clearError() { error = null; pageFailed = false }

    /** 长按链接或图片：记下来让浏览器页弹菜单；长按文字照旧选字。Kavi 在操作时不弹。 */
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

    /**
     * 浏览器页回到前台：网页窗口在它不在前台时被聊天里的小窗拿走了（浏览器页是 singleTask，切走不销毁），
     * 它的框里就空了、只剩灰底。让所有放网页的地方重新来要，前台这个会拿回来。
     */
    fun reclaim(ctx: Context) {
        val w = web ?: return
        if (w.parent == null || wrapper.baseContext !== ctx) hostGen++
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
                is BrowserNet.Plan.Proxy -> apply(plan.port, plan.bypass)
            }
        }
    }

    private suspend fun apply(port: Int, bypass: List<String>): String? {
        if (port == appliedPort && route == appliedRoute && bypass == appliedBypass) return null
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
        appliedPort = port; appliedRoute = route; appliedBypass = bypass
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
    suspend fun load(ctx: Context, url: String, timeoutMs: Long = 25_000): String? {
        // 并行抓取时在指定的标签页里开，平时在当前页
        val t = withContext(Dispatchers.Main) { pinned() ?: webView(ctx).let { tab } }
        // 恢复的标签页等真开始加载了才算恢复完：半路被打断（页面关掉、代理没起来）下次切过来还会再开，不会一直空白
        withContext(Dispatchers.Main) { t.clearError() }
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.OPEN -> {}
            BrowserNet.Kind.EXTERNAL -> return "这是打开别的 App 的链接，浏览器里打不开：$url"
            BrowserNet.Kind.BLOCKED -> return "不能打开这个地址（本机、局域网和文件地址都不开）：$url"
        }
        prepare(ctx)?.let { reason -> withContext(Dispatchers.Main) { t.error = reason; t.pageFailed = true }; return reason }
        val done = withContext(Dispatchers.Main) {
            CompletableDeferred<Unit>().also { t.pageDone = it; t.restore = null; t.web!!.let { w -> applyUa(w, url); w.loadUrl(url) } }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        return withContext(Dispatchers.Main) { t.error }
    }

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

    private class Client(val t: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (BrowserNet.classify(url)) {
                BrowserNet.Kind.OPEN -> {
                    if (!request.isForMainFrame) false
                    // 网页没经过用户点击就自己跳到广告网站：不去
                    else if (!request.hasGesture() && BrowserAds.isAd(request.url.host.orEmpty()) && blocksAds(t.site)) { t.diag.add("没让网页自己跳到广告网站 $url"); true }
                    else {
                        t.clearError()
                        // 从手机版网站跳到电脑版网站（或反过来）：换好标识重新打开这个网址
                        if (applyUa(view, url)) { view.loadUrl(url); true } else false
                    }
                }
                BrowserNet.Kind.EXTERNAL -> { t.diag.add("网页要打开别的 App：${url.take(120)}"); if (userViewing && request.hasGesture()) external = url; true }
                BrowserNet.Kind.BLOCKED -> { t.diag.add("拦下：本机或局域网地址 $url"); if (request.isForMainFrame) t.error = "已拦下：网页想打开本机、局域网或文件地址（$url）"; true }
            }
        }

        // 网页里的图片、脚本、请求也不许碰本机和局域网
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host ?: return null
            if (request.url.scheme in setOf("http", "https", "ws", "wss") && BrowserNet.isLocal(host)) {
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
            if (e.url == view.url || e.url == t.address) { t.error = "这个网站的安全证书有问题，已停止打开（${e.url}）"; t.pageFailed = true }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
            t.diag.add((if (request.isForMainFrame) "网页没打开" else "加载失败") + "（${e.errorCode} ${e.description}）${request.url.toString().take(160)}")
            if (request.isForMainFrame) { t.error = describe(e.errorCode, e.description?.toString().orEmpty()); t.pageFailed = true }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: WebResourceResponse) {
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
            if (t === tab) barsHidden = false
            t.diag.add("开始打开 ${url.take(160)}")
            if (t.pageDone?.isCompleted != false) t.pageDone = CompletableDeferred()
            t.translated = false
            t.media.clear()
            t.mediaFrames.clear()
            t.mediaFromUser = false
            t.address = url
            t.site = BrowserPages.site(url)
            t.blockedNow.set(0); t.blocked = 0
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            // 不支持开头注入的旧网页组件：页面露出来时补上防泄漏（晚了一点，聊胜于无）
            if (rtcBlocked() && t.rtcScript == null) view.evaluateJavascript(RTC_JS, null)
            // 不支持开头注入的旧网页组件：页面露出来时补装钩子
            if (t.diagOn && t.diagScript == null) view.evaluateJavascript(BrowserDiag.HOOK_JS, null)
            if (t.netScript == null) view.evaluateJavascript(BrowserNetWatch.JS, null)
            view.evaluateJavascript(BrowserPages.KEEP_PLAYING_JS + BrowserPages.MEDIA_JS, null)
            widenIfDesktop(view, url)
            if (blocksAds(t.site)) view.evaluateJavascript(BrowserAds.INJECT_JS, null)
            applyBlocks(view, t.site)
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(BrowserPages.KEEP_PLAYING_JS + BrowserPages.MEDIA_JS, null)
            widenIfDesktop(view, url)
            if (blocksAds(t.site)) view.evaluateJavascript(BrowserAds.INJECT_JS, null)
            t.pageDone?.complete(Unit)
            t.diag.add("加载完 ${url.take(160)}")
            applyBlocks(view, t.site)
            // 选要屏蔽的东西时网页跳了：新页面上接着能选
            if (picking && t === tab) { picked = null; view.evaluateJavascript(BrowserBlocks.PICK_JS, null) }
            if (teaching && t === tab && BrowserPages.isWeb(url)) teachSteps = BrowserTeach.append(teachSteps, "→ 到了「${view.title.orEmpty().take(40)}」$url")
            if (url != "about:blank") t.address = url
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
            if (url != t.address) BrowserCdp.forget(t)
            if (url != "about:blank") t.address = url
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }
    }

    private class Chrome(val t: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { t.loading = newProgress }
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
        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            t.title = newTitle.orEmpty()
            val url = view.url.orEmpty()
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
    @Volatile private var lastDialog: String? = null

    // ---------- 下载 ----------

    private fun progressText(name: String, got: Long, total: Long) =
        "正在下载「$name」：" + if (total > 0) "${got * 100 / total}%（${BrowserDownload.sizeText(got)} / ${BrowserDownload.sizeText(total)}）"
        else BrowserDownload.sizeText(got)

    /** 在网页里装好：晚两分钟再收回网页生成的文件；记下链接上写的下载文件名（blob: 网址里没有名字）。 */
    private const val KEEP_BLOB_JS = """(function(){if(window.__jackKeep)return;window.__jackKeep=1;
try{var rv=URL.revokeObjectURL;URL.revokeObjectURL=function(u){setTimeout(function(){try{rv.call(URL,u);}catch(e){}},120000);};}catch(e){}
function note(a){try{var h=a&&a.href;if(h&&(h.indexOf('blob:')===0||h.indexOf('data:')===0))window.__jackLastDl={href:h.slice(0,200),name:a.getAttribute('download')||''};}catch(e){}}
try{var ck=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){note(this);return ck.apply(this,arguments);};
var de=EventTarget.prototype.dispatchEvent;EventTarget.prototype.dispatchEvent=function(e){if(this instanceof HTMLAnchorElement&&e&&e.type==='click')note(this);return de.apply(this,arguments);};}catch(e){}
document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[href]');if(a)note(a);},true);})();"""

    /** 网页生成的文件最多读多大（要整个经过网页的内存，太大会卡死网页）。 */
    const val BLOB_LIMIT = 200L * 1024 * 1024

    /** 浏览器的线路设置（没打开过浏览器也能读）。 */
    fun routeFor(ctx: Context): String = if (::app.isInitialized) route
        else ctx.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("route", BrowserNet.AUTO) ?: BrowserNet.AUTO

    /** 下载时报的浏览器标识（和网页里用的一样，网站才认）。 */
    fun userAgent(): String = mobileUa.ifBlank { "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36" }

    /** 某个网址在这个标签页里的 Cookie（登录过的网站下载要带上）。 */
    suspend fun cookieFor(url: String, t: Tab? = null): String? = withContext(Dispatchers.Main) {
        runCatching { (t?.let { cookiesOf(it) } ?: CookieManager.getInstance()).getCookie(url) }.getOrNull()
    }

    private fun tabById(id: Int): Tab? = tabs.firstOrNull { it.id == id }

    /** 在某个标签页里跑脚本（不一定是当前这个）。 */
    private suspend fun evalOn(t: Tab, js: String): String = withContext(Dispatchers.Main) {
        val w = t.web ?: throw java.io.IOException("那个标签页已经关了")
        ensureLaidOut(w)
        suspendCancellableCoroutine { cont -> w.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r ?: "null") } }
    }

    /**
     * 把网页自己生成的文件（blob:）从网页里读出来：(类型, 内容)。要在生成它的那个标签页里读。
     * 一段段搬（每次 1 MB 的 base64），太大的不读。
     */
    suspend fun blobBytes(p: BrowserDownload.Pending, progress: (Long, Long) -> Unit = { _, _ -> }): Pair<String, ByteArray> {
        val t = tabById(p.tab) ?: tab
        val js = "(function(u,l){window.__jackBlob={state:'loading'};fetch(u).then(function(r){return r.blob();}).then(function(b){" +
            "if(b.size>l){window.__jackBlob={state:'big',size:b.size};return;}var f=new FileReader();" +
            "f.onload=function(){var s=String(f.result);window.__jackBlob={state:'ok',mime:b.type,size:b.size,data:s.slice(s.indexOf(',')+1)};};" +
            "f.onerror=function(){window.__jackBlob={state:'error',why:String(f.error)};};f.readAsDataURL(b);})" +
            ".catch(function(e){window.__jackBlob={state:'error',why:String(e)};});return 1;})(${JsonPrimitive(p.url)},$BLOB_LIMIT)"
        evalOn(t, js)
        var info: JsonObject? = null
        for (i in 0 until 240) {
            delay(250)
            info = runCatching { json.parseToJsonElement(unwrap(evalOn(t, "JSON.stringify(window.__jackBlob?{state:__jackBlob.state,mime:__jackBlob.mime,size:__jackBlob.size,len:(__jackBlob.data||'').length,why:__jackBlob.why}:null)"))) as? JsonObject }.getOrNull()
            if (info != null && (info["state"] as? JsonPrimitive)?.content != "loading") break
        }
        fun s(k: String) = (info?.get(k) as? JsonPrimitive)?.content.orEmpty()
        when (s("state")) {
            "ok" -> {}
            "big" -> throw BrowserDownload.DownloadException("网页生成的文件有 ${BrowserDownload.sizeText(s("size").toLongOrNull() ?: 0)}，超过 ${BrowserDownload.sizeText(BLOB_LIMIT)}，存不下来")
            "loading" -> throw BrowserDownload.DownloadException("等了一分钟，网页还没把文件交出来")
            else -> throw BrowserDownload.DownloadException("网页生成的文件读不出来（" + s("why").ifBlank { "可能已经被网页收回了，再点一次下载试试" } + "）")
        }
        val len = s("len").toIntOrNull() ?: 0
        val size = s("size").toLongOrNull() ?: 0
        val out = ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
        val chunk = 1024 * 1024
        try {
            var i = 0
            while (i < len) {
                val part = unwrap(evalOn(t, "window.__jackBlob.data.substr($i,$chunk)"))
                out.write(Base64.decode(part, Base64.DEFAULT))
                i += chunk
                progress(out.size().toLong(), size)
            }
        } finally {
            runCatching { evalOn(t, "window.__jackBlob=null;0") }
        }
        return s("mime") to out.toByteArray()
    }

    /**
     * 存一个网页要下载的文件到 target（对话文件夹或手机「下载」）：普通网址带 Cookie 下（能断点续传），
     * 网页生成的（blob:）从网页里读，data: 直接解。progress 报进度文字。
     */
    internal suspend fun storeDirect(p: BrowserDownload.Pending, target: (String, String) -> Downloads.Target, progress: (String) -> Unit = {}, control: BrowserDownload.Control? = null, privateDownload: Boolean = false, requestCookie: String? = null, requestRoute: String = route, onBytes: (Long, Long) -> Unit = { _, _ -> }): Downloads.Saved {
        var finish: (() -> Downloads.Saved)? = null
        fun sink(name: String, mime: String) = target(name, mime).also { finish = it.finish }.sink
        if (p.url.startsWith("blob:", true)) {
            val (mime0, bytes) = blobBytes(p) { got, total -> onBytes(got, total); progress(progressText(p.name, got, total)) }
            val mime = mime0.ifBlank { p.mime }.ifBlank { "application/octet-stream" }
            val name = p.name.ifBlank { "下载" }.let { n -> if ('.' !in n) BrowserDownload.extFor(mime)?.let { "$n.$it" } ?: n else n }
            withContext(Dispatchers.IO) {
                val s = sink(name, mime)
                try { s.open(false).use { it.write(bytes) } } catch (e: Exception) { s.discard(); throw e }
            }
        } else {
            withContext(Dispatchers.IO) {
                BrowserDownload.download(p, requestCookie, requestRoute, BrowserDownload.HARD_LIMIT, ::sink,
                    progress = { got, total -> onBytes(got, total); progress(progressText(p.name, got, total)) }, control = control)
            }
        }
        return withContext(Dispatchers.IO) { finish!!().also { s -> if (::app.isInitialized && !privateDownload) Downloads.log(app, s, p.url) } }
    }

    /**
     * 用户点了下载：存到手机的「下载/Kavi」文件夹（Android 10 以上，文件管理器里能找到）；
     * 更老的系统存到 App 自己的下载目录。在后台下，进度和结果显示在浏览器页。
     */
    fun saveToPhone(ctx: Context, p: BrowserDownload.Pending) {
        val context = ctx.applicationContext
        pending = null
        com.jack.ai.data.BrowserDownloads.enqueue(context, p, { name, mime -> Downloads.toPhone(context, name, mime) })
    }

    /** Kavi 的确认流程照旧，确认后也进入同一个队列。 */
    suspend fun store(p: BrowserDownload.Pending, target: (String, String) -> Downloads.Target, progress: (String) -> Unit = {}): Downloads.Saved =
        withContext(Dispatchers.Main) { com.jack.ai.data.BrowserDownloads.enqueue(app, p, target, progress) }.result.await()

    suspend fun saveTo(ctx: Context, dir: java.io.File?, p: BrowserDownload.Pending, progress: (String) -> Unit = {}): Downloads.Saved {
        val context = ctx.applicationContext
        if (pending == p) pending = null
        return store(p, { name, mime -> if (dir != null) Downloads.toDir(context, dir, name, mime) else Downloads.toPhone(context, name, mime) }, progress)
    }

    /** 当前页面上能批量下的东西：images 图片、files 附件链接（PDF、文档、压缩包……）、all 都要。返回 (共几个, 前 max 个的 (网址, 名字))。 */
    suspend fun collectDownloads(ctx: Context, kind: String, filter: String, max: Int): Pair<Int, List<Pair<String, String>>> {
        agentAt = System.currentTimeMillis()
        val raw = unwrap(eval(ctx, BrowserScripts.downloads(ctx, kind, filter, max)))
        val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return 0 to emptyList()
        val items = (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val url = (x["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            url to (x["name"] as? JsonPrimitive)?.content.orEmpty()
        }
        return ((o["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: items.size) to items
    }

    /** 当前页面上的视频、音频：网页里的播放器（地址、时长、大小、在不在放）、B 站的播放信息，加上拦截到的媒体请求。 */
    data class MediaPage(val title: String, val url: String, val elements: List<JsonObject>, val playinfo: String?, val hits: List<MediaGrab.Hit>, val tab: Tab)

    suspend fun mediaPage(ctx: Context): MediaPage {
        agentAt = System.currentTimeMillis()
        val t = pinned() ?: tab
        val o = runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.media(ctx)))).jsonObject }.getOrNull()
        val els = (o?.get("media") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val pi = (o?.get("playinfo") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        return MediaPage((o?.get("title") as? JsonPrimitive)?.content ?: title, address, els, pi, t.media.toList(), t)
    }

    // ---------- Kavi 操作网页 ----------

    private val json = Json { ignoreUnknownKeys = true }

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

    /** 没放进任何界面时，按手机屏幕大小排版，网页才有正常的宽高。 */
    private fun ensureLaidOut(w: WebView) {
        if (w.parent != null && w.width > 0) return
        val dm = app.resources.displayMetrics
        w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private suspend fun eval(ctx: Context, js: String): String = withContext(Dispatchers.Main) {
        val w = pinned()?.web ?: webView(ctx)
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
        val page = Page(o.s("url"), o.s("title"), items, o.f("above").toInt(), o.f("below").toInt(), o.f("scroll").toInt(),
            o.f("height").toInt(), o.f("vh").toInt(), o.s("text"), o.f("vx"), o.f("vy"), o.f("vw"),
            withContext(Dispatchers.Main) { error }, dialog, pendingHere(), o.f("frames").toInt())
        val t = pinned() ?: tab
        // 后台、没有 CDP 或页面已经跳转时维持脚本清单，避免影响现有读页。
        if (o.s("docKey").isBlank() || !withContext(Dispatchers.Main) { t.web?.isAttachedToWindow == true }) return page
        fun box(it: JsonObject): ElementFusion.Box {
            fun n(k: String) = (it[k] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            return ElementFusion.Box(n("x"), n("y"), n("x") + n("w"), n("y") + n("h"))
        }
        val hidden = (o["hidden"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.map(::box)
        val listeners = (o["probes"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.map {
                ElementProbe.Listener((it["label"] as? JsonPrimitive)?.content.orEmpty(), box(it))
            }
        return BrowserCdp.enrich(t, page, o.s("docKey"), hidden, listeners) { count ->
            unwrap(eval(ctx, BrowserScripts.reserve(ctx, o.s("docKey"), count))).toIntOrNull()
        }
    }

    /** 某个编号的元素是什么（决定要不要先问用户）。找不到返回 null。 */
    suspend fun inspect(ctx: Context, n: Int): JsonObject? {
        val t = pinned() ?: tab
        if (BrowserCdp.hasRef(t, n)) return BrowserCdp.inspect(t, n)
        val raw = unwrap(eval(ctx, BrowserScripts.inspect(ctx, n)))
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    /** 对某个编号的元素做动作；CDP 不可用时沿用脚本输入。 */
    suspend fun act(ctx: Context, n: Int, op: String, arg: String = "", clear: Boolean = false): String? {
        agentAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) { error = null; pageDone = CompletableDeferred() }
        val t = pinned() ?: tab
        if (BrowserCdp.hasRef(t, n)) {
            if (op !in setOf("click", "hover", "type", "delete", "key")) {
                return "这个编号来自嵌入页面或封闭组件，暂不支持 $op；请先点开再用编号操作。"
            }
            if (!withContext(Dispatchers.Main) { t.web?.isAttachedToWindow == true }) {
                return "这个嵌入元素目前在后台，不能安全地发真实输入。请先打开它所在标签页。"
            }
            val point = BrowserCdp.locate(t, n) ?: return "编号 $n 已经移走或被挡住，请重新 read。"
            if (op == "key" && !BrowserCdp.focus(t, n)) return "这个元素现在无法获得输入焦点，请重新 read。"
            val sent = BrowserCdp.act(t, point.first, point.second, op, arg, clear)
            t.lastCdpInput = sent == BrowserCdp.Outcome.DONE
            if (sent == BrowserCdp.Outcome.UNCERTAIN) {
                return "CDP 输入中途断开，页面可能已经收到操作。先读页面确认，不要直接重复。"
            }
            if (sent == BrowserCdp.Outcome.FALLBACK) {
                return "CDP 已断开，这个元素没有脚本入口，请重新 read 再试。"
            }
            settle()
            return withContext(Dispatchers.Main) { error }
        }
        var outcome = BrowserCdp.Outcome.FALLBACK
        if (op in setOf("click", "hover", "type", "delete", "key")) {
            // 日期等特殊原生控件仍按原方式设置，普通文本框才用 CDP 输入。
            val info = if (op == "type" || op == "delete") inspect(ctx, n) else null
            val fieldType = (info?.get("type") as? JsonPrimitive)?.content.orEmpty()
            val isText = (info?.get("text") as? JsonPrimitive)?.content == "true"
            val supported = op !in setOf("type", "delete") || (isText &&
                (op != "type" || fieldType !in setOf("date", "datetime-local", "time", "month", "week", "range", "color")))
            // 没挂在界面上的网页（后台标签页）收不收得到 CDP 输入还没在真机上验证过，先走原来的脚本
            val attached = withContext(Dispatchers.Main) { (pinned() ?: tab).web?.isAttachedToWindow == true }
            if (supported && attached) {
                val at = runCatching {
                    json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.center(ctx, n)))).jsonObject
                }.getOrNull()
                if ((at?.get("found") as? JsonPrimitive)?.content == "true") {
                    fun coord(k: String) = (at[k] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                    delay(300)
                    val focused = op != "key" || unwrap(eval(ctx, BrowserScripts.focus(ctx, n))) == "true"
                    if (focused) outcome = BrowserCdp.act(pinned() ?: tab, coord("x"), coord("y"), op, arg, clear)
                }
            }
        }
        (pinned() ?: tab).lastCdpInput = outcome == BrowserCdp.Outcome.DONE
        if (outcome == BrowserCdp.Outcome.UNCERTAIN) {
            return "CDP 输入中途断开，网页可能已经收到这次操作。先读页面确认，不要直接重复提交。"
        }
        val err = if (outcome == BrowserCdp.Outcome.DONE) null else {
            val raw = unwrap(eval(ctx, BrowserScripts.act(ctx, n, op, arg, clear)))
            runCatching { (json.parseToJsonElement(raw).jsonObject["err"] as? JsonPrimitive)?.content }.getOrNull()
        }
        settle()
        return err ?: withContext(Dispatchers.Main) { error }
    }

    /** 按文字找元素，标成编号 0，返回它的情况（和 inspect 一样）；找不到返回 null。 */
    suspend fun markText(ctx: Context, text: String): JsonObject? {
        agentAt = System.currentTimeMillis()
        val raw = unwrap(eval(ctx, BrowserScripts.markText(ctx, text)))
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?.takeIf { (it["found"] as? JsonPrimitive)?.content == "true" }
            ?: BrowserCdp.markText(pinned() ?: tab, text)
    }

    /**
     * 像手指一样在网页窗口上点或划（真的触摸事件）：网页自己画的控件、嵌在里面的别的网站（登录框、支付框）、
     * 要滑动的组件都认。坐标是截图（look）上的像素。网页窗口不在屏幕上时退回用脚本点那个位置。
     */
    suspend fun touch(ctx: Context, x1: Float, y1: Float, x2: Float = x1, y2: Float = y1, durationMs: Long = 60): String? {
        agentAt = System.currentTimeMillis()
        val w = withContext(Dispatchers.Main) { (pinned()?.web ?: webView(ctx)).also { ensureLaidOut(it) } }
        val scale = withContext(Dispatchers.Main) { minOf(1f, 720f / w.width.coerceAtLeast(1)) }
        val (ax, ay, bx, by) = listOf(x1 / scale, y1 / scale, x2 / scale, y2 / scale)
        val inside = withContext(Dispatchers.Main) { listOf(ax, bx).all { it in 0f..w.width.toFloat() } && listOf(ay, by).all { it in 0f..w.height.toFloat() } }
        if (!inside) return "坐标在网页窗口外面（截图大约 ${(w.width * scale).toInt()}×${(w.height * scale).toInt()}）"
        withContext(Dispatchers.Main) { pageDone = CompletableDeferred(); clearError() }
        val cssWidth = unwrap(eval(ctx, "innerWidth")).toFloatOrNull()?.takeIf { it > 0 } ?: w.width.toFloat()
        val outcome = if (!withContext(Dispatchers.Main) { w.isAttachedToWindow }) BrowserCdp.Outcome.FALLBACK
        else BrowserCdp.at(pinned() ?: tab,
            BrowserCdpCoords.fromScreenshot(x1, w.width, cssWidth),
            BrowserCdpCoords.fromScreenshot(y1, w.width, cssWidth),
            BrowserCdpCoords.fromScreenshot(x2, w.width, cssWidth),
            BrowserCdpCoords.fromScreenshot(y2, w.width, cssWidth), durationMs)
        if (outcome == BrowserCdp.Outcome.UNCERTAIN) {
            return "CDP 输入中途断开，网页可能已经收到这次操作。先读页面确认，不要直接重复。"
        }
        if (outcome == BrowserCdp.Outcome.FALLBACK) {
            if (withContext(Dispatchers.Main) { w.isAttachedToWindow }) {
                withContext(Dispatchers.Main) { gesture(w, ax, ay, bx, by, durationMs) }
            } else {
                // 不在屏幕上：触摸事件不一定送得到，退回脚本（只能点，不能划）
                val k = w.width.toFloat() / cssWidth
                eval(ctx, "(function(){var e=document.elementFromPoint(${ax / k},${ay / k});if(e){e.click();}})();1")
            }
        }
        settle()
        return withContext(Dispatchers.Main) { error }
    }

    /** 按下、（划过去）、抬起。在主线程调。 */
    private suspend fun gesture(w: WebView, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val steps = if (x1 == x2 && y1 == y2) 1 else 12
        val t0 = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float, at: Long) {
            val e = android.view.MotionEvent.obtain(t0, at, action, x, y, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            w.dispatchTouchEvent(e)
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, x1, y1, t0)
        for (i in 1..steps) {
            delay(durationMs / steps)
            val f = i.toFloat() / steps
            send(android.view.MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, android.os.SystemClock.uptimeMillis())
        }
        send(android.view.MotionEvent.ACTION_UP, x2, y2, android.os.SystemClock.uptimeMillis())
    }

    /** 截图上这个位置是什么（动手前看要不要问用户）：和 inspect 一样的信息，或 frame=true（读不到的嵌入框）。 */
    suspend fun elementAt(ctx: Context, x: Float, y: Float): JsonObject? {
        val (cx, cy) = withContext(Dispatchers.Main) {
            val w = webView(ctx)
            val scale = minOf(1f, 720f / w.width.coerceAtLeast(1))
            x / scale to y / scale
        }
        val k = withContext(Dispatchers.Main) { webView(ctx).width.toFloat() } / unwrap(eval(ctx, "innerWidth")).toFloat().coerceAtLeast(1f)
        return runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.at(ctx, cx / k, cy / k)))).jsonObject }.getOrNull()
    }

    /** 编号 n 的元素中心换成截图上的像素（先滚到屏幕中间）。找不到返回 null。 */
    suspend fun centerOf(ctx: Context, n: Int): Pair<Float, Float>? {
        val t = pinned() ?: tab
        if (BrowserCdp.hasRef(t, n)) {
            val center = BrowserCdp.locate(t, n) ?: return null
            val w = withContext(Dispatchers.Main) { t.web ?: webView(ctx) }
            val cssW = unwrap(eval(ctx, "innerWidth")).toFloatOrNull()?.coerceAtLeast(1f) ?: w.width.toFloat()
            val k = w.width / cssW * minOf(1f, 720f / w.width.coerceAtLeast(1))
            return (center.first * k).toFloat() to (center.second * k).toFloat()
        }
        val o = runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.center(ctx, n)))).jsonObject }.getOrNull() ?: return null
        if ((o["found"] as? JsonPrimitive)?.content != "true") return null
        fun f(k: String) = (o[k] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
        delay(300)
        return withContext(Dispatchers.Main) {
            val w = webView(ctx)
            val k = w.width / f("vw").coerceAtLeast(1f)
            val scale = minOf(1f, 720f / w.width.coerceAtLeast(1))
            f("x") * k * scale to f("y") * k * scale
        }
    }

    /** 页面有没有变：网址 + 文字长度 + 元素个数（点了以后比一比，看点没点上）。 */
    suspend fun pageSignature(ctx: Context): String {
        val url = withContext(Dispatchers.Main) { (pinned() ?: tab).address }
        return url + "|" + unwrap(eval(ctx, BrowserScripts.fingerprint(ctx)))
    }

    /** 等网页里出现这段文字（最多 timeoutMs）；text 为空就等页面不再变化。返回等到没有。 */
    suspend fun waitFor(ctx: Context, text: String, timeoutMs: Long): Boolean {
        agentAt = System.currentTimeMillis()
        if (text.isBlank()) return waitStable(ctx, timeoutMs)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (unwrap(eval(ctx, BrowserScripts.hasText(ctx, text))) == "true") return true
            delay(500)
        }
        return false
    }

    /** 等页面加载完、内容不再变（连着两次一样）。返回在时限内稳没稳下来。 */
    private suspend fun waitStable(ctx: Context, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var last = ""
        var same = 0
        while (System.currentTimeMillis() < end) {
            val sig = unwrap(eval(ctx, BrowserScripts.signature(ctx)))
            val busy = withContext(Dispatchers.Main) { (pinned() ?: tab).loading < 100 }
            if (!busy && sig == last) { if (++same >= 2) return true } else same = 0
            last = sig
            delay(400)
        }
        return false
    }

    /** 读整页：what = text / links / table。返回网页给的 JSON。 */
    suspend fun extract(ctx: Context, what: String, start: Int = 0, limit: Int = 12_000): JsonObject? {
        agentAt = System.currentTimeMillis()
        return runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.extract(ctx, what, start, limit)))).jsonObject }.getOrNull()
    }

    /** Kavi 要上传的文件：网页弹出选文件时直接交给它（不弹选择器）。uploadTaken 表示网页已经拿走了。 */
    @Volatile var agentUpload: android.net.Uri? = null
    @Volatile var uploadTaken = false

    /**
     * 上传对话文件夹里的文件：用手指点编号 n 的上传按钮（网页只认真的点击才肯弹选文件），
     * 网页要文件时把这个文件交过去。返回出错原因，成功返回 null。
     */
    suspend fun upload(ctx: Context, n: Int, file: java.io.File): String? {
        val (x, y) = centerOf(ctx, n) ?: return "找不到编号 $n，页面可能变了"
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
        uploadTaken = false
        agentUpload = uri
        try {
            touch(ctx, x, y)?.let { return it }
            val end = System.currentTimeMillis() + 3000
            while (!uploadTaken && System.currentTimeMillis() < end) delay(200)
            if (!uploadTaken) return "点了编号 $n，但网页没有要文件（可能不是上传按钮，或者网页窗口不在屏幕上）。可以 look 看看上传按钮在哪，或者请用户自己传。"
            settle()
            return null
        } finally {
            agentUpload = null
        }
    }

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
    fun pin(t: Tab) = PinTab(t)
    private suspend fun pinned(): Tab? = kotlin.coroutines.coroutineContext[PinTab]?.t

    /** 关掉某个标签页（按对象找，并行抓完收拾用）。 */
    fun closeTab(t: Tab) { tabs.indexOf(t).takeIf { it >= 0 }?.let { closeTab(it) } }

    /** 标签页清单（给 Kavi 看）：第几个、标题、网址、是不是当前。 */
    fun tabList(): String = tabs.mapIndexed { i, t ->
        "${i + 1}. ${t.badge}${t.title.ifBlank { if (t.address.isEmpty()) "起始页" else BrowserPages.host(t.address) }} ${t.address}" + if (t === tab) "（当前）" else ""
    }.joinToString("\n")

    /** 关掉挡着网页的弹窗（下 App、领券、Cookie 提示），返回关掉了什么。 */
    suspend fun dismissPopups(ctx: Context): List<String> {
        val raw = unwrap(eval(ctx, BrowserScripts.dismiss(ctx)))
        val closed = runCatching { (json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content } }.getOrDefault(emptyList())
        if (closed.isNotEmpty()) delay(300)
        return closed
    }

    /** 滚到页面最底下（往下划才加载更多的页面用），等新内容出来。 */
    suspend fun scrollToBottom(ctx: Context) {
        agentAt = System.currentTimeMillis()
        eval(ctx, "window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)); 1")
        delay(600)
        waitStable(ctx, 5_000)
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
        delay(500)
        val pending = withContext(Dispatchers.Main) { pageDone?.takeIf { loading < 100 } }
        if (pending != null) withTimeoutOrNull(15_000) { pending.await() }
        // 很多网页加载完还在慢慢往里填内容：等它不再变（最多 4 秒）
        runCatching { waitStable(app, 4_000) }
        // 后台请求还没回来的话，数据可能还没刷新：再等它们回来（最多 5 秒，长连接不等）
        runCatching { waitNetQuiet(app, 5_000) }
    }

    /** 等网页的后台请求都回来。钩子没装上就不等。 */
    private suspend fun waitNetQuiet(ctx: Context, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        var waited = false
        while (System.currentTimeMillis() < end) {
            val (inflight, _) = BrowserNetWatch.state(eval(ctx, BrowserNetWatch.STATE_JS)) ?: return
            if (inflight == 0) { if (waited) runCatching { waitStable(ctx, 1_500) }; return }
            waited = true
            delay(300)
        }
    }

    /** 现在的请求失败序号（动作前记一下，做完用 [netFailsSince] 取这期间失败的）。钩子没装上是 null。 */
    suspend fun netSeq(ctx: Context): Int? = runCatching { BrowserNetWatch.state(eval(ctx, BrowserNetWatch.STATE_JS))?.second }.getOrNull()

    suspend fun netFailsSince(ctx: Context, seq: Int): List<BrowserNetWatch.Fail> =
        runCatching { BrowserNetWatch.fails(eval(ctx, BrowserNetWatch.failsSinceJs(seq))) }.getOrDefault(emptyList())

    /**
     * 这一步做完的网页截个小图（不画编号框），存成 JPEG，对话里按步骤显示。网页没画出来就不存，返回 false。
     */
    suspend fun snapshot(ctx: Context, out: java.io.File, maxWidth: Int = 480): Boolean {
        val bmp = withContext(Dispatchers.Main) {
            val w = webView(ctx)
            ensureLaidOut(w)
            if (w.width <= 0 || w.height <= 0) return@withContext null
            val scale = minOf(1f, maxWidth.toFloat() / w.width)
            val b = Bitmap.createBitmap((w.width * scale).toInt(), (w.height * scale).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(b)
            c.scale(scale, scale)
            c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            val sample = IntArray(16) { i -> b.getPixel((b.width * (i % 4 + 1) / 5).coerceAtMost(b.width - 1), (b.height * (i / 4 + 1) / 5).coerceAtMost(b.height - 1)) }
            if (sample.distinct().size == 1) { b.recycle(); null } else b
        } ?: return false
        return withContext(Dispatchers.IO) {
            try {
                out.parentFile?.mkdirs()
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                true
            } catch (e: Exception) {
                false
            } finally {
                bmp.recycle()
            }
        }
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

/** 网页进程没了以后这个网页窗口就不能用了：从界面上拿下来、销毁，告诉系统已经处理（返回 true）。 */
internal fun WebView.dropAfterRendererGone(): Boolean {
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { destroy() }
    return true
}
