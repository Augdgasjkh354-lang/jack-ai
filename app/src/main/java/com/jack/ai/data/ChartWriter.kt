package com.jack.ai.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 把数据画成 PNG 图表：柱状图、折线图、饼图。 */
object ChartWriter {
    data class Series(val name: String, val values: List<Double>)

    private const val W = 1200
    private const val H = 780
    private val PALETTE = listOf(
        0xFFC4673F, 0xFF2F6FB0, 0xFF3E7D5E, 0xFFD9A441, 0xFF7B5BA6, 0xFFB5506A, 0xFF4BA3A8, 0xFF8A8178,
    ).map { it.toInt() }
    private val INK = Color.rgb(0x2B, 0x27, 0x22)
    private val GRAY = Color.rgb(0x8A, 0x81, 0x78)
    private val GRID = Color.rgb(0xEC, 0xE7, 0xE0)

    private fun text(size: Float, color: Int = INK, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun fmt(v: Double): String = when {
        abs(v) >= 1e8 -> trim(v / 1e8) + "亿"
        abs(v) >= 1e4 -> trim(v / 1e4) + "万"
        else -> trim(v)
    }

    private fun trim(v: Double): String =
        if (v == floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun ellipsize(s: String, p: Paint, width: Float): String {
        if (p.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** 让坐标轴的刻度是整齐的数。 */
    private fun niceStep(range: Double, ticks: Int = 5): Double {
        if (range <= 0) return 1.0
        val raw = range / ticks
        val mag = 10.0.pow(floor(log10(raw)))
        val n = raw / mag
        return mag * when {
            n <= 1 -> 1.0
            n <= 2 -> 2.0
            n <= 2.5 -> 2.5
            n <= 5 -> 5.0
            else -> 10.0
        }
    }

    fun render(type: String, title: String, unit: String, labels: List<String>, series: List<Series>, out: OutputStream) {
        require(series.isNotEmpty() && series.any { it.values.isNotEmpty() }) { "没有数据" }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.drawText(ellipsize(title, text(40f, bold = true), W - 96f), 48f, 76f, text(40f, bold = true))
        if (unit.isNotBlank() && type != "pie") c.drawText("单位：$unit", 48f, 118f, text(24f, GRAY))
        if (type == "pie") pie(c, labels, series.first()) else axes(c, type, labels, series)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    private fun axes(c: Canvas, type: String, labels: List<String>, series: List<Series>) {
        val n = max(labels.size, series.maxOf { it.values.size })
        val all = series.flatMap { it.values }
        var lo = min(0.0, all.minOrNull() ?: 0.0)
        var hi = max(0.0, all.maxOrNull() ?: 1.0)
        if (hi == lo) hi = lo + 1
        val step = niceStep(hi - lo)
        lo = floor(lo / step) * step
        hi = ceil(hi / step) * step

        // 图例
        var legendY = 150f
        if (series.size > 1) {
            var x = 48f
            series.forEachIndexed { i, s ->
                val p = text(24f)
                val w = p.measureText(s.name) + 56f
                if (x + w > W - 48) { x = 48f; legendY += 36f }
                c.drawRoundRect(RectF(x, legendY - 18, x + 26, legendY + 2), 4f, 4f, Paint().apply { color = PALETTE[i % PALETTE.size] })
                c.drawText(s.name, x + 34, legendY, p)
                x += w
            }
            legendY += 24f
        }

        val left = 120f
        val right = W - 48f
        val top = max(170f, legendY + 20f)
        val bottom = H - 90f
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        val gridP = Paint().apply { color = GRID; strokeWidth = 2f }
        val tickP = text(22f, GRAY, align = Paint.Align.RIGHT)
        var v = lo
        while (v <= hi + step / 2) {
            c.drawLine(left, y(v), right, y(v), gridP)
            c.drawText(fmt(v), left - 14, y(v) + 8, tickP)
            v += step
        }
        c.drawLine(left, y(0.0), right, y(0.0), Paint().apply { color = GRAY; strokeWidth = 2f })

        val slot = (right - left) / n
        val labelP = text(22f, GRAY, align = Paint.Align.CENTER)
        val every = max(1, ceil(n * 70.0 / (right - left)).toInt())
        labels.forEachIndexed { i, l ->
            if (i % every == 0) c.drawText(ellipsize(l, labelP, slot * every - 8), left + slot * (i + 0.5f), bottom + 36, labelP)
        }

        if (type == "line") {
            series.forEachIndexed { si, s ->
                val color = PALETTE[si % PALETTE.size]
                val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 5f; style = Paint.Style.STROKE }
                val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                val pts = s.values.take(n).mapIndexed { i, value -> (left + slot * (i + 0.5f)) to y(value) }
                pts.zipWithNext().forEach { (a, b) -> c.drawLine(a.first, a.second, b.first, b.second, lineP) }
                pts.forEach { (x, yy) ->
                    c.drawCircle(x, yy, 8f, dotP)
                    c.drawCircle(x, yy, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
                }
                if (series.size == 1 && n <= 16) {
                    val vp = text(20f, INK, align = Paint.Align.CENTER)
                    s.values.take(n).forEachIndexed { i, value -> c.drawText(fmt(value), pts[i].first, pts[i].second - 18, vp) }
                }
            }
        } else {
            val group = slot * 0.72f
            val bw = group / series.size
            series.forEachIndexed { si, s ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PALETTE[si % PALETTE.size] }
                s.values.take(n).forEachIndexed { i, value ->
                    val x = left + slot * i + (slot - group) / 2 + bw * si
                    val y0 = y(0.0)
                    val y1 = y(value)
                    c.drawRoundRect(RectF(x + 2, min(y0, y1), x + bw - 2, max(y0, y1)), 6f, 6f, p)
                    if (n * series.size <= 24) {
                        c.drawText(fmt(value), x + bw / 2, min(y0, y1) - 10, text(20f, INK, align = Paint.Align.CENTER))
                    }
                }
            }
        }
    }

    private fun pie(c: Canvas, labels: List<String>, s: Series) {
        val values = s.values.map { max(0.0, it) }
        val total = values.sum().takeIf { it > 0 } ?: 1.0
        val cx = 380f
        val cy = 450f
        val r = 260f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        var start = -90f
        values.forEachIndexed { i, v ->
            val sweep = (v / total * 360).toFloat()
            c.drawArc(oval, start, sweep, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PALETTE[i % PALETTE.size] })
            c.drawArc(oval, start, sweep, true, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
            })
            start += sweep
        }
        c.drawCircle(cx, cy, r * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawText(fmt(values.sum()), cx, cy + 14, text(38f, INK, bold = true, align = Paint.Align.CENTER))

        val rows = values.size.coerceAtMost(12)
        val lineH = min(52f, 520f / rows)
        var y = cy - lineH * rows / 2 + lineH / 2
        values.take(rows).forEachIndexed { i, v ->
            val x = 720f
            c.drawRoundRect(RectF(x, y - 20, x + 28, y + 6), 6f, 6f, Paint().apply { color = PALETTE[i % PALETTE.size] })
            val name = labels.getOrElse(i) { "第${i + 1}项" }
            val pct = String.format(java.util.Locale.US, "%.1f%%", v / total * 100)
            c.drawText(ellipsize(name, text(26f), 280f), x + 42, y, text(26f))
            c.drawText(pct, W - 48f, y, text(26f, GRAY, align = Paint.Align.RIGHT))
            y += lineH
        }
    }
}
