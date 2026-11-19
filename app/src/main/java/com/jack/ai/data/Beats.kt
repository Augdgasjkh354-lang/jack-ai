package com.jack.ai.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 找音乐的节拍（卡点用）：16kHz 单声道 → 每 16 毫秒一帧的响度 → 响度突然变大的地方（起音）→
 * 自相关找最像的节拍间隔（60～180 BPM，偏向 120 左右）→ 对齐到起音最强的相位，得到每一拍的时间；
 * 每 4 拍里最重的那一拍当「重拍」（换镜头最好卡在它上面）。
 */
object Beats {
    const val RATE = 16_000
    const val HOP = 256 // 16 毫秒
    private const val WIN = 512
    const val HOP_MS = HOP * 1000.0 / RATE

    class Result(val bpm: Double, val beatsMs: List<Long>, val strongMs: List<Long>, val durationMs: Long)

    /** 一个个样本推进来，攒成起音强度曲线。 */
    class Onsets {
        private val win = FloatArray(WIN)
        private var n = 0L
        private val energy = ArrayList<Float>()
        fun push(s: Float) {
            win[(n % WIN).toInt()] = s
            n++
            if (n >= WIN && (n - WIN) % HOP == 0L) {
                var sum = 0.0
                for (x in win) sum += x * x
                energy += ln(1e-7 + sum / WIN).toFloat()
            }
        }
        val samples get() = n
        /** 起音强度：响度比上一帧大多少（只要变大的），再减去附近 0.5 秒的平均（去掉整体变响）。 */
        fun flux(): FloatArray = fluxOf(energy.toFloatArray())
    }

    fun fluxOf(e: FloatArray): FloatArray {
        val raw = FloatArray(e.size) { i -> if (i == 0) 0f else maxOf(0f, e[i] - e[i - 1]) }
        val half = (250 / HOP_MS).toInt()
        val pre = DoubleArray(raw.size + 1).also { p -> raw.forEachIndexed { i, v -> p[i + 1] = p[i] + v } }
        return FloatArray(raw.size) { i ->
            val a = maxOf(0, i - half); val b = minOf(raw.size, i + half + 1)
            maxOf(0f, raw[i] - ((pre[b] - pre[a]) / (b - a)).toFloat())
        }
    }

    fun detect(flux: FloatArray, samples: Long): Result? {
        val dur = samples * 1000 / RATE
        if (flux.size < (4000 / HOP_MS).toInt()) return null
        // 节拍间隔：自相关，乘一个以 120 BPM 为中心的偏好（免得选成一半或两倍）
        val minLag = (60_000 / 180 / HOP_MS).toInt(); val maxLag = (60_000 / 60 / HOP_MS).toInt() + 1
        var best = -1.0; var lag = 0
        val score = DoubleArray(maxLag + 2)
        for (l in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until flux.size - l) s += flux[i] * flux[i + l]
            val bpm = 60_000 / (l * HOP_MS)
            val w = exp(-0.5 * (ln(bpm / 120.0) / ln(2.0) / 0.9).let { it * it })
            score[l] = s / (flux.size - l) * w
            if (score[l] > best) { best = score[l]; lag = l }
        }
        if (best <= 0) return null
        // 抛物线插值，间隔精确到零点几帧（长音乐里累计误差才不会越走越偏）
        val period = if (lag in (minLag + 1) until maxLag) {
            val a = score[lag - 1]; val b = score[lag]; val c = score[lag + 1]
            val d = a - 2 * b + c
            lag + if (d < 0) 0.5 * (a - c) / d else 0.0
        } else lag.toDouble()
        // 相位：哪个起点上的拍子落在起音上最多
        var phase = 0; var ps = -1.0
        for (p in 0 until period.toInt().coerceAtLeast(1)) {
            var s = 0.0; var t = p.toDouble()
            while (t < flux.size) { s += flux[t.roundToInt().coerceAtMost(flux.size - 1)]; t += period }
            if (s > ps) { ps = s; phase = p }
        }
        val frames = mutableListOf<Int>()
        var t = phase.toDouble()
        while (t < flux.size) {
            val c = t.roundToInt()
            // 每一拍在 ±2 帧（±32 毫秒）里对到最强的起音上
            val f = (maxOf(0, c - 2)..minOf(flux.size - 1, c + 2)).maxByOrNull { flux[it] } ?: c
            frames += f
            t += period
        }
        // 重拍：4 拍一组，看第几拍上的起音加起来最重
        val k = (0 until 4).maxByOrNull { off -> frames.indices.filter { it % 4 == off }.sumOf { flux[frames[it]].toDouble() } } ?: 0
        val toMs = { f: Int -> (f * HOP_MS + WIN * 500.0 / RATE).toLong() }
        val beats = frames.map(toMs).filter { it < dur }
        return Result(60_000 / (period * HOP_MS), beats, frames.filterIndexed { i, _ -> i % 4 == k }.map(toMs).filter { it < dur }, dur)
    }

    /** 给 Kavi 看的：BPM、每拍多长、重拍和全部拍子（太多就截前面）。 */
    fun describe(r: Result, name: String, maxList: Int = 160): String = buildString {
        val beatMs = 60_000 / r.bpm
        append("「$name」${Subtitles.stamp(r.durationMs).substringBefore('.')} 长，约 ${r.bpm.roundToInt()} BPM（每拍 ${(beatMs / 1000).let { "%.2f".format(it) }} 秒）。\n")
        fun list(ms: List<Long>) = ms.take(maxList).joinToString(" ") { "%.2f".format(it / 1000.0) } + if (ms.size > maxList) " …（共 ${ms.size} 个）" else ""
        append("重拍（秒，换镜头最好卡在这些点上）：").append(list(r.strongMs)).append("\n")
        append("全部拍子（秒）：").append(list(r.beatsMs))
        if (abs(r.bpm - 120) > 50) append("\n这首速度比较极端，卡点时可以隔一拍或两拍换一次镜头。")
    }
}
