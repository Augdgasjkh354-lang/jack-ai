package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * 给 PPT 每页拍张照，让模型自己看一眼排得怎么样：在看不见的 WebView 里用 App 的 PPT 查看器画出来，
 * 一页页滚过去截图，六页拼成一张（带页码），转成 data URL。
 */
object SlideShots {
    /** 查看器里一页的大小（CSS 像素），和 viewer.html 里 pptxPreview.init 的 width 对应。 */
    private const val PAGE_W = 984
    private const val SLIDE_W = 960
    private const val SLIDE_H = 540
    /** WebView 的宽（设备像素）：截出来一页 1280 宽，足够看清字。 */
    private const val VIEW_W = 1280
    const val PER_SHEET = 6

    class Result(val sheets: List<String>, val pages: Int)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(ctx: Context, file: File, maxPages: Int = 24): Result? = withContext(Dispatchers.Main) {
        val k = VIEW_W.toFloat() / PAGE_W
        val viewH = (SLIDE_H * k).toInt() + 4
        val ready = CompletableDeferred<Boolean>()
        val w = WebView(ctx.applicationContext)
        try {
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = false
            w.settings.allowContentAccess = false
            w.settings.useWideViewPort = true
            w.settings.loadWithOverviewMode = true
            w.setBackgroundColor(Color.WHITE)
            w.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().startsWith("DOCVIEW_OK")) ready.complete(true)
                    else if (m.message().startsWith("DOCVIEW_FAIL")) ready.complete(false)
                    return true
                }
            }
            w.webViewClient = docViewClient(file)
            w.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(viewH, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, VIEW_W, viewH)
            w.loadUrl("https://$DOC_HOST/viewer.html?kind=pptx&w=$PAGE_W")
            if (withTimeoutOrNull(30_000) { ready.await() } != true) return@withContext null
            delay(400)
            val tops = suspendCancellableCoroutine { cont ->
                w.evaluateJavascript("JSON.stringify([...document.querySelectorAll('.pptx-preview-slide-wrapper')].map(e => e.getBoundingClientRect().top + scrollY))") { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }.let { r ->
                runCatching { Json.parseToJsonElement((Json.parseToJsonElement(r) as JsonPrimitive).content) as JsonArray }.getOrNull()
                    ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()
            }
            if (tops.isEmpty()) return@withContext null
            val left = ((PAGE_W - SLIDE_W) / 2f * k).toInt()
            val shotW = (SLIDE_W * k).toInt(); val shotH = (SLIDE_H * k).toInt()
            val shots = mutableListOf<Bitmap>()
            for (top in tops.take(maxPages)) {
                val y = (top * k).toInt()
                w.scrollTo(0, y)
                delay(250)
                val full = Bitmap.createBitmap(VIEW_W, viewH, Bitmap.Config.ARGB_8888)
                val c = Canvas(full)
                c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
                w.draw(c)
                shots += Bitmap.createBitmap(full, left.coerceAtMost(VIEW_W - 1), 0, shotW.coerceAtMost(VIEW_W - left), shotH.coerceAtMost(viewH)).also { if (it != full) full.recycle() }
            }
            // 全是一个颜色：没画出来（WebView 在后台没有出画面）
            val blank = shots.all { b -> IntArray(9) { i -> b.getPixel(b.width * (i % 3 + 1) / 4, b.height * (i / 3 + 1) / 4) }.distinct().size == 1 }
            if (blank) { shots.forEach { it.recycle() }; return@withContext null }
            val sheets = shots.chunked(PER_SHEET).mapIndexed { si, group -> sheet(group, si * PER_SHEET) }
            shots.forEach { it.recycle() }
            Result(sheets, tops.size)
        } finally {
            w.stopLoading()
            w.destroy()
        }
    }

    /** 两列三行拼一张，每页左上角写页码。 */
    private fun sheet(group: List<Bitmap>, start: Int): String {
        val cw = 640; val ch = 360; val gap = 12; val cols = 2
        val rows = (group.size + cols - 1) / cols
        val out = Bitmap.createBitmap(cols * cw + (cols + 1) * gap, rows * ch + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(120, 120, 120))
        val tagBg = Paint().apply { color = Color.rgb(230, 40, 40) }
        val tag = Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true; isFakeBoldText = true }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        group.forEachIndexed { i, b ->
            val x = gap + (i % cols) * (cw + gap); val y = gap + (i / cols) * (ch + gap)
            c.drawBitmap(b, null, Rect(x, y, x + cw, y + ch), paint)
            val label = "${start + i + 1}"
            c.drawRect(x.toFloat(), y.toFloat(), x + tag.measureText(label) + 16f, y + 36f, tagBg)
            c.drawText(label, x + 8f, y + 28f, tag)
        }
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
        out.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
