package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Kavi 自己的钱和近况：
 * - savings：以前没花完的每日预算攒下来的积蓄，最多攒 [SAVE_DAYS] 天的量；accruedThrough 是攒到了哪一天（yyyy-MM-dd）；
 * - lately：它最近在想什么、在做什么（每次自由时间结束时自己写的一两句），聊天时它知道自己的近况。
 */
@Serializable
data class FreeState(
    val savings: Double = 0.0,
    val accruedThrough: String = "",
    val lately: String = "",
    val latelyAt: Long = 0,
)

/**
 * 一次自由时间的钱：今天剩下的、从积蓄里拿来用的、收尾用的额外预算。
 * 花到 [soft]（今天的 + 拿的积蓄）时系统说一声；到 [limit]（再加额外预算）就停。拿了积蓄会重新算，提醒可以再来一次。
 */
class FreeBudget(val today: Double, val extra: Double, savings: Double, private val noteFor: (Double) -> String) {
    @Volatile var savings = savings; private set
    @Volatile var fromSavings = 0.0; private set
    /** 这次已经提醒过「预算用完」了。 */
    @Volatile var noted = false
    /** 这次已经花了多少（runBackgroundWork 接上）。 */
    @Volatile var spentNow: () -> Double = { 0.0 }
    val soft: Double get() = today + fromSavings
    val limit: Double get() = soft + extra
    fun note(): String = noteFor(extra)

    /** 从积蓄里拿 amount 用在这次；拿不了那么多就拿全部。返回真拿到的。 */
    @Synchronized fun use(amount: Double): Double {
        val a = amount.coerceIn(0.0, savings)
        savings -= a
        fromSavings += a
        if (a > 0) noted = false
        return a
    }

    /** 结束时算账：从积蓄里拿了但没花掉的退回去。返回最后的积蓄。 */
    fun settle(spent: Double): Double {
        val used = (spent - today).coerceIn(0.0, fromSavings)
        return savings + (fromSavings - used)
    }
}

object FreeTime {
    const val SAVE_DAYS = 7
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "free_state.json")
    @Volatile private var cached: FreeState? = null

    fun load(ctx: Context): FreeState = synchronized(lock) {
        cached ?: runCatching { json.decodeFromString(FreeState.serializer(), file(ctx).readText()) }.getOrDefault(FreeState()).also { cached = it }
    }

    fun save(ctx: Context, s: FreeState) = synchronized(lock) {
        cached = s
        runCatching { file(ctx).writeText(json.encodeToString(FreeState.serializer(), s)) }
    }

    fun day(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(at))

    /**
     * 把以前没花完的预算攒进积蓄：从上次攒到的那天之后、到昨天为止，有过自由时间的每一天，没花完的部分攒进来；
     * 一共最多攒 SAVE_DAYS 天的预算。没过自由时间的日子（手机关着、关了自由时间）不攒。第一次只记下日子，不攒。
     */
    fun accrue(state: FreeState, entries: List<ReadEntry>, budget: Double, now: Long = System.currentTimeMillis()): FreeState {
        val today = day(now)
        val yesterday = day(now - 86_400_000L)
        if (state.accruedThrough.isEmpty()) return state.copy(accruedThrough = yesterday)
        if (state.accruedThrough >= yesterday) return state
        val byDay = entries.groupBy { day(it.at) }
        var savings = state.savings
        byDay.keys.filter { it > state.accruedThrough && it < today }.sorted().forEach { d ->
            savings += (budget - byDay.getValue(d).sumOf { it.spent }).coerceAtLeast(0.0)
        }
        return state.copy(savings = savings.coerceAtMost(budget * SAVE_DAYS), accruedThrough = yesterday)
    }

    /** 聊天时给 Kavi 的那段：它自己的近况。空的就没有。 */
    fun latelyNote(ctx: Context): String = load(ctx).lately
}
