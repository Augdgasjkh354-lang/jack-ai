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
 * 给 PPT、Word 每页拍张照，让模型自己看一眼排得怎么样：在看不见的 WebView 里用 App 的文档查看器画出来，
 * 一页页滚过去截图，六页拼成一张（带页码），转成 data URL。
 */
object PageShots {
    /** 一种文档怎么截：查看器排版宽度（CSS 像素）、页的选择器、缩略图大小、一张几列。 */
    private class Kind(val pageW: Int, val selector: String, val thumbW: Int, val thumbH: Int, val cols: Int)

    private val KINDS = mapOf(
        "pptx" to Kind(984, ".pptx-preview-slide-wrapper", 640, 360, 2),
        "docx" to Kind(842, "section.docx", 400, 566, 3),
    )

    /** WebView 的宽（设备像素）：截出来一页 1280 宽左右，足够看清字。 */
    private const val VIEW_W = 1280
    const val PER_SHEET = 6

    class Result(val sheets: List<String>, val pages: Int)

    fun supports(file: File) = file.extension.lowercase() in KINDS

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(ctx: Context, file: File, maxPages: Int = 24): Result? = withContext(Dispatchers.Main) {
        val ext = file.extension.lowercase()
        val kind = KINDS[ext] ?: return@withContext null
        val k = VIEW_W.toFloat() / kind.pageW
        val ready = CompletableDeferred<Boolean>()
        val w = WebView(ctx.applicationContext)
        fun layout(h: Int) {
            w.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, VIEW_W, h)
        }
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
            layout(900)
            w.loadUrl("https://$DOC_HOST/viewer.html?kind=$ext&w=${kind.pageW}")
            if (withTimeoutOrNull(30_000) { ready.await() } != true) return@withContext null
            delay(400)
            // 每页的位置和大小（CSS 像素）：[x, y, 宽, 高]
            val rects = suspendCancellableCoroutine { cont ->
                w.evaluateJavascript("JSON.stringify([...document.querySelectorAll('${kind.selector}')].map(e => { const r = e.getBoundingClientRect(); return [r.left + scrollX, r.top + scrollY, r.width, r.height]; }))") { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }.let { r ->
                runCatching { Json.parseToJsonElement((Json.parseToJsonElement(r) as JsonPrimitive).content) as JsonArray }.getOrNull()
                    ?.mapNotNull { e -> (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 4 } }.orEmpty()
            }
            if (rects.isEmpty()) return@withContext null
            // WebView 高度够放下最高的一页
            val viewH = (rects.maxOf { it[3] } * k).toInt() + 4
            layout(viewH)
            delay(200)
            val shots = mutableListOf<Bitmap>()
            for ((x, y, cw, ch) in rects.take(maxPages).map { it.map { v -> (v * k).toInt() } }) {
                w.scrollTo(0, y)
                delay(250)
                val full = Bitmap.createBitmap(VIEW_W, viewH, Bitmap.Config.ARGB_8888)
                val c = Canvas(full)
                c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
                w.draw(c)
                val left = x.coerceIn(0, VIEW_W - 1)
                shots += Bitmap.createBitmap(full, left, 0, cw.coerceIn(1, VIEW_W - left), ch.coerceIn(1, viewH)).also { if (it != full) full.recycle() }
            }
            // 全是一个颜色：没画出来（WebView 在后台没有出画面）
            val blank = shots.all { b -> IntArray(9) { i -> b.getPixel(b.width * (i % 3 + 1) / 4, b.height * (i / 3 + 1) / 4) }.distinct().size == 1 }
            if (blank) { shots.forEach { it.recycle() }; return@withContext null }
            val sheets = shots.chunked(PER_SHEET).mapIndexed { si, group -> sheet(group, si * PER_SHEET, kind) }
            shots.forEach { it.recycle() }
            Result(sheets, rects.size)
        } finally {
            w.stopLoading()
            w.destroy()
        }
    }

    /** 拼一张：PPT 两列三行，Word 三列两行，每页左上角写页码。 */
    private fun sheet(group: List<Bitmap>, start: Int, kind: Kind): String {
        val cw = kind.thumbW; val ch = kind.thumbH; val gap = 12; val cols = kind.cols
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
