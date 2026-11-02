package com.jack.ai.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import com.jack.ai.data.Downloads
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/** 按可见宽度逐屏拍，预滚动先触发懒加载，再拼前二十屏。 */
object BrowserLongShot {
    suspend fun capture(ctx: Context, w: WebView): Pair<Downloads.Saved, Boolean> = withContext(Dispatchers.Main) {
        val x = w.scrollX; val y = w.scrollY
        val width = w.width; val height = w.height
        if (width <= 0 || height <= 0) error("网页还没显示出来")
        var image: Bitmap? = null
        try {
            suspend fun hideBars() = script(w, """window.__kaviShot=window.__kaviShot||[];document.querySelectorAll('*').forEach(function(e){var p=getComputedStyle(e).position;if((p==='fixed'||p==='sticky')&&!window.__kaviShot.some(function(v){return v[0]===e;})){window.__kaviShot.push([e,e.style.getPropertyValue('visibility'),e.style.getPropertyPriority('visibility')]);e.style.setProperty('visibility','hidden','important');}});""")
            hideBars()
            fun pageHeight() = (w.contentHeight * w.scale).toInt().coerceAtLeast(height)
            for (i in 0 until 20) {
                currentCoroutineContext().ensureActive()
                w.scrollTo(x, i * height)
                delay(300)
                if ((i + 1) * height >= pageHeight()) break
            }
            val fullHeight = pageHeight()
            val outHeight = minOf(fullHeight, height * 20)
            val bitmap = Bitmap.createBitmap(width, outHeight, Bitmap.Config.RGB_565)
            image = bitmap
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            var top = 0
            while (top < outHeight) {
                w.scrollTo(x, top)
                delay(300)
                currentCoroutineContext().ensureActive()
                hideBars()
                val actual = w.scrollY
                canvas.save()
                canvas.clipRect(0, top, width, minOf(actual + height, outHeight))
                // draw 的原点是网页内容坐标；平移回可见区，再落到长图对应位置。
                canvas.translate(-w.scrollX.toFloat(), 0f)
                w.draw(canvas)
                canvas.restore()
                val next = actual + height
                if (next <= top) break
                top = next
            }
            val saved = withContext(Dispatchers.IO) { save(ctx, bitmap) }
            saved to (fullHeight > outHeight)
        } finally {
            withContext(NonCancellable) {
                script(w, """(window.__kaviShot||[]).forEach(function(p){if(p[1])p[0].style.setProperty('visibility',p[1],p[2]);else p[0].style.removeProperty('visibility');});delete window.__kaviShot;""")
                w.scrollTo(x, y)
                image?.recycle()
            }
        }
    }
    private suspend fun script(w: WebView, js: String) = suspendCancellableCoroutine<Unit> { c ->
        w.evaluateJavascript(js) { if (c.isActive) c.resume(Unit) }
    }
    private fun save(ctx: Context, image: Bitmap): Downloads.Saved {
        val name = "网页长截图_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kavi")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Kavi").apply { mkdirs() }
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("没能在相册建图片")
        try {
            resolver.openOutputStream(uri)?.use { if (!image.compress(Bitmap.CompressFormat.JPEG, 85, it)) error("图片没存成") } ?: error("打不开相册图片")
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return Downloads.Saved(name, uri, "image/jpeg", "相册 Pictures/Kavi")
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}
