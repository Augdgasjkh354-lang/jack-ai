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
        /** 铺满时裁掉的部分跟着人（人脸，没有就跟画面主体）走，不是只裁中间。 */
        val follow: Boolean = false,
        /** 切到这一段时用的转场（null 用整片的 transition）。 */
        val transition: String? = null,
    )

    /** 音效：sound 是 SOUNDS 里的一种，atMs 是成片上的时间（有封面时从封面之后算）。 */
    data class Sfx(val sound: String, val atMs: Long, val volume: Float = 1f)

    /** 画面上的字（花字），时间是成片上的。 */
    data class Text(val text: String, val startMs: Long, val endMs: Long, val style: String, val position: String)

    data class Plan(
        val size: Pair<Int, Int>?, val clips: List<Clip>, val transition: String, val texts: List<Text>,
        val subtitles: String?, val subtitleSource: String?, val music: String?, val musicVolume: Float, val duck: Boolean,
        /** 配音（voiceover 做的 wav），从正片开头放；voiceVolume 0..2。 */
        val voiceover: String? = null, val voiceVolume: Float = 1f,
        /** 封面图：放在最前面 coverMs 毫秒（已经插进 clips 第一段）。texts、按成片时间的字幕和配音都从封面之后算，引擎自动往后挪。 */
        val cover: String? = null, val coverMs: Long = 0,
        val sfx: List<Sfx> = emptyList(),
        /** 自动配音效：转场配「唰」「咚」，花字弹出配「啵」，价格配「叮」。 */
        val autoSfx: Boolean = false,
    ) {
        val fade get() = transition == "fade"

        /** 切进第 i 段时的转场：第一段之前只有过黑场（淡入）算数。 */
        fun transitionInto(i: Int): String =
            if (i == 0) (if (fade) "fade" else "cut") else clips[i].transition ?: transition

        /** 第 i 段结束时的转场：和下一段的进场一样；最后一段只有过黑场（淡出）。 */
        fun transitionOutOf(i: Int): String =
            if (i == clips.lastIndex) (if (fade) "fade" else "cut") else transitionInto(i + 1)
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
    /** cut 直接切、fade 过黑场、flash 闪白、zoom 缩放冲击、shake 抖一下。 */
    val TRANSITIONS = listOf("cut", "fade", "flash", "zoom", "shake")
    /** 合成的音效（不用外面的音效文件，没有版权问题）。 */
    val SOUNDS = linkedMapOf(
        "whoosh" to "唰（转场、划过）", "swoosh" to "嗖（短促的快切）", "ding" to "叮（重点、提示）", "pop" to "啵（花字弹出）",
        "boom" to "咚（重拍、震撼）", "click" to "咔（点击、切换）", "shutter" to "咔嚓（拍照）", "coin" to "叮叮（价格、优惠）",
    )
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
            val fit = c.s("fit").ifEmpty { "fill" }
            if (fit !in setOf("fill", "center", "fit")) return null to "$n 的 fit 只能是 fill（铺满，跟着人裁）、center（铺满，只裁中间）、fit（留边）"
            val fill = fit != "fit"
            val follow = fit == "fill"
            val tr = c.s("transition").ifEmpty { null }
            if (tr != null && tr !in TRANSITIONS) return null to "$n 的 transition 只能是 ${TRANSITIONS.joinToString("、")}"
            if (image) {
                val d = c.s("duration").ifEmpty { "3" }.let { time(it) } ?: return null to "$n 的 duration 写成秒数，比如 2.5"
                if (d !in 300..60_000) return null to "$n 图片放 0.3 到 60 秒"
                Clip(file, true, durationMs = d, zoom = zoom, filter = filter, fill = fill, transition = tr)
            } else {
                val s = c.s("start").takeIf { it.isNotEmpty() }?.let { time(it) ?: return null to "$n 的 start 认不出，写成 1:23 或秒数" } ?: 0L
                val e = c.s("end").takeIf { it.isNotEmpty() }?.let { time(it) ?: return null to "$n 的 end 认不出，写成 1:23 或秒数" }
                if (e != null && e - s < 200) return null to "$n 太短了（end 要比 start 晚至少 0.2 秒）"
                Clip(file, false, s, e, speed = speed, volume = volume, zoom = zoom, filter = filter, fill = fill, follow = follow, transition = tr)
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
        if (transition !in TRANSITIONS) return null to "transition 只能是 ${TRANSITIONS.joinToString("、")}"
        val sfx = (o["sfx"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(120).mapIndexed { i, x ->
            val n = "sfx 第 ${i + 1} 个"
            val sound = x.s("sound").takeIf { it in SOUNDS } ?: return null to "$n 的 sound 只能是 ${SOUNDS.keys.joinToString("、")}"
            val at = time(x.s("at").ifEmpty { return null to "$n 没写 at（成片上第几秒）" }) ?: return null to "$n 的 at 认不出"
            Sfx(sound, at, x.s("volume").ifEmpty { "1" }.toFloatOrNull()?.coerceIn(0f, 2f) ?: 1f)
        }
        val all = if (cover == null) clips else listOf(Clip(cover, true, durationMs = coverMs)) + clips
        return Plan(
            size, all, transition, texts,
            o.s("subtitles").ifEmpty { null }, o.s("subtitles_source").ifEmpty { null },
            o.s("music").ifEmpty { null }, mv, o.s("duck") != "false",
            o.s("voiceover").ifEmpty { null }, vv, cover, coverMs, sfx, o.s("auto_sfx") == "true",
        ) to ""
    }

    /**
     * 自动音效（成片时间，含封面偏移由调用方加）：每个转场在切点前 0.1 秒放「唰」（flash 配「嗖」、shake 配「咚」），
     * 花字出现时「啵」（价格「叮叮」）。已经手写在 sfx 里、离得很近（0.15 秒内）的不重复加。
     */
    fun autoSfx(plan: Plan, lengths: List<Long>): List<Sfx> {
        val out = mutableListOf<Sfx>()
        val offs = offsets(lengths)
        // 有封面时，封面切到正片那一下不配音效（正片从 0 秒开始，配了会抢开头）
        for (i in (if (plan.cover != null) 2 else 1) until plan.clips.size) {
            val t = plan.transitionInto(i)
            val at = offs[i] - plan.coverMs
            if (at < 0) continue
            when (t) {
                "zoom" -> out += Sfx("whoosh", (at - 150).coerceAtLeast(0), 0.7f)
                "flash" -> out += Sfx("swoosh", (at - 80).coerceAtLeast(0), 0.7f)
                "shake" -> out += Sfx("boom", at, 0.8f)
            }
        }
        plan.texts.forEach { t -> out += Sfx(if (t.style == "price") "coin" else "pop", t.startMs, 0.6f) }
        return out.filter { a -> plan.sfx.none { b -> kotlin.math.abs(a.atMs - b.atMs) < 150 } }
    }

    /**
     * 跟人裁的镜头路线：samples 是（这段里的毫秒, 主体中心 0..1 或 null 没找到）。
     * 没找到的用前一个找到的（开头没找到就用后面第一个，都没有就正中）；中值去掉偶尔认错的；
     * 再加「死区」：主体动得不多（不到 dead）镜头就不动，免得画面一直晃；最后按时间做平滑。
     */
    fun followPath(samples: List<Pair<Long, Float?>>, dead: Float = 0.06f): List<Pair<Long, Float>> {
        if (samples.isEmpty()) return emptyList()
        val firstFound = samples.firstNotNullOfOrNull { it.second } ?: 0.5f
        var last = firstFound
        val filled = samples.map { (t, x) -> t to (x ?: last).also { last = it } }
        val med = filled.indices.map { i ->
            val w = (maxOf(0, i - 2)..minOf(filled.lastIndex, i + 2)).map { filled[it].second }.sorted()
            filled[i].first to w[w.size / 2]
        }
        var cam = med.first().second
        val held = med.map { (t, x) -> if (kotlin.math.abs(x - cam) > dead) cam = x; t to cam }
        // 平滑：前后各看一格的加权平均（1:2:1），转向不会一下子跳过去
        return held.indices.map { i ->
            val a = held[maxOf(0, i - 1)].second; val b = held[i].second; val c = held[minOf(held.lastIndex, i + 1)].second
            held[i].first to (a + 2 * b + c) / 4
        }
    }

    /** 路线在某一时刻的位置（线性插值），并夹在裁剪框能到的范围里（window 是裁剪框占画面宽的比例）。 */
    fun followAt(path: List<Pair<Long, Float>>, ms: Long, window: Float): Float {
        if (path.isEmpty()) return 0.5f
        val x = when {
            ms <= path.first().first -> path.first().second
            ms >= path.last().first -> path.last().second
            else -> {
                val j = path.indexOfFirst { it.first >= ms }
                val (t0, x0) = path[j - 1]; val (t1, x1) = path[j]
                x0 + (x1 - x0) * (ms - t0).toFloat() / (t1 - t0).coerceAtLeast(1)
            }
        }
        val half = (window / 2).coerceIn(0f, 0.5f)
        return x.coerceIn(half, 1 - half)
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
