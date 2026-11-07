package com.jack.ai.data

/**
 * 带时间的文字稿 / 字幕：转文字时按停顿切出来的一句句（[Cue]），存成 .srt；剪视频时按选中的片段把时间对到成片上，
 * 烧字幕时把长句拆成一屏放得下的几行。思路来自 modelscope/FunClip（MIT）：先转出带时间的文字，让模型照着挑片段。
 */
object Subtitles {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** 选中的一段（原视频上的时间）。 */
    data class Seg(val startMs: Long, val endMs: Long)

    fun stamp(ms: Long): String {
        val s = ms / 1000
        val tenth = ms % 1000 / 100
        return (if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)) + ".$tenth"
    }

    /** 给模型看的：一行一句「[0:12.3-0:18.9] 文字」。 */
    fun timed(cues: List<Cue>): String = cues.joinToString("\n") { "[${stamp(it.startMs)}-${stamp(it.endMs)}] ${it.text}" }

    fun toSrt(cues: List<Cue>): String = cues.withIndex().joinToString("\n") { (i, c) ->
        "${i + 1}\n${srtTime(c.startMs)} --> ${srtTime(c.endMs)}\n${c.text}\n"
    }

    private fun srtTime(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    /** 读 .srt（也认 WebVTT 那种用点分毫秒的）；格式不对的块跳过。 */
    fun parseSrt(text: String): List<Cue> {
        val time = Regex("""(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
        fun ms(h: String, m: String, s: String, f: String) = h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').toLong()
        return text.replace("\r", "").split(Regex("""\n\s*\n""")).mapNotNull { block ->
            val lines = block.trim().lines()
            val at = lines.indexOfFirst { time.containsMatchIn(it) }.takeIf { it >= 0 } ?: return@mapNotNull null
            val g = time.find(lines[at])!!.groupValues
            val body = lines.drop(at + 1).joinToString("\n").trim()
            val s = ms(g[1], g[2], g[3], g[4]); val e = ms(g[5], g[6], g[7], g[8])
            if (body.isEmpty() || e <= s) null else Cue(s, e, body)
        }.sortedBy { it.startMs }
    }

    /**
     * 「0:30-0:45, 1:10.5-1:32」→ 片段（按填的顺序，不排序：用户可能想调换顺序）。每段要有头有尾、尾比头晚。
     * 认不出返回 null 和哪一段写错了。
     */
    fun parseSegments(spec: String, parseTime: (String) -> Long?): Pair<List<Seg>?, String> {
        val parts = spec.replace('，', ',').replace('、', ',').replace('；', ',').replace(';', ',').split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null to "segments 是空的"
        val out = mutableListOf<Seg>()
        for (p in parts) {
            val m = Regex("""^(.+?)\s*(?:-|–|—|~|～|到)\s*(.+)$""").matchEntire(p) ?: return null to "「$p」要写成 开始-结束，比如 0:30-0:45"
            val s = parseTime(m.groupValues[1]) ?: return null to "「$p」的开始时间认不出"
            val e = parseTime(m.groupValues[2]) ?: return null to "「$p」的结束时间认不出"
            if (e <= s) return null to "「$p」结束要比开始晚"
            out += Seg(s, e)
        }
        return out to ""
    }

    /** 原视频上的字幕 → 成片上的：只留落在选中片段里的部分，时间按片段在成片里的位置平移。 */
    fun remap(cues: List<Cue>, segs: List<Seg>): List<Cue> {
        val out = mutableListOf<Cue>()
        var offset = 0L
        for (g in segs) {
            for (c in cues) {
                val s = maxOf(c.startMs, g.startMs); val e = minOf(c.endMs, g.endMs)
                // 只擦到一点边的（不到 0.3 秒）不要，免得片段开头闪一下上一句
                if (e - s >= 300) out += Cue(offset + s - g.startMs, offset + e - g.startMs, c.text)
            }
            offset += g.endMs - g.startMs
        }
        return out
    }

    /**
     * 一句太长一屏放不下：先按标点（没有就硬切）折成每行最多 [maxChars] 个字（英文数字两个算一个字），
     * 再两行一屏，时间按字数比例分。
     */
    fun forScreen(cues: List<Cue>, maxChars: Int = 16): List<Cue> = cues.flatMap { c ->
        val text = c.text.replace(Regex("""\s+"""), " ").trim()
        val screens = split(text, maxChars).chunked(2).map { it.joinToString("\n") }
        if (screens.size <= 1) return@flatMap listOf(c.copy(text = screens.firstOrNull() ?: text))
        val total = screens.sumOf { weight(it.replace("\n", "")) }.coerceAtLeast(1.0)
        var at = c.startMs.toDouble()
        val span = (c.endMs - c.startMs).toDouble()
        screens.mapIndexed { i, p ->
            val s = at; at += span * weight(p.replace("\n", "")) / total
            Cue(s.toLong(), if (i == screens.lastIndex) c.endMs else at.toLong(), p)
        }
    }

    /** 字宽：中文一个算 1，英文数字算 0.5。 */
    private fun weight(s: String): Double = s.sumOf { if (it.code < 0x2E80) 0.5 else 1.0 }

    /** 拆成每块不超过 limit 宽，尽量在标点后断。 */
    private fun split(text: String, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (weight(rest) > limit) {
            var w = 0.0; var cut = 0; var lastPunct = -1
            while (cut < rest.length && w + weight(rest[cut].toString()) <= limit) {
                w += weight(rest[cut].toString())
                if (rest[cut] in PUNCT) lastPunct = cut
                cut++
            }
            val at = if (lastPunct >= limit / 3) lastPunct + 1 else cut
            out += rest.substring(0, at).trim()
            rest = rest.substring(at).trim()
        }
        if (rest.isNotBlank()) out += rest
        return out.filter { it.isNotBlank() }
    }

    private val PUNCT = setOf('，', '。', '！', '？', '；', '、', ',', '.', '!', '?', ';', ' ', '：', ':')
}
