package com.jack.ai.data

/**
 * 配音的拼接（不碰 Android，好测）：每句是 16 位单声道 PCM。先把每句头尾的静音剪到只剩一点，
 * 再按顺序接起来、句子之间停一下，算出每句在整条配音里从几秒到几秒（就是字幕的时间）。
 */
object VoiceTrack {
    /** 剪掉头尾低于门限的静音，各留 [keepMs] 毫秒（免得字头字尾被吃掉）。 */
    fun trim(pcm: ByteArray, rate: Int, threshold: Int = 500, keepMs: Int = 60): ByteArray {
        val n = pcm.size / 2
        if (n == 0) return pcm
        fun at(i: Int) = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).let { if (it > 32767) it - 65536 else it }
        var a = 0
        while (a < n && kotlin.math.abs(at(a)) < threshold) a++
        if (a == n) return ByteArray(0)
        var b = n - 1
        while (b > a && kotlin.math.abs(at(b)) < threshold) b--
        val keep = rate * keepMs / 1000
        val s = maxOf(0, a - keep); val e = minOf(n, b + 1 + keep)
        return pcm.copyOfRange(s * 2, e * 2)
    }

    /** 接起来：返回整条 PCM 和每句的字幕（毫秒）。没合成出声音的句子跳过。 */
    fun join(lines: List<String>, parts: List<ByteArray>, rate: Int, pauseMs: Long): Pair<ByteArray, List<Subtitles.Cue>> {
        val out = java.io.ByteArrayOutputStream()
        val cues = mutableListOf<Subtitles.Cue>()
        val pause = ByteArray((rate * pauseMs / 1000).toInt() * 2)
        var samples = 0L
        lines.indices.forEach { i ->
            val p = parts[i]
            if (p.isEmpty()) return@forEach
            if (cues.isNotEmpty()) { out.write(pause); samples += pause.size / 2 }
            val start = samples * 1000 / rate
            out.write(p); samples += p.size / 2
            cues += Subtitles.Cue(start, samples * 1000 / rate, lines[i])
        }
        return out.toByteArray() to cues
    }
}
