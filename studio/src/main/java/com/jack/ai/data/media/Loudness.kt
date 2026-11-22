package com.jack.ai.data.media

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 响度（ITU-R BS.1770 的做法，平台把视频都拉到 -14 LUFS 左右）：K 加权（高架 + 高通），400 毫秒一块、重叠 75%，
 * 去掉 -70 LUFS 以下的块，再去掉比平均低 10 LU 以上的块，剩下的平均。顺便找一大段没声音的地方、最大的采样值。
 * 纯计算，喂单声道 float（-1～1）样本。思路来自 zhouxiaoka/autoclip 的「技术底线」和 KyaniteLabs/kinocut 的出片质检。
 */
class Loudness(private val rate: Int) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun f(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    // K 加权的两级滤波，按采样率算系数（和 pyloudnorm 的公式一样）
    private val shelf = run {
        val g = 3.99984385397; val q = 0.7071752369554193; val fc = 1681.9744509555319
        val a = Math.pow(10.0, g / 40); val w = 2 * PI * fc / rate; val alpha = kotlin.math.sin(w) / (2 * q); val c = kotlin.math.cos(w)
        val sa = 2 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * c + sa
        Biquad(a * ((a + 1) + (a - 1) * c + sa) / a0, -2 * a * ((a - 1) + (a + 1) * c) / a0, a * ((a + 1) + (a - 1) * c - sa) / a0,
            2 * ((a - 1) - (a + 1) * c) / a0, ((a + 1) - (a - 1) * c - sa) / a0)
    }
    private val highpass = run {
        val q = 0.5003270373253953; val fc = 38.13547087613982
        val k = tan(PI * fc / rate)
        val d = 1 + k / q + k * k
        Biquad(1 / d, -2 / d, 1 / d, 2 * (k * k - 1) / d, (1 - k / q + k * k) / d)
    }

    private val step = rate / 10          // 100 毫秒
    private val sub = ArrayList<Double>()  // 每 100 毫秒的 K 加权均方
    private val raw = ArrayList<Double>()  // 每 100 毫秒的原始均方（找静音用）
    private var acc = 0.0; private var accRaw = 0.0; private var n = 0
    var peak = 0.0
        private set

    fun push(s: Float) {
        val x = s.toDouble()
        val a = kotlin.math.abs(x)
        if (a > peak) peak = a
        val k = highpass.f(shelf.f(x))
        acc += k * k; accRaw += x * x
        if (++n == step) { sub += acc / step; raw += accRaw / step; acc = 0.0; accRaw = 0.0; n = 0 }
    }

    /** 积分响度（LUFS）；几乎没声音返回 null。channels≥2 时按左右声道一样大算（加 3 dB）。 */
    fun integrated(channels: Int = 1): Double? {
        if (sub.size < 4) return null
        val blocks = (0..sub.size - 4).map { i -> (sub[i] + sub[i + 1] + sub[i + 2] + sub[i + 3]) / 4 }
        fun lufs(ms: Double) = -0.691 + 10 * log10(ms) + if (channels >= 2) 3.0103 else 0.0
        val abs = blocks.filter { it > 0 && lufs(it) > -70 }
        if (abs.isEmpty()) return null
        val rel = lufs(abs.average()) - 10
        val gated = abs.filter { lufs(it) > rel }
        return lufs(gated.ifEmpty { abs }.average())
    }

    /** 连着 [minMs] 以上没声音（低于 -50 dBFS）的段落，毫秒。 */
    fun silences(minMs: Long = 2000): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var start = -1
        val quiet = 1e-5 // -50 dBFS 的均方
        for (i in 0..raw.size) {
            val q = i < raw.size && raw[i] < quiet
            if (q && start < 0) start = i
            if (!q && start >= 0) { if ((i - start) * 100L >= minMs) out += start * 100L to i * 100L; start = -1 }
        }
        return out
    }

    val durationMs: Long get() = sub.size * 100L

    companion object {
        const val TARGET = -14.0

        /** 要调多少 dB 到 [TARGET]：差不到 1.5 dB 不调；往大调时最大的采样值不超过 -1 dBFS。返回 (dB, 是不是因为峰值没调够)。 */
        fun gainDb(lufs: Double, peak: Double): Pair<Double, Boolean> {
            val want = TARGET - lufs
            if (kotlin.math.abs(want) < 1.5) return 0.0 to false
            if (want < 0) return want to false
            val room = if (peak <= 0) want else -1.0 - 20 * log10(peak)
            return if (room < want) maxOf(0.0, room) to true else want to false
        }
    }
}
