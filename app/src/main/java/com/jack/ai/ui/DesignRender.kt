package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.util.Base64
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jack.ai.data.design.DesignFonts
import com.jack.ai.data.design.DesignTemplates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * 设计出图：在看不见的 WebView 里打开模板（或 Kavi 写的页面），用 fill.js 填好文字、列表、图片、配色、图标，
 * 检查有没有装不下的，再按画布大小一像素对一像素截成 PNG（要的话再出一份 PDF）。
 * 页面从 https://design.jack.local/ 打开；/files/ 下面是对话文件夹里的文件（用户的照片），/fonts/ 是字体；页面自己不联网。
 */
object DesignRender {
    const val HOST = "design.jack.local"
    /** 长图最高多少（像素），再高手机内存吃不消。 */
    const val MAX_H = 12000

    class Result(
        val width: Int, val height: Int, val problems: List<String>, val unknown: List<String>,
        /** 填好的页面（图标图形去掉了），存成底稿，以后改设计就改它。 */
        val source: String,
        /** 截出来一片空白：多半是 App 在后台、WebView 没出画面。 */
        val blank: Boolean,
        /** 给模型看一眼的小图（JPEG data URL）。 */
        val preview: String?,
        /** 审美检查的建议（对比度、字号、颜色太多、贴边、AI 味……），见 fill.js 的 lint。 */
        val advice: List<String> = emptyList(),
        /** 缩到小红书信息流那么大的缩略图（宽 300），看封面在小图里还认不认得出；长图没有。 */
        val thumb: String? = null,
    )

    /** 对话文件夹里的图片在页面里的地址。 */
    fun fileUrl(name: String) = "/files/" + name.split('/').joinToString("/") { android.net.Uri.encode(it) }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(ctx: Context, dir: File, html: String, w: Int, h: Int, data: JsonObject, png: File, pdf: File?, previewWidth: Int = 720): Result = withContext(Dispatchers.Main) {
        val (fontCss, fontsMissing) = fontCss(ctx, html)
        val page = DesignTemplates.prepare(html, w, h, DesignTemplates.fillJs(ctx), fontCss).toByteArray()
        val loaded = CompletableDeferred<Unit>()
        val view = WebView(ctx.applicationContext)
        suspend fun js(code: String): String = suspendCancellableCoroutine { c -> view.evaluateJavascript(code) { if (c.isActive) c.resume(it ?: "null") } }
        fun str(raw: String) = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull().orEmpty()
        fun layout(height: Int) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, height)
        }
        try {
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.blockNetworkLoads = false
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = true
            view.settings.textZoom = 100
            view.setBackgroundColor(Color.WHITE)
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            val root = dir.canonicalFile
            view.webViewClient = object : WebViewClient() {
                // 网页进程没了（崩溃或内存紧被收回，和 Kavi 浏览器共用一个进程）：丢掉这个网页窗口，不接住的话整个 App 会被系统关掉
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = view.dropAfterRendererGone()
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse =
                    serve(ctx, root, page, request)
                override fun onPageFinished(v: WebView, url: String) { loaded.complete(Unit) }
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true
            }
            layout(if (h > 0) h else 1920)
            view.loadUrl("https://$HOST/")
            withTimeoutOrNull(20_000) { loaded.await() }
            js("kaviFill(${data})")
            val report = withTimeoutOrNull(20_000) {
                var r = ""
                while (r.isEmpty()) { delay(80); r = str(js("window.__kaviDone||''")) }
                r
            }
            val o = runCatching { Json.parseToJsonElement(report.orEmpty()) as JsonObject }.getOrNull()
            fun list(k: String) = (o?.get(k) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            val problems = list("problems").toMutableList()
            if (o == null) problems += "页面 20 秒没准备好，按现在的样子出图"
            if (fontsMissing.isNotEmpty()) problems += "字体没下载下来（可能没联网）：${fontsMissing.joinToString("、")}，这次用系统黑体代替了；联网后重新出图，或者换成得意黑、系统黑体"
            val height = if (h > 0) h else ((o?.get("height") as? JsonPrimitive)?.content?.toIntOrNull() ?: 1920).coerceIn(200, MAX_H).also {
                layout(it); delay(300)
            }
            delay(150)
            val bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val blank = IntArray(25) { i -> bmp.getPixel(w * (i % 5 + 1) / 6, height * (i / 5 + 1) / 6) }.distinct().size == 1
            val source = str(js("kaviSource()"))
            withContext(Dispatchers.IO) {
                png.parentFile?.mkdirs()
                png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (pdf != null) writePdf(bmp, pdf)
            }
            val preview = previewOf(bmp, previewWidth)
            val thumb = if (h > 0) previewOf(bmp, 300) else null
            bmp.recycle()
            Result(w, height, problems, list("unknown"), source, blank, preview, list("advice"), thumb)
        } finally {
            view.stopLoading()
            view.destroy()
        }
    }

    /**
     * 设计页面的请求：/ 是页面本身，/files/ 是对话文件夹里的文件（用户的照片），/fonts/ 是字体（见 DesignFonts）。
     * 别的网址一律不给：模板不联网（字体由 App 代下，页面自己连不出去）。出图和编辑器共用。
     */
    fun serve(ctx: Context, root: File, page: ByteArray, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        val path = url.path.orEmpty()
        fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, "".byteInputStream())
        if (url.scheme != "https" || url.host != HOST) return notFound()
        if (path == "/" || path.isEmpty()) return WebResourceResponse("text/html", "utf-8", page.inputStream())
        if (path.startsWith("/files/")) {
            val f = PageCheck.resolve(root, android.net.Uri.decode(path.removePrefix("/files/"))) ?: return notFound()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
            return WebResourceResponse(mime, null, f.inputStream())
        }
        if (path.startsWith("/fonts/")) {
            val stream = DesignFonts.open(ctx, path) ?: return notFound()
            return WebResourceResponse("font/woff2", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000"), stream)
        }
        return notFound()
    }

    /** 页面用到的字体的 @font-face；下不下来的字体名放在第二个里。要联网，在 IO 线程调。 */
    suspend fun fontCss(ctx: Context, html: String): Pair<String, List<String>> = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        // 下载字体是阻塞的网络请求：可打断，点停止时不用等它下完
        val fonts = DesignFonts.used(DesignFonts.all(ctx), html)
        if (fonts.isEmpty()) "" to emptyList() else DesignFonts.css(ctx, fonts)
    }

    /** PDF：一页，按 150dpi 算纸张大小，整张图铺满（打印、发给印刷店用）。 */
    private fun writePdf(bmp: Bitmap, out: File) {
        val k = 72f / 150f
        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder((bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), 1).create()
            val p = doc.startPage(info)
            p.canvas.drawBitmap(bmp, Matrix().apply { setScale(k, k) }, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(p)
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    /** 给模型看的小图：最宽 720（或给定的宽）、最高 2400（长图太长就只看上面一段），JPEG。 */
    private fun previewOf(bmp: Bitmap, maxW: Int = 720): String {
        val k = minOf(1f, maxW.toFloat() / bmp.width)
        val pw = (bmp.width * k).toInt().coerceAtLeast(1)
        val ph = (bmp.height * k).toInt().coerceIn(1, 2400)
        val small = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(bmp, Matrix().apply { setScale(k, k) }, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        small.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
