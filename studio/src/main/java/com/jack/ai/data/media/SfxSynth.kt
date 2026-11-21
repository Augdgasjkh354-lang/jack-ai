package com.jack.ai.data.media

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 短视频常用音效，全部现场合成（44.1kHz 单声道），不用外面的音效文件，没有版权问题。
 * 再按成片时间把它们混成一条音轨，出片时和原声、配音、音乐一起混。
 */
object SfxSynth {
    const val RATE = 44_100

    fun sound(name: String): FloatArray = when (name) {
        "ding" -> bell(1320.0, 0.7, 0.55f)
        "coin" -> bell(1760.0, 0.25, 0.5f).let { a -> mix(a, bell(2640.0, 0.5, 0.45f), (0.09 * RATE).toInt()) }
        "pop" -> sweep(900.0, 260.0, 0.07, 0.8f)
        "boom" -> boom()
        "whoosh" -> noiseSweep(0.38, 300.0, 4000.0, 0.55f)
        "swoosh" -> noiseSweep(0.18, 1500.0, 7000.0, 0.5f)
        "click" -> burst(0.012, 0.6f)
        "shutter" -> mix(burst(0.02, 0.6f), burst(0.03, 0.5f), (0.07 * RATE).toInt())
        else -> FloatArray(0)
    }

    private fun n(sec: Double) = (sec * RATE).toInt()

    /** 铃声：基音加两个泛音，指数衰减。 */
    private fun bell(f: Double, sec: Double, amp: Float) = FloatArray(n(sec)) { i ->
        val t = i.toDouble() / RATE
        val env = minOf(1.0, t / 0.004) * exp(-t * 6.5)
        (amp * env * (sin(2 * PI * f * t) + 0.35 * sin(2 * PI * f * 2.76 * t) * exp(-t * 4) + 0.2 * sin(2 * PI * f * 5.4 * t) * exp(-t * 9))).toFloat() * 0.7f
    }

    /** 音高从 f0 滑到 f1 的短音（「啵」）。 */
    private fun sweep(f0: Double, f1: Double, sec: Double, amp: Float): FloatArray {
        val len = n(sec); var ph = 0.0
        return FloatArray(len) { i ->
            val p = i.toDouble() / len
            ph += 2 * PI * (f0 + (f1 - f0) * p) / RATE
            (amp * sin(ph) * minOf(1.0, i / (0.003 * RATE)) * (1 - p) * (1 - p)).toFloat()
        }
    }

    /** 「咚」：低频下滑的正弦加一点低通噪声。 */
    private fun boom(): FloatArray {
        val len = n(0.6); var ph = 0.0
        val rnd = java.util.Random(3); var lp = 0.0
        return FloatArray(len) { i ->
            val t = i.toDouble() / RATE
            ph += 2 * PI * (40 + 70 * exp(-t * 12)) / RATE
            lp += 0.02 * ((rnd.nextDouble() * 2 - 1) - lp)
            (0.9 * exp(-t * 5) * sin(ph) + 0.6 * exp(-t * 25) * lp * 4).toFloat().coerceIn(-1f, 1f)
        }
    }

    /** 「唰」：噪声过一个截止频率从低扫到高的低通，先渐强再渐弱。 */
    private fun noiseSweep(sec: Double, from: Double, to: Double, amp: Float): FloatArray {
        val len = n(sec)
        val rnd = java.util.Random(11)
        var a = 0.0; var b = 0.0
        return FloatArray(len) { i ->
            val p = i.toDouble() / len
            val fc = from * Math.pow(to / from, p)
            val k = 1 - exp(-2 * PI * fc / RATE)
            a += k * ((rnd.nextDouble() * 2 - 1) - a); b += k * (a - b)
            val env = sin(PI * p).let { it * it }
            (amp * env * b * 2.2).toFloat().coerceIn(-1f, 1f)
        }
    }

    private fun burst(sec: Double, amp: Float): FloatArray {
        val rnd = java.util.Random(5); val len = n(sec)
        return FloatArray(len) { i -> (amp * (rnd.nextDouble() * 2 - 1) * (1 - i.toDouble() / len)).toFloat() }
    }

    private fun mix(a: FloatArray, b: FloatArray, offset: Int): FloatArray {
        val out = FloatArray(maxOf(a.size, offset + b.size))
        a.copyInto(out); b.forEachIndexed { i, v -> out[offset + i] += v }
        return out
    }

    /** 按时间把音效混成一整条（长 totalMs），超过 0.95 的整体压下来；返回 16 位小端 PCM。 */
    fun track(events: List<VideoPlan.Sfx>, totalMs: Long): ByteArray {
        val len = (totalMs * RATE / 1000).toInt().coerceAtLeast(1)
        val buf = FloatArray(len)
        val cache = HashMap<String, FloatArray>()
        for (e in events) {
            val s = cache.getOrPut(e.sound) { sound(e.sound) }
            val at = (e.atMs * RATE / 1000).toInt()
            for (i in s.indices) { val j = at + i; if (j in 0 until len) buf[j] += s[i] * e.volume }
        }
        val peak = buf.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        val k = if (peak > 0.95f) 0.95f / peak else 1f
        val out = java.nio.ByteBuffer.allocate(len * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in buf) out.putShort((v * k * 32767).toInt().coerceIn(-32768, 32767).toShort())
        return out.array()
    }
}
