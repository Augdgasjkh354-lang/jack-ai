package com.jack.ai.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 看视频：模型不能直接看视频，就抽帧（均匀抽 + 画面变化大的地方），每帧标上时间，拼成几张宫格图交给能看图的模型。
 * 先多抽一倍的候选帧（小图），比较相邻两帧差多少，留下均匀的一半和变化最大的一半。
 */
object VideoFrames {
    /** 一张宫格图放几帧。 */
    const val PER_GRID = 9

    class Frame(val ms: Long, val bmp: Bitmap)

    /**
     * 这一段（毫秒）留几帧。短视频（抖音、小红书）看细：1 分钟以内每秒 1 帧（至少 9 帧）；
     * 再长的每 3 秒 1 帧，最多 72 帧（8 张宫格图），长视频先看个大概，要细看的段落再单独看。
     */
    fun keepFor(spanMs: Long): Int {
        val sec = ((spanMs + 999) / 1000).toInt()
        return if (sec <= 60) maxOf(9, sec) else (sec / 3).coerceIn(60, MAX_FRAMES)
    }

    const val MAX_FRAMES = 72

    /** 候选帧多少个：留的帧的两倍（好挑出画面变化大的），最多 96 个（抽帧太多太慢）。 */
    fun candidatesFor(keep: Int): Int = minOf(keep * 2, maxOf(keep, 96))

    /** n 个候选帧的时间：均匀分布在 [start, end) 里，避开开头和结尾的黑帧（各让半格）。 */
    fun candidateTimes(startMs: Long, endMs: Long, n: Int): List<Long> {
        val span = (endMs - startMs).coerceAtLeast(1)
        return (0 until n).map { i -> startMs + span * (2 * i + 1) / (2L * n) }
    }

    /**
     * 从候选帧里挑 keep 个：一半均匀取，另一半按「和前一帧差多少」从大到小补（转场、换画面），按时间排好。
     * diffs[i] 是第 i 帧和第 i-1 帧的差（第 0 帧随便填）。
     */
    fun pick(diffs: List<Float>, keep: Int): List<Int> {
        val n = diffs.size
        if (n <= keep) return (0 until n).toList()
        val even = keep / 2 + keep % 2
        val chosen = sortedSetOf<Int>()
        for (i in 0 until even) chosen += (i * n / even + n / (2 * even)).coerceIn(0, n - 1)
        diffs.indices.drop(1).sortedByDescending { diffs[it] }.forEach { if (chosen.size < keep) chosen += it }
        return chosen.toList()
    }

    /** 16×16 灰度小图，用来比两帧差多少。 */
    fun signature(b: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        val px = IntArray(256)
        s.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (s !== b) s.recycle()
        return FloatArray(256) { i -> val c = px[i]; (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f }
    }

    fun diff(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { kotlin.math.abs(a[it] - b[it]).toDouble() }.toFloat() / a.size

    /** 视频的时长（毫秒）、宽高（转正后）、有没有声音。读不出返回 null。 */
    class Info(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean, val hasVideo: Boolean)

    fun info(f: File): Info? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            fun k(key: Int) = r.extractMetadata(key)
            val d = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if ((k(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 == 90) { val x = w; w = h; h = x }
            Info(d, w, h, k(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes", k(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
        } finally {
            r.release()
        }
    }.getOrNull()

    /**
     * 抽帧：[start, end) 里多抽候选（长边 480），挑出 keep 帧。段落短（2 分钟内）按准确时间取，长的取最近的关键帧（快很多）。
     * progress(抽了几帧, 一共几帧)。
     */
    fun extract(f: File, startMs: Long, endMs: Long, keep: Int, progress: (Int, Int) -> Unit = { _, _ -> }): List<Frame> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val n = candidatesFor(keep)
            val times = candidateTimes(startMs, endMs, n)
            val option = if (endMs - startMs <= 120_000) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val frames = mutableListOf<Frame>()
            val sigs = mutableListOf<FloatArray>()
            times.forEachIndexed { i, t ->
                val b = frameAt(r, t * 1000, option)
                if (b != null) { frames += Frame(t, b); sigs += signature(b) }
                progress(i + 1, n)
            }
            if (frames.isEmpty()) return emptyList()
            val diffs = sigs.indices.map { i -> if (i == 0) 0f else diff(sigs[i], sigs[i - 1]) }
            val keepIdx = pick(diffs, keep).toSet()
            frames.forEachIndexed { i, fr -> if (i !in keepIdx) fr.bmp.recycle() }
            return frames.filterIndexed { i, _ -> i in keepIdx }
        } finally {
            r.release()
        }
    }

    private fun frameAt(r: MediaMetadataRetriever, us: Long, option: Int): Bitmap? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, option, 480, 480)
        else r.getFrameAtTime(us, option)?.let { b ->
            val s = 480f / maxOf(b.width, b.height)
            if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true).also { b.recycle() }
        }
    }.getOrNull()

    /** 时间写法：1:05、1:02:03。 */
    fun stamp(ms: Long): String = AudioPcm.stamp(ms / 1000)

    /**
     * 拼宫格图：3 列，每格左上角标时间。横的视频每格宽 400，竖的每格宽 300（整张不会太大）。
     */
    fun grid(frames: List<Frame>): Bitmap {
        val first = frames.first().bmp
        val portrait = first.height > first.width
        val cellW = if (portrait) 300 else 400
        val cellH = (cellW.toLong() * first.height / first.width.coerceAtLeast(1)).toInt().coerceIn(120, 600)
        val cols = minOf(3, frames.size)
        val rows = (frames.size + cols - 1) / cols
        val gap = 6
        val out = Bitmap.createBitmap(cols * cellW + (cols + 1) * gap, rows * cellH + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(24, 24, 24))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = cellW * 0.075f; isFakeBoldText = true }
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
        frames.forEachIndexed { i, fr ->
            val x = gap + (i % cols) * (cellW + gap)
            val y = gap + (i / cols) * (cellH + gap)
            // 画面按格子比例居中裁（每帧比例一样，基本不裁）
            val b = fr.bmp
            val scale = maxOf(cellW.toFloat() / b.width, cellH.toFloat() / b.height)
            val sw = cellW / scale; val sh = cellH / scale
            val src = android.graphics.Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt())
            c.drawBitmap(b, src, android.graphics.Rect(x, y, x + cellW, y + cellH), paint)
            val label = stamp(fr.ms)
            val tw = text.measureText(label)
            val pad = text.textSize * 0.35f
            c.drawRoundRect(RectF(x + 6f, y + 6f, x + 6f + tw + pad * 2, y + 6f + text.textSize + pad * 1.4f), pad, pad, tag)
            c.drawText(label, x + 6f + pad, y + 6f + pad * 0.6f + text.textSize * 0.92f, text)
        }
        return out
    }

    /** 宫格图转成 JPEG data URL（交给模型看）。 */
    fun dataUrl(b: Bitmap, quality: Int = 80): String {
        val bytes = java.io.ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /** 带时间的文字稿（「[0:12.3-0:18.9] 文字」一行一句）只留和 [start, end) 重叠的句子。 */
    fun transcriptBetween(timed: String, startMs: Long, endMs: Long): String {
        val re = Regex("""^\[([\d:.]+)-([\d:.]+)]""")
        return timed.lines().filter { line ->
            val m = re.find(line.trim()) ?: return@filter false
            val a = VideoEdit.parseTime(m.groupValues[1]) ?: return@filter true
            val b = VideoEdit.parseTime(m.groupValues[2]) ?: a
            b > startMs && a < endMs
        }.joinToString("\n")
    }
}
