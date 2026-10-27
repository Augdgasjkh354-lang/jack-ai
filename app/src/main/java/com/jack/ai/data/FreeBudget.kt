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
    /** 给下次自由时间衔接用，和聊天中的近况分开。 */
    val freeSummary: String = "",
    val freeSummaryAt: Long = 0,
    /** Kavi 跟用户提的心愿：想要一笔预算做某件事，或者想买个东西。 */
    val wishes: List<Wish> = emptyList(),
    /** Kavi 自己约的下一次自由时间（0 = 没约，按每天凌晨那次）。 */
    val nextAt: Long = 0,
)

/**
 * 一个心愿。kind：budget 想要一笔预算做某件事；buy 想买个东西（Kavi 不能付钱，用户决定买不买、自己去买）。
 * amount：要多少预算 / 东西的价格；offer：买东西时 Kavi 愿意从积蓄里出多少。
 * status：pending 等用户回、approved 给了预算、declined 先不了、bought 买了。told：Kavi 知道结果了没有。
 */
@Serializable
data class Wish(
    val id: String = newId(),
    val kind: String = "budget",
    val text: String,
    val amount: Double = 0.0,
    val offer: Double = 0.0,
    val url: String = "",
    val at: Long = System.currentTimeMillis(),
    val status: String = "pending",
    val answeredAt: Long = 0,
    val told: Boolean = false,
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
        cached ?: runCatching { json.decodeFromString(FreeState.serializer(), String(android.util.AtomicFile(file(ctx)).readFully())) }.getOrDefault(FreeState()).also { cached = it }
    }

    fun invalidate() = synchronized(lock) { cached = null }

    fun save(ctx: Context, s: FreeState) = synchronized(lock) {
        val f = android.util.AtomicFile(file(ctx))
        val out = f.startWrite()
        try {
            out.write(json.encodeToString(FreeState.serializer(), s).toByteArray())
            f.finishWrite(out)
            cached = s
        } catch (e: Exception) { f.failWrite(out); throw e }
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
        return state.copy(savings = maxOf(state.savings, savings.coerceAtMost(budget * SAVE_DAYS)), accruedThrough = yesterday)
    }

    /**
     * 聊天时给 Kavi 的那段：它自己的近况、还在等用户回的心愿；full 时再加上常记的自己的经历和自由时间里没做完的事
     * （自由时间的开场里已经有这两样，那里不用 full）。都只在自由时间后变，放在系统提示最后。空的就没有。
     */
    fun latelyNote(ctx: Context, full: Boolean = true): String {
        val s = load(ctx)
        val waiting = s.wishes.filter { it.status == "pending" }
        return buildString {
            append(s.lately)
            if (waiting.isNotEmpty()) append("\nWaiting for an answer: ").append(waiting.joinToString("; ") { describe(it) })
            if (full) {
                OwnMemory.hotLines(ctx).takeIf { it.isNotBlank() }?.let { append("\nThings you did or thought in your own time (details with own_history):\n").append(it) }
                s.freeSummary.takeIf { it.isNotBlank() }?.let { append("\nLeft unfinished in your free time:\n").append(it) }
            }
        }.trim()
    }

    fun money(v: Double) = "¥" + String.format(Locale.US, "%.2f", v)

    /** 心愿的一句话描述（给模型看）。 */
    fun describe(w: Wish): String = if (w.kind == "buy") "buy 「${w.text}」 for ${money(w.amount)}" + (if (w.offer > 0) " (you offered ${money(w.offer)} from savings)" else "")
        else "${money(w.amount)} for 「${w.text}」"

    /** 用户回了心愿：给预算就进积蓄（不受攒钱上限限制，是送的）；买了就从积蓄里扣掉 Kavi 愿意出的那部分。 */
    fun answer(s: FreeState, id: String, status: String, now: Long = System.currentTimeMillis()): FreeState {
        val w = s.wishes.firstOrNull { it.id == id && it.status == "pending" } ?: return s
        val savings = when {
            status == "approved" && w.kind == "budget" -> s.savings + w.amount
            status == "bought" -> (s.savings - w.offer).coerceAtLeast(0.0)
            else -> s.savings
        }
        return s.copy(savings = savings, wishes = s.wishes.map { if (it.id == id) it.copy(status = status, answeredAt = now, told = false) else it })
    }

    /** 自由时间开始时告诉 Kavi 的：心愿的回音（回了没告诉过的）和还在等的。返回（给模型的话，标成已告诉后的状态）。 */
    fun news(s: FreeState): Pair<String, FreeState> {
        val answered = s.wishes.filter { it.status != "pending" && !it.told }
        val waiting = s.wishes.filter { it.status == "pending" }
        val lines = answered.map { w ->
            when (w.status) {
                "approved" -> "{User} said yes to your wish ${describe(w)}: ${money(w.amount)} went into your savings."
                "bought" -> "{User} bought 「${w.text}」 for you" + (if (w.offer > 0) " (${money(w.offer)} came out of your savings, as you offered)." else ".")
                else -> "{User} passed on your wish ${describe(w)} for now."
            }
        } + waiting.map { "Still waiting: ${describe(it)}" }
        val told = s.copy(wishes = s.wishes.map { if (it.status != "pending") it.copy(told = true) else it }.takeLast(30))
        return lines.joinToString("\n") to told
    }
}
