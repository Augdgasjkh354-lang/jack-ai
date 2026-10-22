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
import com.jack.ai.api.BrowserNet
import com.jack.ai.api.Net
import com.jack.ai.data.ProxyCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var address by mutableStateOf("")
    var title by mutableStateOf("")
    var loading by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** 网页想打开别的 App，等用户点头（只在用户自己看着浏览器时问）。 */
    var external by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var route by mutableStateOf(BrowserNet.AUTO)
        private set
    /** Jack 最近一次操作网页的时刻；聊天页据此显示小窗。 */
    var agentAt by mutableLongStateOf(0L)
    /** 网页窗口换了地方（浏览器页 ↔ 聊天小窗）就加一，让原来的地方重新来要。 */
    var hostGen by mutableIntStateOf(0)
    /** 用户正开着浏览器页。 */
    var userViewing = false

    private var web: WebView? = null
    private lateinit var wrapper: MutableContextWrapper
    private lateinit var app: Context

    /** 网页上次是按哪个代理端口设的（0 = 直连）；内核重启换了端口就要重设。 */
    private var appliedPort = -1
    private var appliedRoute = ""
    /** 正在加载的页面加载完时完成。 */
    private var pageDone: CompletableDeferred<Unit>? = null
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
        return WebView(wrapper).apply {
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
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = Client()
            webChromeClient = Chrome()
            setDownloadListener { url, _, _, _, _ ->
                notice = "Jack 浏览器暂时不能下载文件。可以用菜单里的「复制链接」，换别的方式下载：\n$url"
            }
            web = this
        }
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
        withContext(Dispatchers.Main) { error = null }
        when (BrowserNet.classify(url)) {
            BrowserNet.Kind.OPEN -> {}
            BrowserNet.Kind.EXTERNAL -> return "这是打开别的 App 的链接，浏览器里打不开：$url"
            BrowserNet.Kind.BLOCKED -> return "不能打开这个地址（本机、局域网和文件地址都不开）：$url"
        }
        prepare(ctx)?.let { reason -> withContext(Dispatchers.Main) { error = reason }; return reason }
        val done = withContext(Dispatchers.Main) {
            CompletableDeferred<Unit>().also { pageDone = it; webView(ctx).loadUrl(url) }
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

    private class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (BrowserNet.classify(url)) {
                BrowserNet.Kind.OPEN -> false
                BrowserNet.Kind.EXTERNAL -> { if (userViewing && request.hasGesture()) external = url; true }
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
                // 并行的连接各要一次（实测一个页面 6 次）
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
            if (pageDone?.isCompleted != false) pageDone = CompletableDeferred()
            address = url
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }

        override fun onPageFinished(view: WebView, url: String) {
            pageDone?.complete(Unit)
            if (url != "about:blank") address = url
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url != "about:blank") address = url
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { loading = newProgress }
        override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
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

    // ---------- Jack 操作网页 ----------

    private val json = Json { ignoreUnknownKeys = true }

    /** 页面的一项可操作元素。坐标是网页像素（CSS px），截图上画框用。 */
    data class Item(val n: Int, val kind: String, val label: String, val value: String?, val checked: Boolean?,
                    val options: List<String>, val href: String?, val x: Float, val y: Float, val w: Float, val h: Float)

    data class Page(val url: String, val title: String, val items: List<Item>, val above: Int, val below: Int,
                    val scroll: Int, val height: Int, val vh: Int, val text: String,
                    /** 可视区在网页里的位置和宽度（CSS px），截图时把元素坐标换成屏幕像素。 */
                    val vx: Float, val vy: Float, val vw: Float, val error: String?, val dialog: String?)

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
            withContext(Dispatchers.Main) { error }, dialog)
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
            if (w.canGoBack()) { pageDone = CompletableDeferred(); w.goBack(); true } else false
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
