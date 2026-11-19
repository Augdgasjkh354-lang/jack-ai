package com.jack.ai.api

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.nio.FloatBuffer

/**
 * 手机上抠图（BiRefNet-lite，ONNX Runtime，只用 CPU）：去掉背景，存成透明 PNG，裁到主体外框。
 * 模型文件由 data/LocalModels 下载；用完就放掉（加载后占好几百 MB，抠图又不常用）。
 */
object LocalCutout {
    private const val SIZE = 1024
    private const val MAX_SIDE = 2048
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    private val lock = Any()

    /** 抠好的图：宽高、主体占原图的多少（0～1）。 */
    data class Result(val width: Int, val height: Int, val coverage: Float)

    /** 抠 src，存成 out（PNG）。在后台线程调用；几秒到十几秒。 */
    fun cut(model: File, src: File, out: File): Result = synchronized(lock) {
        val photo = load(src) ?: error("打不开这张图片")
        try {
            val matte = run(model, photo)
            compose(photo, matte, out)
        } finally {
            photo.recycle()
        }
    }

    /** 读图：太大的先缩到长边 2048，按 EXIF 转正（手机拍的照片常常是横着存的）。 */
    private fun load(src: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
        val deg = runCatching {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** 跑模型：缩成 1024×1024、按 ImageNet 归一化，出 1024×1024 的前景概率（0～1）。 */
    private fun run(model: File, photo: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(photo, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== photo) small.recycle()
        val plane = SIZE * SIZE
        val input = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            input.put(i, (((c shr 16) and 255) / 255f - MEAN[0]) / STD[0])
            input.put(plane + i, (((c shr 8) and 255) / 255f - MEAN[1]) / STD[1])
            input.put(2 * plane + i, ((c and 255) / 255f - MEAN[2]) / STD[2])
        }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        env.createSession(model.path, opts).use { session ->
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { x ->
                session.run(mapOf(session.inputNames.first() to x)).use { r ->
                    val buf = (r.get(0) as OnnxTensor).floatBuffer
                    val out = FloatArray(plane)
                    buf.get(out, 0, minOf(plane, buf.remaining()))
                    // 模型说明里说输出已经过 sigmoid，实测 int4 版给的是原始分数（-20～17）：超出 0～1 就自己换算
                    if (out.any { it < -0.01f || it > 1.01f }) for (i in out.indices) out[i] = 1f / (1f + kotlin.math.exp(-out[i]))
                    return out
                }
            }
        }
    }

    /** 前景概率放大回原图大小当透明度，合成透明 PNG，再裁到主体外框（四周留一点边）。 */
    internal fun compose(photo: Bitmap, matte: FloatArray, out: File): Result {
        val m = IntArray(SIZE * SIZE) { i -> val a = (matte[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt(); (255 shl 24) or (a shl 16) or (a shl 8) or a }
        val mask = Bitmap.createBitmap(m, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(mask, photo.width, photo.height, true)
        mask.recycle()
        val w = photo.width; val h = photo.height
        val px = IntArray(w * h); val al = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        big.getPixels(al, 0, w, 0, 0, w, h)
        big.recycle()
        var minX = w; var minY = h; var maxX = -1; var maxY = -1; var solid = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = (al[i] shr 16) and 255
            px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
            if (a > 12) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
            if (a > 128) solid++
        }
        val coverage = solid.toFloat() / (w.toLong() * h)
        if (maxX < 0 || coverage < 0.003f) error("没认出照片里的主体（可能整张都是背景）")
        val pad = (maxOf(maxX - minX, maxY - minY) * 0.03f).toInt()
        val x0 = (minX - pad).coerceAtLeast(0); val y0 = (minY - pad).coerceAtLeast(0)
        val x1 = (maxX + pad).coerceAtMost(w - 1); val y1 = (maxY + pad).coerceAtMost(h - 1)
        val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
        val res = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        res.setPixels(px, y0 * w + x0, w, 0, 0, cw, ch)
        out.parentFile?.mkdirs()
        out.outputStream().use { res.compress(Bitmap.CompressFormat.PNG, 100, it) }
        res.recycle()
        return Result(cw, ch, coverage)
    }
}
