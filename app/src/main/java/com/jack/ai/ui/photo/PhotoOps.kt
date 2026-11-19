package com.jack.ai.ui.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 修图的图片处理，全部是纯函数：给一张图，返回新的一张（不改原图）。
 * 坐标都按图的宽高归一化（0～1），预览用小图、保存用大图，同一套参数画出来一样。
 */
object PhotoOps {
    /** 编辑时的最大边长：够清楚，内存也扛得住（2560×1920 约 20MB）。 */
    const val EDIT_MAX = 2560

    /** 读图：按 EXIF 转正，缩到 maxSide 以内。 */
    fun load(file: File, maxSide: Int = EDIT_MAX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = false }) ?: return null
        val rotate = runCatching {
            when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotate != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotate) }, true)
        return scaleDown(bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp, maxSide)
    }

    fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val k = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()), true)
    }

    // —— 裁剪、旋转、翻转、拉直 ——

    /** 转了 quarter 个 90°、翻转、拉直 angle 度（自动放大，四角不露空）。 */
    fun orient(src: Bitmap, quarter: Int, flipH: Boolean, flipV: Boolean, angle: Float): Bitmap {
        val q = ((quarter % 4) + 4) % 4
        val m = Matrix()
        m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f, src.width / 2f, src.height / 2f)
        m.postRotate(q * 90f, src.width / 2f, src.height / 2f)
        var b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (abs(angle) > 0.05f) b = straighten(b, angle)
        return b
    }

    /** 拉直：转 angle 度并放大到刚好盖满原来的框。 */
    fun straighten(src: Bitmap, angle: Float): Bitmap {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val a = Math.toRadians(abs(angle).toDouble())
        val s = max((w * cos(a) + h * sin(a)) / w, (w * sin(a) + h * cos(a)) / h).toFloat()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(w / 2, h / 2); c.rotate(angle); c.scale(s, s); c.translate(-w / 2, -h / 2)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** 按归一化的框裁剪。 */
    fun crop(src: Bitmap, r: RectF): Bitmap {
        val l = (r.left.coerceIn(0f, 1f) * src.width).roundToInt()
        val t = (r.top.coerceIn(0f, 1f) * src.height).roundToInt()
        val rr = (r.right.coerceIn(0f, 1f) * src.width).roundToInt()
        val bb = (r.bottom.coerceIn(0f, 1f) * src.height).roundToInt()
        if (rr - l < 2 || bb - t < 2) return src
        return Bitmap.createBitmap(src, l, t, rr - l, bb - t)
    }

    /** 在宽高比 ratio（宽/高）下，图里最大的居中框（归一化）。 */
    fun fitRatio(imgW: Int, imgH: Int, ratio: Float): RectF {
        val imgR = imgW.toFloat() / imgH
        return if (ratio > imgR) { val hh = imgR / ratio; RectF(0f, (1 - hh) / 2, 1f, (1 + hh) / 2) }
        else { val ww = ratio / imgR; RectF((1 - ww) / 2, 0f, (1 + ww) / 2, 1f) }
    }

    // —— 滤镜 ——

    /** 预设滤镜：名字 → 颜色矩阵（4×5）。 */
    val FILTERS: List<Pair<String, FloatArray?>> = listOf(
        "原图" to null,
        "鲜亮" to cm { saturation(1.25f); contrast(1.08f); brightness(6f) },
        "元气" to cm { warmth(10f); saturation(1.15f); brightness(10f) },
        "清新" to cm { warmth(-10f); saturation(1.05f); brightness(8f); contrast(0.95f) },
        "柔和" to cm { contrast(0.85f); brightness(12f); saturation(0.9f) },
        "胶片" to cm { warmth(14f); contrast(0.92f); saturation(0.85f); lift(18f) },
        "暖阳" to cm { warmth(24f); saturation(1.1f) },
        "冷调" to cm { warmth(-22f); saturation(0.95f) },
        "淡雅" to cm { saturation(0.6f); brightness(14f); contrast(0.9f) },
        "复古" to cm { sepia(0.45f); contrast(0.95f); lift(10f) },
        "灰调" to cm { saturation(0.35f); contrast(0.95f) },
        "黑白" to cm { saturation(0f); contrast(1.1f) },
        "浓郁" to cm { saturation(1.45f); contrast(1.12f) },
    )

    private class CmBuilder { val m = ColorMatrix()
        fun post(a: ColorMatrix) { m.postConcat(a) }
        fun saturation(s: Float) = post(ColorMatrix().apply { setSaturation(s) })
        fun brightness(v: Float) = post(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, v, 0f, 1f, 0f, 0f, v, 0f, 0f, 1f, 0f, v, 0f, 0f, 0f, 1f, 0f)))
        fun contrast(c: Float) { val t = 128f * (1 - c); post(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))) }
        fun warmth(v: Float) = post(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, v, 0f, 1f, 0f, 0f, v * 0.2f, 0f, 0f, 1f, 0f, -v, 0f, 0f, 0f, 1f, 0f)))
        /** 提亮暗部（褪色感）：黑色不再是纯黑。 */
        fun lift(v: Float) { val k = 1 - v / 255f; post(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, v, 0f, k, 0f, 0f, v, 0f, 0f, k, 0f, v, 0f, 0f, 0f, 1f, 0f))) }
        fun sepia(a: Float) {
            val s = floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
            val id = ColorMatrix().array
            post(ColorMatrix(FloatArray(20) { id[it] + (s[it] - id[it]) * a }))
        }
    }
    private fun cm(block: CmBuilder.() -> Unit): FloatArray = CmBuilder().apply(block).m.array.copyOf()

    /** 按强度（0～1）在原图和滤镜之间插值。 */
    fun filterMatrix(m: FloatArray?, strength: Float): ColorMatrix? {
        if (m == null) return null
        val id = ColorMatrix().array
        return ColorMatrix(FloatArray(20) { id[it] + (m[it] - id[it]) * strength.coerceIn(0f, 1f) })
    }

    fun applyMatrix(src: Bitmap, cm: ColorMatrix?): Bitmap {
        if (cm == null) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    // —— 调节 ——

    /** 调节项：键、名字。值都是 -100～100，0 是不变。 */
    val ADJUST = listOf(
        "brightness" to "亮度", "contrast" to "对比度", "saturation" to "饱和度", "warmth" to "色温", "tint" to "色调",
        "highlights" to "高光", "shadows" to "阴影", "fade" to "褪色", "sharpen" to "锐度", "vignette" to "暗角",
    )

    /** 一次把所有调节做完（逐像素，预览用小图很快）。 */
    fun adjust(src: Bitmap, v: Map<String, Float>): Bitmap {
        fun g(k: String) = (v[k] ?: 0f) / 100f
        if (v.values.all { abs(it) < 0.5f }) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bright = g("brightness") * 60f
        val contrast = 1f + g("contrast") * (if (g("contrast") > 0) 0.6f else 0.5f)
        val sat = 1f + g("saturation")
        val warm = g("warmth") * 30f
        val tint = g("tint") * 25f
        val hi = g("highlights"); val sh = g("shadows"); val fade = g("fade").coerceAtLeast(0f)
        // 亮度曲线（按每个通道查表）：高光、阴影、褪色
        val lut = IntArray(256) { i ->
            var x = i / 255f
            x += sh * 0.35f * (1 - x) * (1 - x) * x * 4 * 0.6f + sh * 0.12f * (1 - x) * (1 - x)
            x += hi * 0.35f * x * x * (1 - x) * 4 * 0.6f + hi * 0.1f * x * x
            x = fade * 0.25f + x * (1 - fade * 0.32f)
            (x.coerceIn(0f, 1f) * 255).roundToInt()
        }
        val vig = g("vignette")
        val cx = w / 2f; val cy = h / 2f; val maxD = hypot(cx, cy)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val p = px[i]
            val a = p ushr 24
            var r = ((p shr 16) and 0xff).toFloat(); var gg = ((p shr 8) and 0xff).toFloat(); var b = (p and 0xff).toFloat()
            r += bright + warm; gg += bright + warm * 0.15f + tint; b += bright - warm
            r = (r - 128) * contrast + 128; gg = (gg - 128) * contrast + 128; b = (b - 128) * contrast + 128
            if (sat != 1f) { val l = 0.299f * r + 0.587f * gg + 0.114f * b; r = l + (r - l) * sat; gg = l + (gg - l) * sat; b = l + (b - l) * sat }
            var ri = lut[r.toInt().coerceIn(0, 255)]; var gi = lut[gg.toInt().coerceIn(0, 255)]; var bi = lut[b.toInt().coerceIn(0, 255)]
            if (vig != 0f) {
                val d = hypot(x - cx, y - cy) / maxD
                val k = 1f - vig * 0.75f * d * d
                ri = (ri * k).toInt().coerceIn(0, 255); gi = (gi * k).toInt().coerceIn(0, 255); bi = (bi * k).toInt().coerceIn(0, 255)
            }
            px[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        val sharpen = g("sharpen")
        if (sharpen > 0.01f) sharpenInPlace(px, w, h, sharpen * 1.2f)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 锐化（3×3 反锐化掩模）。 */
    private fun sharpenInPlace(px: IntArray, w: Int, h: Int, amount: Float) {
        val src = px.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var out = src[i] and 0xff000000.toInt()
            for (shift in intArrayOf(16, 8, 0)) {
                val c = (src[i] shr shift) and 0xff
                val n = ((src[i - 1] shr shift) and 0xff) + ((src[i + 1] shr shift) and 0xff) + ((src[i - w] shr shift) and 0xff) + ((src[i + w] shr shift) and 0xff)
                val v = (c + amount * (c * 4 - n) / 4f).roundToInt().coerceIn(0, 255)
                out = out or (v shl shift)
            }
            px[i] = out
        }
    }

    // —— 模糊、马赛克、虚化 ——

    /** 快速模糊：缩小再放大两次（盒式模糊近似高斯），radius 是相对图宽的比例。 */
    fun blur(src: Bitmap, radius: Float): Bitmap {
        val k = (1f / (radius.coerceIn(0.002f, 0.2f) * 6f)).coerceIn(0.02f, 1f)
        val sw = max(2, (src.width * k).roundToInt()); val sh = max(2, (src.height * k).roundToInt())
        var small = Bitmap.createScaledBitmap(src, sw, sh, true)
        small = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, max(2, sw / 2), max(2, sh / 2), true), sw, sh, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    /** 马赛克：块大小是图宽的 block 倍。 */
    fun pixelate(src: Bitmap, block: Float): Bitmap {
        val n = max(4, (1f / block.coerceIn(0.005f, 0.2f)).roundToInt())
        val sw = n; val sh = max(2, (n * src.height.toFloat() / src.width).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, false)
    }

    /** 虚化（景深）：焦点以外模糊。mode：radial 圆形、linear 线性（横带）。 */
    data class Focus(val mode: String = "radial", val cx: Float = 0.5f, val cy: Float = 0.5f, val radius: Float = 0.28f, val angle: Float = 0f, val strength: Float = 0.5f)

    fun focusBlur(src: Bitmap, f: Focus): Bitmap {
        if (f.mode == "none" || f.strength <= 0.01f) return src
        val blurred = blur(src, 0.004f + f.strength * 0.03f)
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val sharp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(sharp)
        c.drawBitmap(src, 0f, 0f, null)
        val r = f.radius * min(w, h)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = if (f.mode == "linear") {
                val a = Math.toRadians(f.angle.toDouble() + 90)
                val dx = (cos(a) * r * 1.6f).toFloat(); val dy = (sin(a) * r * 1.6f).toFloat()
                LinearGradient(f.cx * w - dx, f.cy * h - dy, f.cx * w + dx, f.cy * h + dy,
                    intArrayOf(0, Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
            } else RadialGradient(f.cx * w, f.cy * h, r * 1.6f, intArrayOf(Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, mask)
        val out = blurred.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(sharp, 0f, 0f, null)
        return out
    }

    // —— 画笔、马赛克笔 ——

    /** 一笔：点都是归一化坐标；width 是相对图宽的比例。kind：pen 画笔、arrow 箭头、line 直线、rect 方框、oval 圆、eraser 橡皮擦。 */
    data class Stroke(val kind: String, val points: List<Pair<Float, Float>>, val color: Int, val width: Float, val fill: String = "")

    fun strokePath(s: Stroke, w: Float, h: Float): Path {
        val p = Path()
        val pts = s.points.map { (x, y) -> x * w to y * h }
        if (pts.isEmpty()) return p
        val (x0, y0) = pts.first(); val (x1, y1) = pts.last()
        when (s.kind) {
            "line" -> { p.moveTo(x0, y0); p.lineTo(x1, y1) }
            "arrow" -> {
                p.moveTo(x0, y0); p.lineTo(x1, y1)
                val a = atan2(y1 - y0, x1 - x0); val len = s.width * w * 3.5f
                for (d in floatArrayOf(2.6f, -2.6f)) { p.moveTo(x1, y1); p.lineTo(x1 + len * cos(a + d), y1 + len * sin(a + d)) }
            }
            "rect" -> p.addRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), Path.Direction.CW)
            "oval" -> p.addOval(RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)), Path.Direction.CW)
            else -> {
                p.moveTo(x0, y0)
                if (pts.size == 1) p.lineTo(x0 + 0.1f, y0)
                // 用中点连二次曲线，线条顺滑
                for (i in 1 until pts.size) {
                    val (ax, ay) = pts[i - 1]; val (bx, by) = pts[i]
                    p.quadTo(ax, ay, (ax + bx) / 2, (ay + by) / 2)
                }
                p.lineTo(x1, y1)
            }
        }
        return p
    }

    /** 画笔一层（透明底），橡皮擦擦掉这一层上的笔画。 */
    fun drawStrokes(c: Canvas, strokes: List<Stroke>, w: Float, h: Float) {
        val layer = c.saveLayer(0f, 0f, w, h, null)
        strokes.forEach { s ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                strokeWidth = s.width * w; color = s.color
                if (s.kind == "eraser") xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            c.drawPath(strokePath(s, w, h), paint)
        }
        c.restoreToCount(layer)
    }

    /** 马赛克笔：按笔画把马赛克（或模糊）图刷上去，橡皮擦刷回原图。 */
    fun mosaic(src: Bitmap, strokes: List<Stroke>, pixel: Bitmap? = null, blurred: Bitmap? = null): Bitmap {
        if (strokes.isEmpty()) return src
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val px = pixel ?: pixelate(src, 0.035f)
        val bl by lazy { blurred ?: blur(src, 0.025f) }
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        strokes.forEach { s ->
            val shaderSrc = when (s.kind) { "eraser" -> src; "blur" -> bl; else -> px }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = s.width * w
                shader = BitmapShader(shaderSrc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            c.drawPath(strokePath(s.copy(kind = "pen"), w, h), paint)
        }
        return out
    }

    // —— 文字、贴纸 ——

    /**
     * 画在图上的一项：文字或贴纸（表情）。cx、cy 是中心（归一化），size 是字高相对图宽的比例。
     * style：plain 普通、stroke 描边、block 底色块、shadow 阴影。
     */
    data class Item(
        val id: Long, val text: String, val sticker: Boolean = false,
        val cx: Float = 0.5f, val cy: Float = 0.5f, val size: Float = 0.08f, val rotation: Float = 0f,
        val color: Int = Color.WHITE, val style: String = "stroke", val font: String = "sans", val bold: Boolean = true,
    )

    fun typeface(font: String, bold: Boolean): Typeface = when (font) {
        "serif" -> Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        "black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        "mono" -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        else -> Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(item: Item, w: Float): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = item.size * w
            typeface = typeface(item.font, item.bold)
            color = item.color
        }
        val maxW = (w * 0.9f).toInt().coerceAtLeast(10)
        val textW = item.text.split('\n').maxOf { tp.measureText(it) }.coerceAtMost(maxW.toFloat()).toInt().coerceAtLeast(1) + 2
        return StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, textW).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    }

    /** 这一项在图上的大小（像素，没转之前），用来画选中框、判断点没点到。 */
    fun itemSize(item: Item, w: Float): Pair<Float, Float> {
        val l = layout(item, w)
        val pad = if (item.style == "block") item.size * w * 0.45f else item.size * w * 0.12f
        return l.width + pad * 2 to l.height + pad * 2
    }

    fun drawItem(c: Canvas, item: Item, w: Float, h: Float) {
        val l = layout(item, w)
        val fs = item.size * w
        c.save()
        c.translate(item.cx * w, item.cy * h)
        c.rotate(item.rotation)
        c.translate(-l.width / 2f, -l.height / 2f)
        if (!item.sticker) when (item.style) {
            "block" -> {
                val pad = fs * 0.45f
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = item.color }
                c.drawRoundRect(RectF(-pad, -pad * 0.6f, l.width + pad, l.height + pad * 0.6f), fs * 0.3f, fs * 0.3f, bg)
                l.paint.color = if (luma(item.color) > 0.6f) Color.BLACK else Color.WHITE
            }
            "stroke" -> {
                val stroke = TextPaint(l.paint).apply { style = Paint.Style.STROKE; strokeWidth = fs * 0.14f; strokeJoin = Paint.Join.ROUND; color = if (luma(item.color) > 0.6f) Color.BLACK else Color.WHITE }
                StaticLayout.Builder.obtain(item.text, 0, item.text.length, stroke, l.width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build().draw(c)
            }
            "shadow" -> l.paint.setShadowLayer(fs * 0.12f, 0f, fs * 0.06f, 0x99000000.toInt())
        }
        l.draw(c)
        c.restore()
    }

    fun luma(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** 点 (x,y)（归一化）落在哪一项上（后加的在上面），没有返回 null。 */
    fun hit(items: List<Item>, x: Float, y: Float, w: Float, h: Float): Item? = items.lastOrNull { it ->
        val (iw, ih) = itemSize(it, w)
        val dx = (x - it.cx) * w; val dy = (y - it.cy) * h
        val a = Math.toRadians(-it.rotation.toDouble())
        val rx = dx * cos(a) - dy * sin(a); val ry = dx * sin(a) + dy * cos(a)
        abs(rx) <= iw / 2 + w * 0.03f && abs(ry) <= ih / 2 + w * 0.03f
    }

    // —— 边框 ——

    /** 边框：style none、thin 细边、wide 宽边、polaroid 拍立得（下边宽）、round 圆角（透明角）。color 是边框颜色。 */
    fun frame(src: Bitmap, style: String, color: Int): Bitmap {
        val w = src.width; val h = src.height
        val unit = min(w, h)
        return when (style) {
            "thin", "wide", "polaroid" -> {
                val t = (unit * if (style == "thin") 0.025f else 0.06f).roundToInt()
                val bottom = if (style == "polaroid") (unit * 0.2f).roundToInt() else t
                val out = Bitmap.createBitmap(w + t * 2, h + t + bottom, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(color)
                c.drawBitmap(src, t.toFloat(), t.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                out
            }
            "round" -> {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), unit * 0.06f, unit * 0.06f, p)
                out
            }
            else -> src
        }
    }

    /** 两点距离（缩放手势用）。 */
    fun dist(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
}
