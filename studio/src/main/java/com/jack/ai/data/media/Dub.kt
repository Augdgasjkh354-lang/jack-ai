package com.jack.ai.data.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 翻译配音（思路来自 debpalash/VoiceStudio 的视频配音，做法是自己写的）：每句配音放回原视频里这句话开始的时间，
 * 配出来比原句长就加速（不变调），加到 [MAX_SPEED] 还放不下就往后挤一点、标出来让 Kavi 把译文缩短再配。
 * 纯逻辑（PCM 进、PCM 出），不碰 Android，好测。
 */
object Dub {
    /** 最多加速到几倍：再快听着就赶了。 */
    const val MAX_SPEED = 1.3f
    /** 比这快一点点的不加速（听不出来，免得每句都过一遍变速）。 */
    private const val SPEED_SLACK = 1.04f
    /** 句子之间至少留多少空。 */
    const val GAP_MS = 80L
    /** 最后一句可以比原句多出多少（后面没有话了）。 */
    const val TAIL_MS = 1500L
    /** 比原时间晚了这么多就算「挤了」，要标出来。 */
    const val LATE_MS = 400L

    enum class Fit { OK, FAST, LONG }

    /** 一句放在哪：从 [startMs] 开始，按 [speed] 倍速放，放完是 [endMs]；[lateMs] 是比原句晚开始了多少。 */
    data class Place(val startMs: Long, val endMs: Long, val speed: Float, val fit: Fit, val lateMs: Long, val needMs: Long, val slotMs: Long)

    /**
     * 排时间：cues 是原句的时间，durMs 是每句配音原速多长（0 = 没配出来，跳过）。
     * 一句能用的时间是从它开始到下一句开始（留 [GAP_MS]），最后一句到它结束再加 [TAIL_MS]。
     */
    fun plan(cues: List<Subtitles.Cue>, durMs: List<Long>): List<Place?> {
        require(cues.size == durMs.size)
        var free = 0L
        return cues.indices.map { i ->
            val d = durMs[i]
            if (d <= 0) return@map null
            val c = cues[i]
            val start = maxOf(c.startMs, free)
            val nextStart = (i + 1 until cues.size).firstOrNull { durMs[it] > 0 }?.let { cues[it].startMs }
            val limit = if (nextStart != null) maxOf(nextStart - GAP_MS, c.endMs) else c.endMs + TAIL_MS
            val slot = maxOf(limit - start, 1L)
            val want = d.toFloat() / slot
            val speed = when {
                want <= SPEED_SLACK -> 1f
                else -> minOf(want, MAX_SPEED)
            }
            val len = (d / speed).toLong()
            val end = start + len
            val late = start - c.startMs
            val fit = when {
                want > MAX_SPEED || late > LATE_MS -> Fit.LONG
                speed > 1f -> Fit.FAST
                else -> Fit.OK
            }
            free = end + GAP_MS
            Place(start, end, speed, fit, late, d, slot)
        }
    }

    /** 按排好的时间把每句（已经变过速的）铺到一条 PCM 上，中间补静音；[minMs] 是整条至少多长（原视频长度）。 */
    fun lay(places: List<Place?>, parts: List<ByteArray>, rate: Int, minMs: Long = 0): ByteArray {
        val endMs = maxOf(minMs, places.filterNotNull().maxOfOrNull { it.endMs } ?: 0L)
        val total = (endMs * rate / 1000).toInt() + 1
        val out = ByteArray(total * 2)
        places.forEachIndexed { i, p ->
            if (p == null) return@forEachIndexed
            val at = (p.startMs * rate / 1000).toInt() * 2
            val src = parts[i]
            val n = minOf(src.size, out.size - at).coerceAtLeast(0) and 1.inv()
            System.arraycopy(src, 0, out, at, n)
        }
        return out
    }

    /** 不变调地变速（Sonic，ExoPlayer 倍速播放用的那个）：16 位单声道 PCM。 */
    fun stretch(pcm: ByteArray, rate: Int, speed: Float): ByteArray {
        if (speed == 1f || pcm.isEmpty()) return pcm
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(speed)
        sonic.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        sonic.flush()
        val out = java.io.ByteArrayOutputStream(pcm.size)
        fun drain() {
            val b = sonic.output
            if (b.hasRemaining()) { val a = ByteArray(b.remaining()); b.get(a); out.write(a) }
        }
        var i = 0
        val chunk = 8192
        while (i < pcm.size) {
            val n = minOf(chunk, pcm.size - i)
            val buf = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            buf.put(pcm, i, n); buf.flip()
            while (buf.hasRemaining()) { sonic.queueInput(buf); drain() }
            i += n
        }
        sonic.queueEndOfStream()
        while (!sonic.isEnded) { val before = out.size(); drain(); if (out.size() == before && !sonic.isEnded) break }
        drain()
        sonic.reset()
        return out.toByteArray()
    }

    /** 给模型看的：要缩短的句子，大概缩到几个字。 */
    fun shorter(text: String, p: Place): Int {
        val chars = text.count { !it.isWhitespace() }
        val ratio = p.slotMs.toDouble() * 1.15 / p.needMs
        return (chars * ratio).toInt().coerceIn(1, maxOf(chars - 1, 1))
    }
}
