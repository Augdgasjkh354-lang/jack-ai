package com.jack.ai.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** 浏览器菜单「添加到主屏幕」：把网站钉成桌面图标，一点就在 Kavi 浏览器里打开。 */
object WebShortcut {
    /** 图标边长（自适应图标 108dp 按两倍画）。 */
    private const val SIZE = 216

    fun supported(ctx: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

    /** 请桌面加图标。返回 false 是桌面不让加。 */
    fun pin(ctx: Context, url: String, name: String, favicon: Bitmap?): Boolean {
        val label = name.trim().ifBlank { host(url) }.take(24)
        // 走对外开放的 LinkActivity（只收 http/https），它再转给 Kavi 浏览器
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(ctx, LinkActivity::class.java)
        val info = ShortcutInfoCompat.Builder(ctx, "web:" + url.hashCode().toUInt().toString(16))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(draw(favicon, letter(name, url), url)))
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(ctx, info, null) }.getOrDefault(false)
    }

    /** 网站图标够清楚就放在白底中间；太小或没有就画一个带首字的彩色块。 */
    private fun draw(favicon: Bitmap?, letter: String, url: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (favicon != null && favicon.width >= 32 && favicon.height >= 32) {
            c.drawColor(Color.WHITE)
            val half = 48f
            c.drawBitmap(favicon, Rect(0, 0, favicon.width, favicon.height), RectF(SIZE / 2 - half, SIZE / 2 - half, SIZE / 2 + half, SIZE / 2 + half), p)
        } else {
            c.drawColor(Color.HSVToColor(floatArrayOf((host(url).hashCode().mod(360)).toFloat(), 0.45f, 0.78f)))
            p.color = Color.WHITE
            p.textSize = 84f
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            val fm = p.fontMetrics
            c.drawText(letter, SIZE / 2f, SIZE / 2f - (fm.ascent + fm.descent) / 2, p)
        }
        return bmp
    }

    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.").removePrefix("m.")

    /** 彩色块上的字：名字的第一个字（英文大写），名字里没有字母、汉字就用网址的。 */
    fun letter(name: String, url: String): String {
        val from = name.firstOrNull { it.isLetterOrDigit() } ?: host(url).firstOrNull { it.isLetterOrDigit() } ?: '?'
        return from.uppercaseChar().toString()
    }
}
