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
import com.jack.ai.ui.JackBrowser.MediaPage

// 浏览器的下载：开始、暂停、续传、存进对话文件夹（从 JackBrowser.kt 拆出来，是 JackBrowser 的扩展函数）。

internal fun JackBrowser.progressText(name: String, got: Long, total: Long) =
    "正在下载「$name」：" + if (total > 0) "${got * 100 / total}%（${BrowserDownload.sizeText(got)} / ${BrowserDownload.sizeText(total)}）"
    else BrowserDownload.sizeText(got)

/** 浏览器的线路设置（没打开过浏览器也能读）。 */
internal fun JackBrowser.routeFor(ctx: Context): String = if (appReady) route
    else ctx.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("route", BrowserNet.AUTO) ?: BrowserNet.AUTO

/** 下载时报的浏览器标识（和网页里用的一样，网站才认）。 */
internal fun JackBrowser.userAgent(): String = mobileUa.ifBlank { "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36" }

/** 某个网址在这个标签页里的 Cookie（登录过的网站下载要带上）。 */
internal suspend fun JackBrowser.cookieFor(url: String, t: Tab? = null): String? = withContext(Dispatchers.Main) {
    runCatching { (t?.let { cookiesOf(it) } ?: CookieManager.getInstance()).getCookie(url) }.getOrNull()
}

internal fun JackBrowser.tabById(id: Int): Tab? = tabs.firstOrNull { it.id == id }

/** 在某个标签页里跑脚本（不一定是当前这个）。 */
internal suspend fun JackBrowser.evalOn(t: Tab, js: String): String = withContext(Dispatchers.Main) {
    val w = t.web ?: throw java.io.IOException("那个标签页已经关了")
    ensureLaidOut(w)
    suspendCancellableCoroutine { cont -> w.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r ?: "null") } }
}

/**
 * 把网页自己生成的文件（blob:）从网页里读出来：(类型, 内容)。要在生成它的那个标签页里读。
 * 一段段搬（每次 1 MB 的 base64），太大的不读。
 */
internal suspend fun JackBrowser.blobBytes(p: BrowserDownload.Pending, progress: (Long, Long) -> Unit = { _, _ -> }): Pair<String, ByteArray> {
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
internal suspend fun JackBrowser.storeDirect(p: BrowserDownload.Pending, target: (String, String) -> Downloads.Target, progress: (String) -> Unit = {}, control: BrowserDownload.Control? = null, privateDownload: Boolean = false, requestCookie: String? = null, requestRoute: String = route, rejectPage: Boolean = false, onBytes: (Long, Long) -> Unit = { _, _ -> }): Downloads.Saved {
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
            BrowserDownload.download(p, requestCookie, requestRoute, BrowserDownload.HARD_LIMIT, ::sink, rejectPage = rejectPage,
                progress = { got, total -> onBytes(got, total); progress(progressText(p.name, got, total)) }, control = control)
        }
    }
    return withContext(Dispatchers.IO) { finish!!().also { s -> if (appReady && !privateDownload) Downloads.log(app, s, p.url) } }
}

/**
 * 用户点了下载：存到手机的「下载/Kavi」文件夹（Android 10 以上，文件管理器里能找到）；
 * 更老的系统存到 App 自己的下载目录。在后台下，进度和结果显示在浏览器页。
 */
internal fun JackBrowser.saveToPhone(ctx: Context, p: BrowserDownload.Pending) {
    val context = ctx.applicationContext
    pending = null
    com.jack.ai.data.browser.BrowserDownloads.enqueue(context, p, { name, mime -> Downloads.toPhone(context, name, mime) })
}

/** Kavi 的确认流程照旧，确认后也进入同一个队列。 */
internal suspend fun JackBrowser.store(p: BrowserDownload.Pending, target: (String, String) -> Downloads.Target, progress: (String) -> Unit = {}): Downloads.Saved =
    withContext(Dispatchers.Main) { com.jack.ai.data.browser.BrowserDownloads.enqueue(app, p, target, progress) }.result.await()

internal suspend fun JackBrowser.saveTo(ctx: Context, dir: java.io.File?, p: BrowserDownload.Pending, progress: (String) -> Unit = {}): Downloads.Saved {
    val context = ctx.applicationContext
    if (pending == p) pending = null
    return store(p, { name, mime -> if (dir != null) Downloads.toDir(context, dir, name, mime) else Downloads.toPhone(context, name, mime) }, progress)
}

/** 当前页面上能批量下的东西：images 图片、files 附件链接（PDF、文档、压缩包……）、all 都要。返回 (共几个, 前 max 个的 (网址, 名字))。 */
internal suspend fun JackBrowser.collectDownloads(ctx: Context, kind: String, filter: String, max: Int): Pair<Int, List<Pair<String, String>>> {
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

internal suspend fun JackBrowser.mediaPage(ctx: Context): MediaPage {
    agentAt = System.currentTimeMillis()
    val t = pinned() ?: tab
    val o = runCatching { json.parseToJsonElement(unwrap(eval(ctx, BrowserScripts.media(ctx)))).jsonObject }.getOrNull()
    val els = (o?.get("media") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val pi = (o?.get("playinfo") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    return MediaPage((o?.get("title") as? JsonPrimitive)?.content ?: title, address, els, pi, t.media.toList(), t)
}
