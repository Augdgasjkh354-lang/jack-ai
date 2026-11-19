package com.jack.ai.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 剪辑方案（make_video 的 plan）：Kavi 照着文字稿、画面和音乐节拍写好，引擎照着出片。
 * 这里只管读懂方案、算时间线（每段在成片里从哪到哪）、把字幕对到成片上，和 Media3 无关，好测。
 *
 * 写法：
 * {"size":"9:16","transition":"fade",
 *  "clips":[{"file":"a.mp4","start":"0:03","end":"0:08","speed":1,"volume":1,"fit":"fill","zoom":"in","filter":"warm"},
 *           {"file":"p.jpg","duration":2.5,"zoom":"in"}],
 *  "texts":[{"text":"重点来了","start":1.2,"end":3,"style":"title","position":"top"}],
 *  "subtitles":"a-字幕.srt","music":"bgm.mp3","music_volume":0.3,"duck":true}
 */
object VideoPlan {
    /** 一段素材。视频：start/end 是原视频上的时间（end 为 null 到结尾）；图片：durationMs 是在成片里放多久。 */
    data class Clip(
        val file: String, val image: Boolean, val startMs: Long = 0, val endMs: Long? = null, val durationMs: Long = 0,
        val speed: Float = 1f, val volume: Float = 1f, val fill: Boolean = true, val zoom: String = "none", val filter: String = "none",
    )

    /** 画面上的字（花字），时间是成片上的。 */
    data class Text(val text: String, val startMs: Long, val endMs: Long, val style: String, val position: String)

    data class Plan(
        val size: Pair<Int, Int>?, val clips: List<Clip>, val fade: Boolean, val texts: List<Text>,
        val subtitles: String?, val subtitleSource: String?, val music: String?, val musicVolume: Float, val duck: Boolean,
        /** 配音（voiceover 做的 wav），从正片开头放；voiceVolume 0..2。 */
        val voiceover: String? = null, val voiceVolume: Float = 1f,
        /** 封面图：放在最前面 coverMs 毫秒（已经插进 clips 第一段）。texts、按成片时间的字幕和配音都从封面之后算，引擎自动往后挪。 */
        val cover: String? = null, val coverMs: Long = 0,
    ) {
        /** 字幕的时间是成片上的（配音的字幕），不用按片段对。 */
        val subtitlesOnOutput get() = subtitleSource == OUTPUT
    }

    /** subtitles_source 填这个：字幕时间就是成片时间（比如配音做出来的字幕）。 */
    const val OUTPUT = "output"

    val SIZES = linkedMapOf("9:16" to (1080 to 1920), "16:9" to (1920 to 1080), "1:1" to (1080 to 1080), "3:4" to (1080 to 1440), "4:3" to (1440 to 1080))
    val STYLES = listOf("title", "highlight", "caption", "label", "price")
    val POSITIONS = listOf("top", "center", "bottom")
    val ZOOMS = listOf("none", "in", "out")
    val FILTERS = listOf("none", "warm", "cool", "bw", "vivid", "film", "fade")
    const val MAX_CLIPS = 80
    const val MAX_TEXTS = 40

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()

    /** 「1:23」「83」「83.5」→ 毫秒。 */
    fun time(raw: String): Long? = VideoEditTime.parse(raw)

    /** 读方案；不对的地方返回说明（给 Kavi 看，让它改了再来）。isImage 按文件名判断是不是图片。 */
    fun parse(o: JsonObject, isImage: (String) -> Boolean): Pair<Plan?, String> {
        val size = o.s("size").ifEmpty { "9:16" }.let { raw ->
            if (raw == "original") null
            else SIZES[raw] ?: Regex("""^(\d{3,4})\s*[x×*]\s*(\d{3,4})$""").find(raw)?.let { m ->
                val w = m.groupValues[1].toInt() and 1.inv(); val h = m.groupValues[2].toInt() and 1.inv()
                if (w in 320..3840 && h in 320..3840) w to h else null
            } ?: return null to "size 只能是 ${SIZES.keys.joinToString("、")}、original，或「宽x高」"
        }
        val rawClips = (o["clips"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (rawClips.isEmpty()) return null to "clips 是空的：至少要一段视频或图片"
        if (rawClips.size > MAX_CLIPS) return null to "一次最多 $MAX_CLIPS 段，现在有 ${rawClips.size} 段：合并相邻的段，或者分两次做"
        val clips = rawClips.mapIndexed { i, c ->
            val n = "第 ${i + 1} 段"
            val file = c.s("file").ifEmpty { return null to "$n 没写 file" }
            val image = isImage(file)
            val speed = c.s("speed").ifEmpty { "1" }.toFloatOrNull()?.takeIf { it in 0.25f..4f } ?: return null to "$n 的 speed 要在 0.25 到 4 之间"
            val volume = c.s("volume").ifEmpty { "1" }.toFloatOrNull()?.takeIf { it in 0f..2f } ?: return null to "$n 的 volume 要在 0 到 2 之间"
            val zoom = c.s("zoom").ifEmpty { "none" }.takeIf { it in ZOOMS } ?: return null to "$n 的 zoom 只能是 ${ZOOMS.joinToString("、")}"
            val filter = c.s("filter").ifEmpty { "none" }.takeIf { it in FILTERS } ?: return null to "$n 的 filter 只能是 ${FILTERS.joinToString("、")}"
            val fill = c.s("fit").ifEmpty { "fill" } != "fit"
            if (image) {
                val d = c.s("duration").ifEmpty { "3" }.let { time(it) } ?: return null to "$n 的 duration 写成秒数，比如 2.5"
                if (d !in 300..60_000) return null to "$n 图片放 0.3 到 60 秒"
                Clip(file, true, durationMs = d, zoom = zoom, filter = filter, fill = fill)
            } else {
                val s = c.s("start").takeIf { it.isNotEmpty() }?.let { time(it) ?: return null to "$n 的 start 认不出，写成 1:23 或秒数" } ?: 0L
                val e = c.s("end").takeIf { it.isNotEmpty() }?.let { time(it) ?: return null to "$n 的 end 认不出，写成 1:23 或秒数" }
                if (e != null && e - s < 200) return null to "$n 太短了（end 要比 start 晚至少 0.2 秒）"
                Clip(file, false, s, e, speed = speed, volume = volume, zoom = zoom, filter = filter, fill = fill)
            }
        }
        val texts = (o["texts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(MAX_TEXTS).mapIndexed { i, t ->
            val n = "texts 第 ${i + 1} 条"
            val text = t.s("text").replace("\\n", "\n").ifEmpty { return null to "$n 没写 text" }
            val s = time(t.s("start").ifEmpty { "0" }) ?: return null to "$n 的 start 认不出"
            val e = time(t.s("end").ifEmpty { return null to "$n 没写 end" }) ?: return null to "$n 的 end 认不出"
            if (e <= s) return null to "$n 的 end 要比 start 晚"
            val style = t.s("style").ifEmpty { "title" }.takeIf { it in STYLES } ?: return null to "$n 的 style 只能是 ${STYLES.joinToString("、")}"
            val pos = t.s("position").ifEmpty { "top" }.takeIf { it in POSITIONS } ?: return null to "$n 的 position 只能是 ${POSITIONS.joinToString("、")}"
            Text(text, s, e, style, pos)
        }
        val mv = o.s("music_volume").ifEmpty { "0.3" }.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.3f
        val vv = o.s("voiceover_volume").ifEmpty { "1" }.toFloatOrNull()?.coerceIn(0f, 2f) ?: 1f
        val cover = o.s("cover").ifEmpty { null }
        if (cover != null && !isImage(cover)) return null to "cover 要填一张图片（用 design 做的封面 PNG）"
        val coverMs = if (cover == null) 0L else (time(o.s("cover_duration").ifEmpty { "0.8" }) ?: return null to "cover_duration 写成秒数").coerceIn(100, 3000)
        val transition = o.s("transition").ifEmpty { "cut" }
        if (transition !in setOf("cut", "fade")) return null to "transition 只能是 cut（直接切）或 fade（过黑场）"
        val all = if (cover == null) clips else listOf(Clip(cover, true, durationMs = coverMs)) + clips
        return Plan(
            size, all, transition == "fade", texts,
            o.s("subtitles").ifEmpty { null }, o.s("subtitles_source").ifEmpty { null },
            o.s("music").ifEmpty { null }, mv, o.s("duck") != "false",
            o.s("voiceover").ifEmpty { null }, vv, cover, coverMs,
        ) to ""
    }

    /** 每段在成片里多长（毫秒）。srcDur：视频原来多长（end 没写时到结尾）。 */
    fun lengths(clips: List<Clip>, srcDur: (Clip) -> Long): List<Long> = clips.map { c ->
        if (c.image) c.durationMs
        else {
            val end = (c.endMs ?: srcDur(c)).coerceAtMost(srcDur(c).takeIf { it > 0 } ?: Long.MAX_VALUE)
            ((end - c.startMs).coerceAtLeast(0) / c.speed).toLong()
        }
    }

    /** 每段在成片里从哪开始。 */
    fun offsets(lengths: List<Long>): List<Long> = lengths.runningFold(0L) { a, b -> a + b }.dropLast(1)

    /**
     * 原视频上的字幕 → 成片上的：只看来自 [source] 的那几段，按段在成片里的位置和变速平移；
     * 只擦到一点边（不到 0.3 秒）的不要。
     */
    fun remapSubtitles(cues: List<Subtitles.Cue>, clips: List<Clip>, lengths: List<Long>, source: String): List<Subtitles.Cue> {
        val offs = offsets(lengths)
        val out = mutableListOf<Subtitles.Cue>()
        clips.forEachIndexed { i, c ->
            if (c.image || c.file != source) return@forEachIndexed
            val segEnd = c.startMs + (lengths[i] * c.speed).toLong()
            for (q in cues) {
                val s = maxOf(q.startMs, c.startMs); val e = minOf(q.endMs, segEnd)
                if (e - s >= 300) out += Subtitles.Cue(offs[i] + ((s - c.startMs) / c.speed).toLong(), offs[i] + ((e - c.startMs) / c.speed).toLong(), q.text)
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * 背景音乐的音量包络：有人说话的时候压到 duck 倍，前后 0.3 秒渐变。返回某一时刻（毫秒）的倍数。
     * speech 是成片上说话的时间段（按开始排好）。
     */
    fun duckGain(speech: List<Pair<Long, Long>>, duck: Float = 0.35f, rampMs: Long = 300): (Long) -> Float {
        val merged = mutableListOf<LongArray>()
        for ((s, e) in speech.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            // 两句之间不到 0.8 秒的停顿不放大，免得音乐一抽一抽
            if (last != null && s - last[1] < 800) last[1] = maxOf(last[1], e) else merged += longArrayOf(s, e)
        }
        val starts = LongArray(merged.size) { merged[it][0] }
        return { t ->
            var i = java.util.Arrays.binarySearch(starts, t)
            if (i < 0) i = -i - 2
            // 离最近一段说话多远：在里面 0，在外面按距离渐变
            fun dist(k: Int): Long = if (k !in merged.indices) Long.MAX_VALUE else {
                val (s, e) = merged[k][0] to merged[k][1]
                when { t < s - 0 -> s - t; t > e -> t - e; else -> 0 }
            }
            val d = minOf(dist(i), dist(i + 1))
            if (d >= rampMs) 1f else duck + (1f - duck) * d.toFloat() / rampMs
        }
    }
}

/** 时间写法：「1:23」「1:02:03」「83.5」→ 毫秒（和 VideoEdit.parseTime 一样，这里不碰 Android）。 */
object VideoEditTime {
    fun parse(s: String): Long? {
        val t = s.trim().ifEmpty { return null }
        val parts = t.split(':', '：')
        if (parts.size > 3) return null
        var sec = 0.0
        for (p in parts) sec = sec * 60 + (p.trim().toDoubleOrNull() ?: return null)
        return (sec * 1000).toLong().takeIf { it >= 0 }
    }
}
