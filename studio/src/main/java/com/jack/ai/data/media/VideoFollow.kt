package com.jack.ai.data.media

import android.graphics.Bitmap
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 横屏素材做竖屏时，找每一刻画面里的人在哪（左右位置 0..1），裁剪框跟着走。
 * 先找人脸（手机自带的人脸检测，不联网）；没有人脸就看画面里细节最多的地方（主体一般比背景细节多）。
 * 每 0.5 秒左右取一帧，一段最多 30 帧，用最近的关键帧（快）。
 */
object VideoFollow {
    fun samples(f: File, startMs: Long, endMs: Long, speed: Float): List<Pair<Long, Float?>> {
        val span = (endMs - startMs).coerceAtLeast(1)
        val n = (span / 500).toInt().coerceIn(3, 30)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            (0 until n).map { i ->
                val src = startMs + span * i / (n - 1).coerceAtLeast(1)
                val b = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(src * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                    else r.getFrameAtTime(src * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                // 时间换成这一段在成片里的（变速后）
                val rel = ((src - startMs) / speed).toLong()
                rel to b?.let { centerOf(it).also { _ -> it.recycle() } }
            }
        } finally {
            r.release()
        }
    }

    /** 一帧里主体的左右位置：最大的那张脸；没有脸就是细节最多的位置（往中间收一点）。 */
    fun centerOf(src: Bitmap): Float? {
        val w = (minOf(src.width, 320) and 1.inv()).coerceAtLeast(2)
        val h = (src.height * w / src.width.coerceAtLeast(1)).coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val face = runCatching {
            val rgb = small.copy(Bitmap.Config.RGB_565, false)
            val faces = arrayOfNulls<FaceDetector.Face>(5)
            val found = FaceDetector(w, h, faces.size).findFaces(rgb, faces)
            rgb.recycle()
            faces.take(found).filterNotNull().filter { it.confidence() > 0.3f }.maxByOrNull { it.eyesDistance() }?.let { fc ->
                val p = android.graphics.PointF(); fc.getMidPoint(p); p.x / w
            }
        }.getOrNull()
        val out = face ?: detail(small)
        if (small !== src) small.recycle()
        return out
    }

    /** 细节（亮度变化）的重心，和正中各占一半：没有人的画面别跟得太猛。 */
    fun detail(b: Bitmap): Float? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        fun lum(c: Int) = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
        var sum = 0.0; var sx = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val g = kotlin.math.abs(lum(px[y * w + x + 1]) - lum(px[y * w + x - 1])) + kotlin.math.abs(lum(px[(y + 1) * w + x]) - lum(px[(y - 1) * w + x]))
            if (g > 24) { sum += g; sx += g * x.toDouble() }
        }
        if (sum < w * h * 2.0) return null
        return (0.5 * (sx / sum / w) + 0.25).toFloat()
    }
}
