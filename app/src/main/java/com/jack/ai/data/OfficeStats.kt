package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Word、PPT、Excel 的一次做对率：每生成一份记一笔，同一次回答里 Kavi 自己返工（看了截图、按排版提醒改）也记一笔，
 * 以后用户再让改的记成「又让改」。只记份数、类型和排版提醒的条数，不记文件名和内容。
 * 看的是哪种文件最容易返工、交付时还带着排版问题的有多少。
 */
object OfficeStats {
    /** step：create 生成、rework 同一次回答里自己返工、change 之后用户又让改。job 把同一份文件的生成和返工连起来。 */
    @Serializable
    data class Event(val at: Long, val job: String, val kind: String, val step: String, val warnings: Int)

    data class Line(val kind: String, val made: Int, val firstPass: Int, val fixed: Int, val leftWarnings: Int, val changes: Int) {
        val percent: Int get() = if (made == 0) 0 else firstPass * 100 / made
    }

    val KINDS = mapOf("create_ppt" to "PPT", "create_word" to "Word", "create_excel" to "Excel")

    private const val MAX = 2000
    private const val WINDOW_MS = 30L * 24 * 3600 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "office_stats.json")

    fun load(ctx: Context): List<Event> = runCatching {
        json.decodeFromString(ListSerializer(Event.serializer()), file(ctx).readText())
    }.getOrDefault(emptyList())

    fun record(ctx: Context, e: Event) = synchronized(lock) {
        val list = (load(ctx) + e).takeLast(MAX)
        runCatching { file(ctx).writeText(json.encodeToString(ListSerializer(Event.serializer()), list)) }
        Unit
    }

    /**
     * 最近 30 天，总的和按类型的：
     * 一次过 = 生成后没返工、也没有排版提醒；自己改好 = 返工过、最后一版没有排版提醒；带着提醒交付 = 最后一版还有提醒。
     */
    fun lines(events: List<Event>, now: Long = System.currentTimeMillis()): Pair<Line, List<Line>> {
        val recent = events.filter { now - it.at < WINDOW_MS }
        val byJob = recent.filter { it.job.isNotEmpty() }.groupBy { it.job }
        fun line(kind: String, ev: List<Event>): Line {
            val jobs = ev.filter { it.step == "create" }.mapNotNull { c -> byJob[c.job]?.sortedBy { it.at } }
            return Line(kind, jobs.size,
                firstPass = jobs.count { j -> j.none { it.step == "rework" } && j.last().warnings == 0 },
                fixed = jobs.count { j -> j.any { it.step == "rework" } && j.last().warnings == 0 },
                leftWarnings = jobs.count { j -> j.last().warnings > 0 },
                changes = ev.count { it.step == "change" })
        }
        val all = line("全部", recent)
        val byKind = recent.groupBy { it.kind }.map { (k, ev) -> line(k, ev) }.filter { it.made > 0 || it.changes > 0 }.sortedByDescending { it.made }
        return all to byKind
    }

    /** 给自查和统计页看的几行。没有记录返回空。 */
    fun summary(events: List<Event>, now: Long = System.currentTimeMillis()): String {
        val (all, byKind) = lines(events, now)
        if (all.made == 0 && all.changes == 0) return ""
        fun text(l: Line) = "${l.made} 份，一次过 ${l.firstPass}（${l.percent}%），自己返工后改好 ${l.fixed}，交付时还带排版提醒 ${l.leftWarnings}" +
            if (l.changes > 0) "；之后又让改 ${l.changes} 次" else ""
        return "近 30 天生成 " + text(all) + "\n" + byKind.joinToString("\n") { "- ${it.kind}：" + text(it) }
    }
}
