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
import com.jack.ai.ui.JackBrowser.Client
import com.jack.ai.ui.JackBrowser.Chrome
import com.jack.ai.ui.JackBrowser.PermAsk

// 浏览器的标签页：分组、网页权限、恢复、地址栏联想、休眠（从 JackBrowser.kt 拆出来，是 JackBrowser 的扩展函数）。

// ---------- 标签页分组 ----------
/** 现在有的分组（按第一次出现的先后）。 */
internal fun JackBrowser.groups(): List<String> = tabs.map { it.group }.filter { it.isNotBlank() }.distinct()

internal fun JackBrowser.setGroup(t: Tab, group: String) { t.group = group.trim().take(20); saveTabs() }

internal fun JackBrowser.renameGroup(from: String, to: String) {
    val name = to.trim().take(20)
    if (name.isEmpty()) return
    tabs.filter { it.group == from }.forEach { it.group = name }
    saveTabs()
}

internal fun JackBrowser.ungroup(group: String) { tabs.filter { it.group == group }.forEach { it.group = "" }; saveTabs() }

/** 关掉一组（或 null = 全部）标签页。最后剩一个时回起始页。 */
internal fun JackBrowser.closeGroup(group: String?) {
    tabs.filter { group == null || it.group == group }.forEach { closeTab(it) }
    saveTabs()
}

/** 给标签页建网页窗口（新开时，或网页进程崩了重建时）。 */
@SuppressLint("SetJavaScriptEnabled")
internal fun JackBrowser.buildWeb(t: Tab): WebView {
    val profile = if (t.incognito) INCOGNITO else if (t.account > 0) accountProfile(t.account) else null
    t.fresh = true
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
            textZoom = JackBrowser.textZoom
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
internal fun JackBrowser.recover(t: Tab, dead: WebView) {
    val url = t.address
    (dead.parent as? ViewGroup)?.removeView(dead)
    BrowserCdp.release(t)
    BrowserNetwork.release(t.id)
    BrowserPacing.release(t.id)
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

/**
 * 先看记住的：不允许过就直接拒；都允许过就只向系统要手机权限。没记过的弹窗问用户。
 * Kavi 在操作（用户没看着）、隐身页记住的不算数，也不记。
 */
internal fun JackBrowser.askPerm(t: Tab, origin: String, kinds: Set<String>, done: (Boolean) -> Unit) {
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
internal fun JackBrowser.permsHere(): Boolean = BrowserPages.site(address).let { s -> s.isNotEmpty() && (permYes + permNo).any { it.startsWith("$s|") } }

internal fun JackBrowser.resetPermsHere() {
    val s = BrowserPages.site(address).ifEmpty { return }
    permYes = permYes.filterNot { it.startsWith("$s|") }.toSet(); permNo = permNo.filterNot { it.startsWith("$s|") }.toSet()
    prefs().edit().putStringSet("permyes", permYes).putStringSet("permno", permNo).apply()
}

/** 记下现在开着的标签页（隐身的不记），离开浏览器页时调。 */
internal fun JackBrowser.saveTabs() {
    if (!appReady || tabs.isEmpty()) return
    val s = BrowserPages.tabsToSave(tabs.map { BrowserPages.SavedTab(it.restore ?: it.address, it.title, it.account, it.group) to (it.incognito || com.jack.ai.tools.BrowserLock.isAgentTab(it)) }, current, MAX_TABS)
    prefs().edit().putString("tabs", listJson.encodeToString(savedTabs, s)).apply()
}

/**
 * App 重开后第一次进浏览器：把上次的标签页建回来（只建窗口，切过去时才加载，见 [Tab.restore]）。
 * 已经有标签页（比如 Kavi 先用过浏览器）就不动。返回有没有恢复。
 */
internal fun JackBrowser.restoreTabs(ctx: Context): Boolean {
    if (tabs.isNotEmpty()) return false
    webView(ctx)
    val s = prefs().getString("tabs", null)?.let { runCatching { listJson.decodeFromString(savedTabs, it) }.getOrNull() }
    if (s == null || s.tabs.isEmpty()) return false
    // 只建要显示的那页的网页窗口，别的先休眠，切过去再开
    s.tabs.take(MAX_TABS).forEachIndexed { i, st ->
        newTab(switch = false, account = st.account, group = st.group, build = i == s.current).apply {
            address = st.url; title = st.title; restore = st.url; usedAt = if (i == s.current) System.currentTimeMillis() else i.toLong()
        }
    }
    // 第一个空白页是 webView() 建的，不要了。先把「眼前这页」指到要显示的那一页：
    // 不然关空白页时会先切到排第一的那页、白白给它建一个网页窗口
    current = (s.current.coerceIn(0, tabs.lastIndex - 1) + 1).coerceAtMost(tabs.lastIndex)
    closeTab(0)
    switchTo(s.current.coerceIn(0, tabs.lastIndex))
    return true
}

/** 长的浏览记录先读进来，打字联想时用。 */
internal fun JackBrowser.warmHistory() {
    if (!appReady || historyList != null) return
    scope.launch(historyIo) { if (historyList == null) historyList = com.jack.ai.data.Store(app).loadHistory() }
}

/** 历史记录页要的完整浏览记录。 */
internal suspend fun JackBrowser.historyEntries(): List<com.jack.ai.data.browser.HistoryEntry> = withContext(historyIo) {
    historyList ?: com.jack.ai.data.Store(app).loadHistory().also { historyList = it }
}

/** 删掉一条浏览记录（起始页的最近访问里也去掉）。 */
internal fun JackBrowser.deleteHistory(url: String) {
    visits = visits.filterNot { it.url == url }; saveLists()
    scope.launch(historyIo) {
        val store = com.jack.ai.data.Store(app)
        historyList = com.jack.ai.data.browser.BrowserHistory.remove(historyList ?: store.loadHistory(), url)
        historySave?.cancel()
        store.saveHistory(historyList.orEmpty())
    }
}

/** 清空浏览记录和最近访问（登录状态、缓存不动）。 */
internal fun JackBrowser.clearHistory() {
    visits = emptyList(); saveLists()
    historySave?.cancel()
    scope.launch(historyIo) { historyList = emptyList(); com.jack.ai.data.Store(app).saveHistory(emptyList()) }
}

/** 地址栏输入时的联想：书签、最近访问、浏览记录里对得上的。 */
internal fun JackBrowser.suggest(query: String): List<BrowserPages.Visit> =
    BrowserPages.suggest(query, marks, visits + historyList.orEmpty().map { BrowserPages.Visit(it.url, it.title, it.at) })

/** 切到第 i 个标签页：网页窗口换了，让浏览器页重新来要。 */
internal fun JackBrowser.switchTo(i: Int) {
    if (i !in tabs.indices) return
    // Kavi 在自己的后台标签页里切标签：只换它钉住的那页，不动用户眼前的
    pinLocal.get()?.let { it.t = tabs[i]; wake(tabs[i]); return }
    if (i != current) snap()
    barsHidden = false
    current = i
    tabs[i].usedAt = System.currentTimeMillis()
    wake(tabs[i])
    hostGen++
    sleepIdle()
}

/** 休眠的标签页切过来时：补开网页窗口（要打开的网址在 restore 里，由浏览器页或 load 打开）。 */
internal fun JackBrowser.wake(t: Tab) {
    if (t.web != null || t !in tabs || !wrapperReady) return
    t.web = buildWeb(t)
    hostGen++
}

/** 能不能休眠：眼前的、Kavi 正用着的、在放声音的、在下载的、在录教学或诊断的都不动。 */
internal fun JackBrowser.canSleep(t: Tab): Boolean = t.web != null && t !== tabs.getOrNull(current) && t !== pinLocal.get()?.t &&
    !com.jack.ai.tools.BrowserLock.isAgentTab(t) && !t.audible && !t.mediaFromUser && pending?.tab != t.id && !t.diagOn &&
    !(teaching && t === tab) && t.loading >= 100

/** 开着网页的标签页超过 [LIVE_MAX] 个：最久没看的先休眠。 */
internal fun JackBrowser.sleepIdle() {
    val live = tabs.filter { it.web != null }
    BrowserPages.toSleep(live.map { Triple(it.id, it.usedAt, canSleep(it)) }, LIVE_MAX).forEach { id -> live.firstOrNull { it.id == id }?.let(::sleep) }
}

/** 手机内存紧：眼前这页以外能休眠的全休眠。 */
internal fun JackBrowser.sleepAllBackground() { tabs.filter(::canSleep).forEach(::sleep) }

internal fun JackBrowser.sleep(t: Tab) {
    val w = t.web ?: return
    if (t.restore == null && BrowserPages.isWeb(t.address)) {
        t.sleepState = android.os.Bundle().also { b -> runCatching { w.saveState(b) } }.takeIf { !it.isEmpty }
        t.restore = t.address
    }
    (w.parent as? ViewGroup)?.removeView(w)
    BrowserCdp.release(t)
    BrowserNetwork.release(t.id)
    BrowserPacing.release(t.id)
    runCatching { w.destroy() }
    t.web = null
    t.netScript = null; t.diagScript = null; t.teachScript = null; t.rtcScript = null; t.deskScript = null
    t.pageDone?.complete(Unit)
    t.loading = 100
    t.media.clear(); t.mediaFrames.clear()
}

/** 关掉一个标签页（最后一个不关，只回起始页）。 */
internal fun JackBrowser.closeTab(i: Int) {
    if (i !in tabs.indices) return
    if (tabs.size == 1) { home(); return }
    val t = tabs.removeAt(i)
    (t.web?.parent as? ViewGroup)?.removeView(t.web)
    BrowserCdp.release(t)
    BrowserNetwork.release(t.id)
    BrowserPacing.release(t.id)
    t.web?.destroy()
    if (t.incognito && tabs.none { it.incognito }) runCatching { clearIncognito() }
    val stay = if (i < current) current - 1 else current.coerceAtMost(tabs.lastIndex)
    // 在 Kavi 的后台操作里关的：用户眼前那页只挪位置，不跟着切
    if (pinLocal.get() != null) { current = stay; hostGen++ } else switchTo(stay)
    // 关的是哪个 Kavi 正用着的后台标签页：给它换一页（弹出的登录窗口关了就回到打开它的那页）
    com.jack.ai.tools.BrowserLock.tabClosed(t) { tabs.firstOrNull { it.id == t.openedBy } ?: newTab(switch = false) }
}

/** Kavi 在后台标签页里要用户接手（登录、验证）：把用户眼前的换成它那页，打开浏览器就能看到。 */
internal fun JackBrowser.showToUser() {
    val p = pinLocal.get() ?: return
    val i = tabs.indexOf(p.t).takeIf { it >= 0 } ?: return
    if (i != current) { snap(); current = i; barsHidden = false; hostGen++ }
}

internal fun JackBrowser.prefs() = app.getSharedPreferences("browser", Context.MODE_PRIVATE)

internal fun JackBrowser.laterFile(url: String): java.io.File {
    val hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    return java.io.File(app.filesDir, "readlater/$hash.md")
}

internal fun JackBrowser.saveLater() { prefs().edit().putString("readlater", listJson.encodeToString(laterList, later)).apply() }

internal fun JackBrowser.removeLater(url: String) {
    later = BrowserPages.removeLater(later, url); saveLater()
    scope.launch(Dispatchers.IO) { laterFile(url).delete() }
}

internal fun JackBrowser.markLaterRead(url: String) { later = BrowserPages.readLater(later, url); saveLater() }

internal fun JackBrowser.addLater(url: String, name: String = "") {
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

internal suspend fun JackBrowser.offlineLater(url: String): Pair<String, String>? = withContext(Dispatchers.IO) {
    val entry = later.find { it.url == url } ?: return@withContext null
    runCatching { entry.title to laterFile(url).readText() }.getOrNull()
}

internal fun JackBrowser.loadLists() {
    fun read(k: String) = prefs().getString(k, null)?.let { runCatching { listJson.decodeFromString(visitList, it) }.getOrNull() }.orEmpty()
    visits = read("visits"); marks = read("marks")
    offline = prefs().getString("offline", null)?.let { runCatching { listJson.decodeFromString(offlineList, it) }.getOrNull() }.orEmpty()
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
    trackBlock = prefs().getBoolean("trackblock", true); trackBlockNow = trackBlock
    httpsOnly = prefs().getBoolean("httpsonly", true)
    httpOk = prefs().getStringSet("httpok", emptySet()).orEmpty().toSet()
    loadScripts()
    adAllow = prefs().getStringSet("adallow", emptySet()).orEmpty().toSet(); adAllowNow = adAllow
    permYes = prefs().getStringSet("permyes", emptySet()).orEmpty().toSet()
    permNo = prefs().getStringSet("permno", emptySet()).orEmpty().toSet()
    blocks = prefs().getString("blocks", null)?.let { runCatching { listJson.decodeFromString(blockMap, it) }.getOrNull() }.orEmpty()
}

internal fun JackBrowser.uaFor(url: String) = if (isDesktopAt(url)) BrowserPages.desktopUserAgent(mobileUa) else mobileUa

/** 换网页前按网站选手机版还是电脑版的标识。返回有没有换。 */
internal fun JackBrowser.needsUa(w: WebView, url: String): Boolean = mobileUa.isNotEmpty() && w.settings.userAgentString != uaFor(url)

internal fun JackBrowser.applyUa(w: WebView, url: String): Boolean {
    if (!needsUa(w, url)) return false
    val ua = uaFor(url)
    // 正在加载时换标识，网页组件会自己重载当前页；调用方换完都会自己打开网址，先停掉，免得两次加载撞在一起
    w.stopLoading()
    w.settings.userAgentString = ua
    // 新式设备标识（请求里的 Sec-CH-UA-*、网页里的 userAgentData）跟着换，不然大网站照样认出是手机
    val meta = if (ua == mobileUa) mobileMeta else mobileMeta?.let { desktopMeta(it) }
    if (meta != null) runCatching { androidx.webkit.WebSettingsCompat.setUserAgentMetadata(w.settings, meta) }
    return true
}

/** 电脑版的新式设备标识：Windows 上的 Chrome，不是手机；版本号照手机上的。 */
internal fun JackBrowser.desktopMeta(m: androidx.webkit.UserAgentMetadata): androidx.webkit.UserAgentMetadata {
    val brands = m.brandVersionList.map { b ->
        if (b.brand.contains("WebView", true)) androidx.webkit.UserAgentMetadata.BrandVersion.Builder(b).setBrand("Google Chrome").build() else b
    }
    return androidx.webkit.UserAgentMetadata.Builder(m).setBrandVersionList(brands).setPlatform("Windows").setPlatformVersion("10.0.0")
        .setArchitecture("x86").setBitness(64).setModel("").setMobile(false).setWow64(false).build()
}

/** 当前网站切换电脑版 / 手机版（按网站记住），然后重新打开。默认全用电脑版时，记的是哪些网站例外用手机版。 */
internal fun JackBrowser.toggleDesktop() {
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
internal fun JackBrowser.toggleDesktopAll() {
    desktopAll = !desktopAll
    prefs().edit().putBoolean("desktopall", desktopAll).apply()
    val w = web ?: return
    if (address.isNotEmpty() && applyUa(w, address)) { clearError(); w.loadUrl(address) }
}

/** 电脑版：自适应的网站会按手机宽度排，把可视宽度改成电脑的，再缩到屏幕上。 */
internal fun JackBrowser.widenIfDesktop(w: WebView, url: String) {
    if (!isDesktopAt(url)) return
    w.evaluateJavascript(
        "(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';" +
            "(document.head||document.documentElement).appendChild(m);}m.content='width=${desktopWidth()}';})();", null,
    )
}

/** 「问 Kavi」：当前网页的标题、网址和正文（最多 6 万字）。 */
internal suspend fun JackBrowser.pageText(ctx: Context): String {
    // 先取正文（去掉导航、侧栏、评论），取不出多少再退回整页文字
    val body = readable(ctx)?.second?.takeIf { it.length >= 200 }?.take(60_000)
        ?: unwrap(eval(ctx, "(document.body ? document.body.innerText : '').slice(0, 60000)")).trim()
    val subs = if (isVideoPage(address)) subtitles(ctx).first?.let { "\n\n【视频字幕】\n" + it.take(60_000) }.orEmpty() else ""
    return "网页：$title\n链接：$address\n\n" + body + subs
}

/** 阅读模式：(标题, 正文 Markdown)；读不出返回 null。 */
internal suspend fun JackBrowser.readable(ctx: Context): Pair<String, String>? {
    val o = runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.readable(ctx)))).jsonObject }.getOrNull() ?: return null
    val md = (o["md"] as? JsonPrimitive)?.content.orEmpty().trim()
    return if (md.isEmpty()) null else (o["title"] as? JsonPrimitive)?.content.orEmpty() to md
}

/** YouTube、B 站的视频页（能取字幕的）。 */
internal fun JackBrowser.isVideoPage(url: String): Boolean {
    val site = BrowserPages.site(url)
    return (site.endsWith("youtube.com") && ("watch" in url || "/shorts/" in url)) || site == "youtu.be" ||
        (site.endsWith("bilibili.com") && "/video/" in url)
}

/** 视频字幕（YouTube、B 站、网页里带字幕轨的视频），最多等 20 秒。返回 (字幕, 哪来的) 或 (null, 为什么没有)。 */
internal suspend fun JackBrowser.subtitles(ctx: Context): Pair<String?, String> {
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
internal suspend fun JackBrowser.scanQr(url: String): String? {
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
    return withContext(Dispatchers.Default) { com.jack.ai.data.media.QrImage.decode(bytes) }
}
