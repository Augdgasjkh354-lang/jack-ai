package com.jack.ai.a11y

/**
 * 在后台画面上直接操作：把手指在预览图上的位置换算成后台屏幕上的坐标。
 * 预览图按 ContentScale.Fit 放在框里（上下或左右留黑边），落在黑边上的不算。
 * 思路参考 Mika 的 PipInput（Apache-2.0），Kavi 自己的实现。
 */
object PipInput {
    data class Point(val x: Int, val y: Int)

    /** 一次手势：按下到抬起的点（框内坐标，像素）和时间（毫秒，从按下算起）。 */
    data class Gesture(val xs: List<Float>, val ys: List<Float>, val times: List<Long>)

    /** 移动不到这么多（框内像素）就算点了一下。 */
    const val TAP_SLOP = 24f

    /**
     * 框内一点 → 屏幕坐标。[boxW]×[boxH] 是放图的框，[imgW]×[imgH] 是图本身的像素（可能是缩小过的截图），
     * [screenW]×[screenH] 是后台屏幕的真实大小。落在黑边上返回 null。
     */
    fun map(x: Float, y: Float, boxW: Float, boxH: Float, imgW: Int, imgH: Int, screenW: Int, screenH: Int): Point? {
        if (boxW <= 0 || boxH <= 0 || imgW <= 0 || imgH <= 0) return null
        val scale = minOf(boxW / imgW, boxH / imgH)
        val left = (boxW - imgW * scale) / 2
        val top = (boxH - imgH * scale) / 2
        val ix = (x - left) / scale
        val iy = (y - top) / scale
        if (ix < 0 || iy < 0 || ix > imgW || iy > imgH) return null
        return Point((ix * screenW / imgW).toInt().coerceIn(0, screenW - 1), (iy * screenH / imgH).toInt().coerceIn(0, screenH - 1))
    }

    /** 点一下还是滑一下。 */
    fun isTap(g: Gesture): Boolean {
        if (g.xs.isEmpty()) return false
        val dx = g.xs.maxOf { it } - g.xs.minOf { it }
        val dy = g.ys.maxOf { it } - g.ys.minOf { it }
        return dx < TAP_SLOP && dy < TAP_SLOP
    }

    /**
     * 滑动换成注入用的一笔：最多 [maxPoints] 个点（均匀抽），时间从 0 开始、不倒退、最长 [maxMs]。
     * 落在黑边上的点挪到最近的边上。点不够两个返回 null。
     */
    fun stroke(g: Gesture, map: (Float, Float) -> Point?, maxPoints: Int = 60, maxMs: Long = 3_000): Triple<IntArray, IntArray, LongArray>? {
        val n = g.xs.size
        if (n < 2) return null
        val pick = if (n <= maxPoints) (0 until n).toList() else (0 until maxPoints).map { it * (n - 1) / (maxPoints - 1) }.distinct()
        val pts = pick.mapNotNull { i -> map(g.xs[i], g.ys[i])?.let { it to g.times[i] } }
        if (pts.size < 2) return null
        val t0 = pts.first().second
        var last = 0L
        val times = pts.map { (_, t) -> (t - t0).coerceIn(last, maxMs).also { last = it } }
        // 太快的一笔（同一毫秒）App 认不出是滑动，至少拉开到 120 毫秒
        val span = times.last().coerceAtLeast(1)
        val scaled = if (span < 120) times.map { it * 120 / span } else times
        return Triple(pts.map { it.first.x }.toIntArray(), pts.map { it.first.y }.toIntArray(), scaled.toLongArray())
    }
}
