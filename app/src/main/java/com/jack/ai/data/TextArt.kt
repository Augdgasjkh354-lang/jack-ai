package com.jack.ai.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 视频上的花字和字幕：先画成透明底的图，再当叠加层压到画面上。frameW 是成片的宽（短视频 1080）。
 * - title 大标题：白字粗黑边
 * - highlight 重点：黄字粗黑边
 * - caption 说明：白字半透明黑底圆角条
 * - label 标签：白字红底圆角块
 * - price 价格：橙红大字白边
 * - subtitle 字幕：白字黑边（抖音那种），不加底
 */
object TextArt {
    private class Look(val sizeOf: (Int) -> Float, val fill: Int, val stroke: Int?, val strokeK: Float, val box: Int?, val maxW: Float)

    private val LOOKS = mapOf(
        "title" to Look({ it * 0.085f }, Color.WHITE, Color.BLACK, 0.16f, null, 0.86f),
        "highlight" to Look({ it * 0.075f }, Color.rgb(255, 225, 77), Color.BLACK, 0.16f, null, 0.86f),
        "caption" to Look({ it * 0.048f }, Color.WHITE, null, 0f, Color.argb(150, 0, 0, 0), 0.8f),
        "label" to Look({ it * 0.052f }, Color.WHITE, null, 0f, Color.rgb(255, 59, 92), 0.7f),
        "price" to Look({ it * 0.11f }, Color.rgb(255, 77, 46), Color.WHITE, 0.09f, null, 0.86f),
        "subtitle" to Look({ it * 0.055f }, Color.WHITE, Color.BLACK, 0.14f, null, 0.88f),
    )

    fun render(text: String, style: String, frameW: Int): Bitmap {
        val look = LOOKS[style] ?: LOOKS.getValue("title")
        val size = look.sizeOf(frameW).coerceAtLeast(12f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isFakeBoldText = style != "caption" }
        val maxW = (frameW * look.maxW).toInt()
        val natural = text.lines().maxOf { paint.measureText(it) }.toInt() + 1
        val width = minOf(maxW, natural).coerceAtLeast(1)
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.08f).setIncludePad(false).build()
        val fillLayout = layout(paint)
        val strokeW = size * look.strokeK
        val pad = if (look.box != null) (size * 0.45f) else strokeW + 2
        val padY = if (look.box != null) size * 0.28f else strokeW + 2
        val w = (width + pad * 2).toInt(); val h = (fillLayout.height + padY * 2).toInt()
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        look.box?.let { box ->
            val r = size * 0.32f
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = box })
        }
        c.translate(pad, padY)
        look.stroke?.let { sc ->
            val sp = TextPaint(paint).apply { this.style = Paint.Style.STROKE; strokeWidth = strokeW; strokeJoin = Paint.Join.ROUND; color = sc }
            layout(sp).draw(c)
        }
        paint.color = look.fill
        fillLayout.draw(c)
        return bmp
    }

    /** 叠加层的竖直位置（-1 底边到 1 顶边，是图中心的位置）：上方、正中、下方（字幕上面一点）。 */
    fun anchorY(position: String): Float = when (position) {
        "top" -> 0.62f
        "center" -> 0f
        else -> -0.5f
    }
}
