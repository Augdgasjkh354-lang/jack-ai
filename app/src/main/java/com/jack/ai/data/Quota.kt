package com.jack.ai.data

import android.content.Context

/**
 * 用量额度（像 Claude 的套餐额度）：5 小时额度和每周额度，按 token 算（输入加输出，命中缓存的也算）。
 * 窗口从额度恢复后的第一次请求开始算：5 小时额度从那一刻起 5 小时后恢复，周额度 7 天后恢复。
 * 用完了新消息发不出去，正在做的事做完这一步就停，等恢复。限额填 0 表示不限。
 */
object Quota {
    const val FIVE_HOURS = 5 * 3600_000L
    const val WEEK = 7 * 24 * 3600_000L

    /** 一个窗口：什么时候开始、用了多少、限额多少。start 为 0 表示还没开始（下次请求才开始算）。 */
    data class Window(val label: String, val start: Long, val used: Long, val limit: Long, val length: Long) {
        val resetAt: Long get() = if (start > 0) start + length else 0
        val over: Boolean get() = limit > 0 && used >= limit
        val fraction: Float get() = if (limit <= 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quota", Context.MODE_PRIVATE)

    /** 窗口过期了就当它没开始。 */
    internal fun current(start: Long, used: Long, length: Long, now: Long): Pair<Long, Long> =
        if (start <= 0 || now >= start + length) 0L to 0L else start to used

    /** 记一次请求用掉的 token。 */
    fun add(ctx: Context, tokens: Long, now: Long = System.currentTimeMillis()) {
        if (tokens <= 0) return
        synchronized(this) {
            val p = prefs(ctx)
            val e = p.edit()
            listOf("five" to FIVE_HOURS, "week" to WEEK).forEach { (key, length) ->
                val (start, used) = current(p.getLong("${key}Start", 0), p.getLong("${key}Used", 0), length, now)
                e.putLong("${key}Start", if (start > 0) start else now)
                e.putLong("${key}Used", used + tokens)
            }
            e.apply()
        }
    }

    fun windows(ctx: Context, settings: Settings, now: Long = System.currentTimeMillis()): List<Window> {
        val p = prefs(ctx)
        return listOf(
            Triple("5 小时额度", "five", FIVE_HOURS) to settings.quotaFiveHours,
            Triple("每周额度", "week", WEEK) to settings.quotaWeek,
        ).map { (w, limit) ->
            val (start, used) = current(p.getLong("${w.second}Start", 0), p.getLong("${w.second}Used", 0), w.third, now)
            Window(w.first, start, used, limit, w.third)
        }
    }

    /** 用完的那个窗口（两个都用完取恢复得晚的）；没用完返回 null。 */
    fun blocked(ctx: Context, settings: Settings, now: Long = System.currentTimeMillis()): Window? =
        windows(ctx, settings, now).filter { it.over }.maxByOrNull { it.resetAt }

    /** 「5 小时额度用完了，14:30 恢复」。 */
    fun message(w: Window, now: Long = System.currentTimeMillis()): String = "${w.label}用完了，${resetText(w.resetAt, now)}恢复"

    fun resetText(at: Long, now: Long = System.currentTimeMillis()): String {
        val f = if (at - now < 20 * 3600_000L && java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).let { it.format(at) == it.format(now) })
            "HH:mm" else "M月d日 HH:mm"
        return java.text.SimpleDateFormat(f, java.util.Locale.CHINA).format(java.util.Date(at))
    }

    /** 1.2M、350K 这样的写法。 */
    fun short(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        n >= 1_000 -> "${n / 1_000}K"
        else -> n.toString()
    }
}
