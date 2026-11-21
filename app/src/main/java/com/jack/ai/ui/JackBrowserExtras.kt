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
import com.jack.ai.ui.JackBrowser.Tab
import com.jack.ai.ui.JackBrowser.SiteData
import com.jack.ai.ui.JackBrowser.Pressed

// 浏览器的零碎功能：长的浏览记录、屏蔽网页上的一块、演示教学、存进资料库、离线网页、网站数据（从 JackBrowser.kt 拆出来，是 JackBrowser 的扩展函数）。

internal fun JackBrowser.recordHistory(url: String, title: String) {
    val now = System.currentTimeMillis()
    scope.launch(historyIo) {
        val store = com.jack.ai.data.Store(app)
        val list = historyList ?: store.loadHistory()
        historyList = com.jack.ai.data.browser.BrowserHistory.record(list, url, title, now)
        // 连着跳好几页只写一次
        historySave?.cancel()
        historySave = scope.launch(historyIo) { delay(2_000); historyList?.let { store.saveHistory(it) } }
    }
}

internal fun JackBrowser.applyBlocks(view: WebView, site: String) {
    val sels = BrowserBlocks.forSite(blocks, site)
    if (sels.isNotEmpty() || blocks.isNotEmpty()) view.evaluateJavascript(BrowserBlocks.injectJs(sels), null)
}

/** 当前网站屏蔽了哪些。 */
internal fun JackBrowser.blocksHere(): List<String> = blocks[BrowserPages.site(address)].orEmpty()

internal fun JackBrowser.startPick() {
    val w = web ?: return
    picked = null
    picking = true
    w.evaluateJavascript(BrowserBlocks.PICK_JS, null)
}

/** 选中的范围往外扩一层（点到的是里面的小字、图标时）。 */
internal fun JackBrowser.pickWider() { web?.evaluateJavascript("window.__jackPickUp&&window.__jackPickUp()", null) }

internal fun JackBrowser.stopPick() {
    picking = false
    picked = null
    web?.evaluateJavascript("window.__jackPickStop&&window.__jackPickStop()", null)
}

/** 把选中的那块存成这个网站的屏蔽规则，马上藏起来。 */
internal fun JackBrowser.confirmPick() {
    val p = picked ?: return
    val site = BrowserPages.site(address)
    stopPick()
    if (site.isEmpty()) return
    blocks = BrowserBlocks.add(blocks, site, p.selector)
    saveBlocks()
    web?.let { applyBlocks(it, site) }
    notice = "已屏蔽" + (if (p.count > 1) "（这一页有 ${p.count} 处一样的，都藏了）" else "") + "。在菜单 → 屏蔽网页上的东西 里能恢复。"
}

internal fun JackBrowser.unblock(selector: String) {
    val site = BrowserPages.site(address)
    blocks = BrowserBlocks.remove(blocks, site, selector)
    saveBlocks()
    web?.let { applyBlocks(it, site) }
}

internal fun JackBrowser.saveBlocks() { prefs().edit().putString("blocks", listJson.encodeToString(blockMap, blocks)).apply() }

internal fun JackBrowser.installTeach(t: Tab) {
    val w = t.web ?: return
    if (t.teachScript == null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        t.teachScript = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, BrowserTeach.RECORD_JS, setOf("*"))
    }
    // 已经打开的这一页也装上
    w.evaluateJavascript(BrowserTeach.RECORD_JS, null)
}

internal fun JackBrowser.startTeach() {
    teaching = true
    teachSteps = if (BrowserPages.isWeb(address)) listOf("打开 $address") else emptyList()
    installTeach(tab)
}

/** 停止记录，返回记下的步骤。 */
internal fun JackBrowser.stopTeach(): List<String> {
    teaching = false
    tabs.forEach { t -> t.teachScript?.remove(); t.teachScript = null }
    // 页面里的监听刷新后才没（只往 console 打，不碍事）
    return teachSteps.also { teachSteps = emptyList() }
}

// ---- 存进资料库 ----
/** 把当前网页的正文存进资料库（Kavi 以后能搜到）。返回给用户看的话。 */
internal suspend fun JackBrowser.saveToLibrary(ctx: Context): String {
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

internal fun JackBrowser.saveLists() {
    prefs().edit().putString("visits", listJson.encodeToString(visitList, visits)).putString("marks", listJson.encodeToString(visitList, marks)).apply()
}

/** 当前网页加进书签或从书签去掉。 */
internal fun JackBrowser.toggleMark() {
    marks = BrowserPages.toggle(marks, address, title, System.currentTimeMillis())
    saveLists()
}

internal fun JackBrowser.removeMark(url: String) {
    marks = marks.filterNot { it.url == url }
    saveLists()
}

/** 导入别的浏览器导出的 bookmarks.html。返回 (文件里有几个, 新加了几个)。 */
internal fun JackBrowser.importMarks(html: String): Pair<Int, Int> {
    val parsed = com.jack.ai.api.BrowserBookmarks.parse(html)
    val (merged, added) = com.jack.ai.api.BrowserBookmarks.merge(marks, parsed)
    if (added > 0) { marks = merged; saveLists() }
    return parsed.size to added
}

internal fun JackBrowser.offlineDir() = java.io.File(app.filesDir, "offline_pages").apply { mkdirs() }

internal fun JackBrowser.offlineFile(p: BrowserPages.OfflinePage) = java.io.File(offlineDir(), p.file)

internal fun JackBrowser.saveOfflineList() { prefs().edit().putString("offline", listJson.encodeToString(offlineList, offline)).apply() }

/** 把当前网页整页存下来（同一个网址再存就换成新的）。返回给用户看的一句话。 */
internal suspend fun JackBrowser.saveOffline(): String = withContext(Dispatchers.Main) {
    val w = web ?: return@withContext "没有打开的网页"
    val url = address
    if (!BrowserPages.isWeb(url)) return@withContext "这一页存不了"
    if (tab.incognito) return@withContext "隐身标签页不存离线网页"
    val name = "${System.currentTimeMillis()}.mht"
    val f = java.io.File(offlineDir(), name)
    val path = withTimeoutOrNull(60_000) { suspendCancellableCoroutine<String?> { c -> w.saveWebArchive(f.path, false) { c.resume(it) } } }
    if (path == null || !f.exists() || f.length() == 0L) { f.delete(); return@withContext "没存成（这个网页不让存，或者还没加载完）" }
    offline.filter { it.url == url }.forEach { offlineFile(it).delete() }
    offline = listOf(BrowserPages.OfflinePage(url, title.ifBlank { BrowserPages.host(url) }, System.currentTimeMillis(), name, f.length())) +
        offline.filterNot { it.url == url }
    saveOfflineList()
    "已存成离线网页（${com.jack.ai.api.BrowserSiteData.sizeText(f.length())}），没网也能看：菜单 →「离线网页」"
}

internal fun JackBrowser.removeOffline(p: BrowserPages.OfflinePage) {
    offlineFile(p).delete()
    offline = offline.filterNot { it.file == p.file }
    saveOfflineList()
}

internal fun JackBrowser.storageOf(t: Tab): WebStorage {
    val name = if (t.incognito) INCOGNITO else if (t.account > 0) accountProfile(t.account) else null
    return name?.takeIf { incognitoSupported() }?.let { androidx.webkit.ProfileStore.getInstance().getProfile(it)?.webStorage } ?: WebStorage.getInstance()
}

/** 当前网页所在网站的 Cookie 个数、网页存储大小（主域名和子域名一起算）。 */
internal suspend fun JackBrowser.siteData(): SiteData? = withContext(Dispatchers.Main) {
    val host = runCatching { java.net.URI(address).host }.getOrNull()?.lowercase() ?: return@withContext null
    val root = com.jack.ai.api.BrowserSiteData.root(host)
    val origins = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine<Map<*, *>?> { c -> storageOf(tab).getOrigins { c.resume(it) } }
    }.orEmpty().values.filterIsInstance<WebStorage.Origin>()
        .filter { o -> runCatching { java.net.URI(o.origin).host }.getOrNull()?.let { com.jack.ai.api.BrowserSiteData.belongs(it, root) } == true }
    val names = cookieHosts(host, root, origins.map { it.origin }).flatMap { h ->
        com.jack.ai.api.BrowserSiteData.names(cookiesOf(tab).getCookie("https://$h"))
    }.toSet()
    SiteData(root, names.size, origins.sumOf { it.usage }, origins.map { it.origin })
}

internal fun JackBrowser.cookieHosts(host: String, root: String, origins: List<String>): List<String> =
    (listOf(host, root, "www.$root") + origins.mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() }).map { it.lowercase() }.distinct()

/** 只清这个网站的 Cookie（登录状态）和网页存储，然后重新打开这一页。 */
internal suspend fun JackBrowser.clearSite(d: SiteData) = withContext(Dispatchers.Main) {
    val host = runCatching { java.net.URI(address).host }.getOrNull()?.lowercase() ?: d.root
    val path = runCatching { java.net.URI(address).path }.getOrNull().orEmpty()
    val cm = cookiesOf(tab)
    val hosts = cookieHosts(host, d.root, d.origins)
    val names = hosts.flatMap { h -> com.jack.ai.api.BrowserSiteData.names(cm.getCookie("https://$h")) + com.jack.ai.api.BrowserSiteData.names(cm.getCookie("http://$h")) }.toSet()
    com.jack.ai.api.BrowserSiteData.expire(names, hosts, d.root, path).forEach { (u, c) -> cm.setCookie(u, c) }
    cm.flush()
    val ws = storageOf(tab)
    d.origins.forEach { ws.deleteOrigin(it) }
    reloadCurrent()
}

/** 网页跟着 App 的深浅色：浅色主题时不变暗。跟着系统深色走（网页组件看的是系统设置）。 */
internal fun JackBrowser.setDark(allowed: Boolean) {
    darkAllowed = allowed
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
    tabs.forEach { t -> t.web?.let { androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(it.settings, allowed) } }
}

/** 回到起始页（书签、最近访问）。能后退回刚才的网页。 */
internal fun JackBrowser.home() {
    val w = web ?: return
    clearError()
    tab.restore = null
    w.loadUrl("about:blank")
    address = ""; title = ""
}

/**
 * 网页窗口到了这个网址，地址栏该显示什么。空白页（about:blank，回起始页时打开的那一页）算起始页，地址是空的：
 * 后退、前进回到它时也一样，不然地址栏还留着别的网址、起始页不出来，只看到一整块空白（深色下是灰的）。
 * 网页自己弹出的窗口（登录窗口先开空白页再往里写）不算。
 */
internal fun JackBrowser.shownAddress(t: Tab, url: String): String = if (url == "about:blank" && t.openedBy == null) "" else url

/** 用户或 Kavi 开始一次新的跳转：上一页的出错提示不再适用。 */
internal fun JackBrowser.clearError() { error = null; pageFailed = false }

/** 长按链接或图片：记下来让浏览器页弹菜单；长按文字照旧选字。Kavi 在操作时不弹。 */
internal fun JackBrowser.longPress(w: WebView): Boolean {
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
internal fun JackBrowser.downloadImage(url: String) {
    if (!BrowserPages.isWeb(url)) { notice = "这张图片是网页内嵌的，没法单独下载。可以截屏保存。"; return }
    pending = BrowserDownload.Pending(url, BrowserDownload.nameFor(url, null, "image/jpeg"), "", -1,
        web?.settings?.userAgentString.orEmpty(), address, byUser = true)
}

/** 放进某个界面：先从原来的地方拿出来。ctx 是那个界面所在的页面。 */
internal fun JackBrowser.claim(ctx: Context): WebView {
    val w = webView(ctx)
    (w.parent as? ViewGroup)?.removeView(w)
    wrapper.baseContext = ctx
    return w
}

/**
 * 浏览器页回到前台：网页窗口在它不在前台时被聊天页拿走了（浏览器页是 singleTask，切走不销毁），
 * 它的框里就空了、只剩灰底。让所有放网页的地方重新来要，前台这个会拿回来。
 */
internal fun JackBrowser.reclaim(ctx: Context) {
    val w = web ?: return
    if (w.parent == null || wrapper.baseContext !== ctx) hostGen++
}

/** 那个界面不用了：Context 换回 App；handBack 时让别的地方（聊天页）重新来要。 */
internal fun JackBrowser.release(ctx: Context, handBack: Boolean = true) {
    if (wrapperReady && wrapper.baseContext === ctx) wrapper.baseContext = app
    if (handBack) hostGen++
}

internal fun JackBrowser.changeRoute(r: String) {
    route = r
    if (appReady) app.getSharedPreferences("browser", Context.MODE_PRIVATE).edit().putString("route", r).apply()
}
