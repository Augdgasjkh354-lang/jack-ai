package com.jack.ai.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.jack.ai.data.PricePoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 价格走势图：横轴时间、纵轴价格，标出最低最高和最新，存成 PNG（聊天里显示成图片卡片）。 */
object PriceChart {
    fun draw(s: List<PricePoint>, out: File): File? {
        if (s.size < 2) return null
        val w = 1080; val h = 640
        val left = 130f; val right = 50f; val top = 90f; val bottom = 90f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 90, 90); textSize = 26f }
        val title = Paint(text).apply { color = Color.rgb(30, 30, 30); textSize = 34f; isFakeBoldText = true }
        val grid = Paint().apply { color = Color.rgb(230, 230, 230); strokeWidth = 2f }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 110, 230); strokeWidth = 5f; style = Paint.Style.STROKE }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 110, 230) }
        val low = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 150, 70); textSize = 26f }
        val high = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 60, 50); textSize = 26f }

        c.drawText("「${s.last().item.take(24)}」价格走势", left, 55f, title)
        val t0 = s.first().at; val t1 = s.last().at.coerceAtLeast(t0 + 1)
        val lo = s.minOf { it.price }; val hi = s.maxOf { it.price }
        val pad = ((hi - lo) * 0.15).coerceAtLeast(hi * 0.02).coerceAtLeast(0.5)
        val pLo = (lo - pad).coerceAtLeast(0.0); val pHi = hi + pad
        fun x(at: Long) = left + (w - left - right) * ((at - t0).toFloat() / (t1 - t0))
        fun y(p: Double) = top + (h - top - bottom) * (1 - ((p - pLo) / (pHi - pLo)).toFloat())
        fun money(v: Double) = "¥" + if (v >= 100) "%.0f".format(v) else "%.2f".format(v)

        // 横线和价格刻度
        for (i in 0..4) {
            val p = pLo + (pHi - pLo) * i / 4
            val yy = y(p)
            c.drawLine(left, yy, (w - right), yy, grid)
            c.drawText(money(p), 10f, yy + 9f, text)
        }
        // 首尾日期
        val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
        c.drawText(fmt.format(Date(t0)), left, h - 40f, text)
        val endLabel = fmt.format(Date(s.last().at))
        c.drawText(endLabel, w - right - text.measureText(endLabel), h - 40f, text)

        val path = Path()
        s.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.at), y(p.price)) else path.lineTo(x(p.at), y(p.price)) }
        c.drawPath(path, line)
        s.forEach { p -> c.drawCircle(x(p.at), y(p.price), 7f, dot) }

        // 最低、最高、最新
        val pl = s.minBy { it.price }; val ph = s.maxBy { it.price }
        fun label(p: PricePoint, prefix: String, paint: Paint, above: Boolean) {
            val str = prefix + money(p.price)
            val tx = (x(p.at) - paint.measureText(str) / 2).coerceIn(left, w - right - paint.measureText(str))
            c.drawText(str, tx, y(p.price) + if (above) -18f else 38f, paint)
        }
        if (pl.price != ph.price) { label(ph, "最高 ", high, true); label(pl, "最低 ", low, false) }
        if (s.last() !== pl && s.last() !== ph) label(s.last(), "最新 ", title.apply { textSize = 26f }, true)

        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }
}
