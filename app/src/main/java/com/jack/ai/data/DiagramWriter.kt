package com.jack.ai.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.io.OutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 把 Diagram 排好的树状图、流程图画成 PNG。 */
object DiagramWriter {
    private val PALETTE = listOf(
        0xFFC4673F, 0xFF2F6FB0, 0xFF3E7D5E, 0xFFD9A441, 0xFF7B5BA6, 0xFFB5506A, 0xFF4BA3A8, 0xFF8A8178,
    ).map { it.toInt() }
    private val INK = Color.rgb(0x2B, 0x27, 0x22)
    private val GRAY = Color.rgb(0x8A, 0x81, 0x78)
    private const val MARGIN = 56f
    private const val MAX_SIDE = 3200f

    private fun textPaint(size: Float = Diagram.FONT, color: Int = INK, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    val measure = Diagram.Measure { textPaint().measureText(it) }

    private fun tint(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun render(layout: Diagram.Layout, title: String, mindmap: Boolean, out: OutputStream) {
        val titleH = if (title.isNotBlank()) 90f else 0f
        val contentW = max(layout.width + MARGIN * 2, 720f)
        val contentH = layout.height + MARGIN * 2 + titleH
        val scale = min(1f, MAX_SIDE / max(contentW, contentH))
        val bmp = Bitmap.createBitmap((contentW * scale).toInt().coerceAtLeast(1), (contentH * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        if (title.isNotBlank()) {
            c.drawText(title, contentW / 2, MARGIN + 34f, textPaint(38f, bold = true))
        }
        c.translate((contentW - layout.width) / 2, MARGIN + titleH)
        layout.links.forEach { drawLink(c, it, mindmap) }
        layout.boxes.forEach { drawBox(c, it, mindmap) }
        layout.links.filter { it.label.isNotBlank() }.forEach { drawLabel(c, it) }
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    private fun drawLink(c: Canvas, l: Diagram.Link, mindmap: Boolean) {
        val color = if (mindmap) PALETTE[l.group % PALETTE.size] else GRAY
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = if (mindmap) 4f else 3f; this.color = color
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        val pts = l.points
        path.moveTo(pts[0].first, pts[0].second)
        if (l.curved && pts.size == 2) {
            val (x1, y1) = pts[0]
            val (x2, y2) = pts[1]
            val mx = (x1 + x2) / 2
            path.cubicTo(mx, y1, mx, y2, x2, y2)
        } else {
            pts.drop(1).forEach { path.lineTo(it.first, it.second) }
        }
        c.drawPath(path, p)
        if (l.arrow && pts.size >= 2) {
            val (x1, y1) = pts[pts.size - 2]
            val (x2, y2) = pts.last()
            val a = atan2(y2 - y1, x2 - x1)
            val len = 16f
            val head = Path().apply {
                moveTo(x2, y2)
                lineTo(x2 - len * cos(a - 0.45f), y2 - len * sin(a - 0.45f))
                lineTo(x2 - len * cos(a + 0.45f), y2 - len * sin(a + 0.45f))
                close()
            }
            c.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
    }

    /** 标签放在连线长度一半的位置。 */
    private fun drawLabel(c: Canvas, l: Diagram.Link) {
        val segs = l.points.zipWithNext()
        val total = segs.sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second).toDouble() }.toFloat()
        var left = total / 2
        var x = l.points[0].first
        var y = l.points[0].second
        for ((a, b) in segs) {
            val len = hypot(b.first - a.first, b.second - a.second)
            if (left <= len && len > 0) {
                x = a.first + (b.first - a.first) * left / len
                y = a.second + (b.second - a.second) * left / len
                break
            }
            left -= len
        }
        val tp = textPaint(22f, GRAY)
        val w = tp.measureText(l.label) + 16f
        c.drawRoundRect(RectF(x - w / 2, y - 18f, x + w / 2, y + 14f), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawText(l.label, x, y + 6f, tp)
    }

    private fun drawBox(c: Canvas, b: Diagram.Box, mindmap: Boolean) {
        val rect = RectF(b.x, b.y, b.x + b.w, b.y + b.h)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        var textColor = INK
        var bold = false
        if (mindmap) {
            val color = PALETTE[b.group % PALETTE.size]
            when (b.depth) {
                0 -> { fill.color = INK; stroke.color = INK; textColor = Color.WHITE; bold = true }
                1 -> { fill.color = color; stroke.color = color; textColor = Color.WHITE; bold = true }
                else -> { fill.color = tint(color, 0x1F); stroke.color = tint(color, 0x99) }
            }
            c.drawRoundRect(rect, 16f, 16f, fill)
            c.drawRoundRect(rect, 16f, 16f, stroke)
        } else {
            val accent = PALETTE[0]
            when (b.shape) {
                "round" -> {
                    fill.color = accent; stroke.color = accent; textColor = Color.WHITE; bold = true
                    c.drawRoundRect(rect, b.h / 2, b.h / 2, fill)
                }
                "diamond" -> {
                    fill.color = tint(PALETTE[3], 0x33); stroke.color = PALETTE[3]
                    val path = Path().apply {
                        moveTo(b.cx, b.y); lineTo(b.x + b.w, b.cy); lineTo(b.cx, b.y + b.h); lineTo(b.x, b.cy); close()
                    }
                    c.drawPath(path, fill); c.drawPath(path, stroke)
                }
                else -> {
                    fill.color = Color.WHITE; stroke.color = INK
                    c.drawRoundRect(rect, 10f, 10f, fill); c.drawRoundRect(rect, 10f, 10f, stroke)
                }
            }
        }
        val tp = textPaint(if (mindmap && b.depth == 0) 30f else Diagram.FONT, textColor, bold)
        val total = b.lines.size * Diagram.LINE_H
        var y = b.cy - total / 2 + Diagram.LINE_H * 0.72f
        b.lines.forEach { line ->
            c.drawText(line, b.cx, y, tp)
            y += Diagram.LINE_H
        }
    }
}
