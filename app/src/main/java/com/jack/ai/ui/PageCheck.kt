package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * 写网页时让 Kavi 自己打开看一眼（check_page）：在看不见的 WebView 里打开文件夹里的网页，
 * 收集控制台的报错和输出、没加载上的文件；可以跑一段测试脚本（点按钮、填输入框、读结果）；最后按手机屏幕截一张图。
 * 文件夹里的文件从 https://page.jack.local/ 读（页面里的相对路径照常能用），别的网址（CDN 上的库）照常联网。
 */
object PageCheck {
    const val HOST = "page.jack.local"
    /** 按手机屏幕的大小排版（CSS 像素）。 */
    private const val CSS_W = 412
    private const val CSS_H = 860
    private const val MAX_LOG = 60

    class Result(val log: List<String>, val failed: List<String>, val info: String, val scriptResult: String?, val shot: String?, val blank: Boolean)

    /** 文件夹里的相对路径对应的文件；跑到文件夹外面的不给。 */
    internal fun resolve(dir: File, path: String): File? {
        val root = dir.canonicalFile
        val f = File(root, path.removePrefix("/").ifEmpty { "index.html" }).canonicalFile
        return f.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    /** 测试脚本包一层：可以 await，返回值转成 JSON 存起来，出错记下错误。 */
    internal fun wrap(script: String): String =
        "window.__jackCheck=undefined;(async()=>{try{const v=await (async()=>{\n$script\n})();" +
            "window.__jackCheck=JSON.stringify({ok:v===undefined?null:v})}catch(e){window.__jackCheck=JSON.stringify({error:String(e&&e.stack||e)})}})();"

    @SuppressLint("SetJavaScriptEnabled")
    /** [frames] 张截图、每隔 [intervalMs] 拍一张（看动画、游戏到底动没动），并排拼成一张。 */
    suspend fun run(ctx: Context, dir: File, page: File, script: String?, waitMs: Long = 1500, frames: Int = 1, intervalMs: Long = 500): Result = withContext(Dispatchers.Main) {
        val log = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val loaded = CompletableDeferred<Unit>()
        val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
        val pxW = (CSS_W * density).toInt()
        val pxH = (CSS_H * density).toInt()
        val w = WebView(ctx.applicationContext)
        suspend fun js(code: String): String = suspendCancellableCoroutine { c -> w.evaluateJavascript(code) { if (c.isActive) c.resume(it ?: "null") } }
        fun str(raw: String) = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull().orEmpty()
        try {
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.allowFileAccess = false
            w.settings.allowContentAccess = false
            w.settings.useWideViewPort = true
            w.setBackgroundColor(Color.WHITE)
            w.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (log.size < MAX_LOG) {
                        val where = m.sourceId().substringAfterLast('/').takeIf { it.isNotBlank() }?.let { " ($it:${m.lineNumber()})" }.orEmpty()
                        log += "[${m.messageLevel().name.lowercase()}] ${m.message().take(500)}$where"
                    }
                    return true
                }
            }
            val root = dir.canonicalFile
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme != "https" || url.host != HOST) return null
                    val f = resolve(root, url.path.orEmpty())
                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, "".byteInputStream())
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
                        ?: when (f.extension.lowercase()) { "js", "mjs" -> "application/javascript"; "json" -> "application/json"; else -> "application/octet-stream" }
                    return WebResourceResponse(mime, "utf-8", f.inputStream())
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (failed.size < 20) failed += "${request.url}：${error.description}"
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                    if (failed.size < 20) failed += "${request.url}：HTTP ${resp.statusCode}"
                }
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                // 页面里点链接跳去别处：不跟
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST
            }
            w.measure(View.MeasureSpec.makeMeasureSpec(pxW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pxH, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, pxW, pxH)
            w.loadUrl("https://$HOST/" + page.relativeTo(root).path.replace(File.separatorChar, '/'))
            if (withTimeoutOrNull(20_000) { loaded.await() } == null) log += "[warning] 20 秒还没加载完，按现在的样子检查"
            delay(waitMs.coerceIn(0, 10_000))
            var scriptResult: String? = null
            if (!script.isNullOrBlank()) {
                js(wrap(script))
                scriptResult = withTimeoutOrNull(15_000) {
                    var r = ""
                    while (r.isEmpty()) { delay(100); r = str(js("window.__jackCheck||''")) }
                    r
                } ?: "{\"error\":\"测试脚本 15 秒没跑完\"}"
                delay(400)
            }
            val info = str(js("JSON.stringify({title:document.title,width:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight,text:(document.body&&document.body.innerText||'').length})"))
                .let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
                ?.let { o -> fun v(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                    "标题「${v("title")}」，页面 ${v("width")}×${v("height")}（屏幕宽 $CSS_W），正文 ${v("text")} 字" + if ((v("width").toIntOrNull() ?: 0) > CSS_W + 4) "；比屏幕宽，会出现横向滚动" else "" }
                .orEmpty()
            val count = frames.coerceIn(1, 4)
            // 一张就 540 宽；几张并排时每张窄一些，拼起来不至于太大
            val tw = if (count == 1) 540 else 300
            val th = (tw.toFloat() * pxH / pxW).toInt()
            val shots = mutableListOf<Bitmap>()
            var blank = true
            repeat(count) { k ->
                if (k > 0) delay(intervalMs.coerceIn(100, 3000))
                val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
                w.draw(Canvas(bmp))
                if (IntArray(9) { i -> bmp.getPixel(pxW * (i % 3 + 1) / 4, pxH * (i / 3 + 1) / 4) }.distinct().size > 1) blank = false
                shots += Bitmap.createScaledBitmap(bmp, tw, th, true).also { if (it != bmp) bmp.recycle() }
            }
            val sheet = if (count == 1) shots[0] else {
                val gap = 10
                Bitmap.createBitmap(count * tw + (count + 1) * gap, th + 2 * gap, Bitmap.Config.ARGB_8888).also { out ->
                    val c = Canvas(out)
                    c.drawColor(Color.rgb(120, 120, 120))
                    val tagBg = android.graphics.Paint().apply { color = Color.rgb(230, 40, 40) }
                    val tag = android.graphics.Paint().apply { color = Color.WHITE; textSize = 24f; isAntiAlias = true; isFakeBoldText = true }
                    shots.forEachIndexed { i, b ->
                        val x = gap + i * (tw + gap)
                        c.drawBitmap(b, x.toFloat(), gap.toFloat(), null)
                        c.drawRect(x.toFloat(), gap.toFloat(), x + 34f, gap + 32f, tagBg)
                        c.drawText("${i + 1}", x + 10f, gap + 25f, tag)
                    }
                    shots.forEach { it.recycle() }
                }
            }
            val bytes = ByteArrayOutputStream().also { sheet.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            sheet.recycle()
            Result(log, failed, info, scriptResult, "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), blank)
        } finally {
            w.stopLoading()
            w.destroy()
        }
    }
}
