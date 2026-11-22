package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jack.ai.data.design.DesignTemplates
import com.jack.ai.data.media.FrameEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * 代码动画出片（思路来自 lemomo-ai/lemo-opuscar：不用视频生成，用代码一帧帧画出来；做法是自己写的）：
 * 在看不见的 WebView 里打开 Kavi 写的动画页面，用 anim.js 把页面的时钟拨到每一帧的时间，截图，交给 [FrameEncoder] 编成 MP4。
 * 也能只截几个时间点拼成一张预览图，先看画面再出片。页面的请求和字体跟设计出图一样（[DesignRender.serve]）。
 */
object AnimRender {
    class Result(
        val frames: Int,
        /** 第一帧截出来一片空白：多半是 App 在后台、WebView 没出画面。 */
        val blank: Boolean,
        /** 从头到尾画面一点没变：动画没跟着时间走。 */
        val still: Boolean,
        /** 页面脚本报的错（第一条）。 */
        val error: String,
        val problems: List<String>,
        /** 预览图（小图，JPEG data URL），给模型看。 */
        val preview: String?,
    )

    /**
     * 出片（[video] 不为空）或出预览（[sheet] 不为空，在 [times] 这几个时间点各截一帧拼起来）。
     * [onFrame] 报进度：第几帧、一共几帧。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(
        ctx: Context, dir: File, html: String, w: Int, h: Int, data: JsonObject,
        times: List<Double>, fps: Int, video: File?, sheet: File?, onFrame: (Int, Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.Main) {
        val (fontCss, fontsMissing) = DesignRender.fontCss(ctx, html)
        val shim = ctx.assets.open("design/anim.js").use { it.readBytes().toString(Charsets.UTF_8) }
        val page = DesignTemplates.prepare(html, w, h, shim + "\n" + DesignTemplates.fillJs(ctx), fontCss).toByteArray()
        val loaded = CompletableDeferred<Unit>()
        val view = WebView(ctx.applicationContext)
        suspend fun js(code: String): String = suspendCancellableCoroutine { c -> view.evaluateJavascript(code) { if (c.isActive) c.resume(it ?: "null") } }
        fun str(raw: String) = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull().orEmpty()
        var encoder: FrameEncoder? = null
        var ok = false
        try {
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = true
            view.settings.textZoom = 100
            view.setBackgroundColor(Color.WHITE)
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            val root = dir.canonicalFile
            view.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = view.dropAfterRendererGone()
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse = DesignRender.serve(ctx, root, page, request)
                override fun onPageFinished(v: WebView, url: String) { loaded.complete(Unit) }
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            view.loadUrl("https://${DesignRender.HOST}/")
            withTimeoutOrNull(20_000) { loaded.await() }
            js("kaviFill($data)")
            val report = withTimeoutOrNull(20_000) {
                var r = ""
                while (r.isEmpty()) { delay(80); r = str(js("window.__kaviDone||''")) }
                r
            }
            val o = runCatching { Json.parseToJsonElement(report.orEmpty()) as JsonObject }.getOrNull()
            val problems = mutableListOf<String>()
            if (o == null) problems += "页面 20 秒没准备好，按现在的样子出"
            if (fontsMissing.isNotEmpty()) problems += "字体没下载下来（可能没联网）：${fontsMissing.joinToString("、")}，用系统黑体代替了"
            // 「检查」里文字装不下这类，动画里的元素常常是故意放在画外等着飞进来的，只留和画外无关的
            (o?.get("problems") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                .filterNot { "画布" in it || "超出" in it }.forEach { problems += it }

            if (video != null) encoder = withContext(Dispatchers.IO) { FrameEncoder(video, w, h, fps) }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val px = IntArray(w * h)
            val shots = mutableListOf<Bitmap>()
            var settleWorks = true
            var reqId = 0L
            var blank = false
            var firstSig: Long? = null
            var moved = false
            var error = ""
            /** 等这一帧的画面真的出来：WebView 的「画面就绪」回调，不灵（看不见的 WebView 有的手机不回）就等一小会儿。 */
            suspend fun settle() {
                val done = if (!settleWorks) null else withTimeoutOrNull(1500) {
                    suspendCancellableCoroutine<Unit> { c ->
                        view.postVisualStateCallback(++reqId, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) { if (c.isActive) c.resume(Unit) }
                        })
                    }
                }
                if (done == null) { settleWorks = false; delay(90) }
            }
            times.forEachIndexed { n, t ->
                coroutineContext.ensureActive()
                val err = str(js("__kaviSeek(${"%.4f".format(java.util.Locale.ROOT, t)})"))
                if (error.isEmpty() && err.isNotEmpty()) error = err
                settle()
                bmp.eraseColor(Color.WHITE)
                view.draw(canvas)
                // 指纹：取 64 个点，看画面有没有在变
                var sig = 0L
                for (k in 0 until 64) sig = sig * 31 + bmp.getPixel(w * (k % 8 * 2 + 1) / 16, h * (k / 8 * 2 + 1) / 16)
                if (n == 0) {
                    firstSig = sig
                    blank = IntArray(25) { i -> bmp.getPixel(w * (i % 5 + 1) / 6, h * (i / 5 + 1) / 6) }.distinct().size == 1
                } else if (sig != firstSig) moved = true
                if (encoder != null) {
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val e = encoder!!
                    withContext(Dispatchers.Default) { e.add(px) }
                } else shots += Bitmap.createScaledBitmap(bmp, (w * 360 / maxOf(w, h)).coerceAtLeast(1), (h * 360 / maxOf(w, h)).coerceAtLeast(1), true)
                onFrame(n + 1, times.size)
            }
            if (error.isEmpty()) error = str(js("window.__kaviErr||''"))
            encoder?.let { e -> withContext(Dispatchers.IO) { e.finish() } }
            bmp.recycle()
            val preview = if (sheet != null) withContext(Dispatchers.IO) { contactSheet(shots, times, sheet) } else null
            shots.forEach { it.recycle() }
            ok = true
            Result(times.size, blank, times.size > 1 && !moved, error, problems, preview)
        } finally {
            encoder?.close()
            if (!ok) video?.delete()
            view.stopLoading()
            view.destroy()
        }
    }

    /** 几帧拼成一张：一行三张，每张下面写时间。存成 PNG，返回给模型看的小图。 */
    private fun contactSheet(shots: List<Bitmap>, times: List<Double>, out: File): String? {
        if (shots.isEmpty()) return null
        val cw = shots[0].width; val ch = shots[0].height
        val cols = minOf(3, shots.size); val rows = (shots.size + cols - 1) / cols
        val gap = 12; val label = 34
        val sheet = Bitmap.createBitmap(cols * cw + (cols + 1) * gap, rows * (ch + label) + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(32, 32, 36))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }
        shots.forEachIndexed { i, b ->
            val x = gap + (i % cols) * (cw + gap); val y = gap + (i / cols) * (ch + label + gap)
            c.drawBitmap(b, x.toFloat(), y.toFloat(), null)
            c.drawText("%.1f 秒".format(times[i]), x.toFloat(), (y + ch + 26).toFloat(), paint)
        }
        out.parentFile?.mkdirs()
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val bytes = ByteArrayOutputStream().also { sheet.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        sheet.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
