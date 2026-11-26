package com.jack.ai.data.games

import kotlin.random.Random

/** 一张牌：rank 2～14（14 是 A），suit 0♠ 1♥ 2♦ 3♣。 */
data class Card(val rank: Int, val suit: Int) {
    val red get() = suit == 1 || suit == 2
    val rankText get() = when (rank) { 14 -> "A"; 13 -> "K"; 12 -> "Q"; 11 -> "J"; 10 -> "10"; else -> rank.toString() }
    val suitText get() = "♠♥♦♣"[suit].toString()
    override fun toString() = rankText + suitText

    companion object {
        fun deck(): List<Card> = (0..3).flatMap { s -> (2..14).map { Card(it, s) } }
    }
}

/** 比牌：7 张里挑最好的 5 张，分数越大越好；同分就是平分。 */
object HandEval {
    private val NAMES = listOf("高牌", "一对", "两对", "三条", "顺子", "同花", "葫芦", "四条", "同花顺")

    fun best(cards: List<Card>): Long {
        require(cards.size in 5..7)
        var top = -1L
        val n = cards.size
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) for (e in d + 1 until n) {
            val s = five(listOf(cards[a], cards[b], cards[c], cards[d], cards[e]))
            if (s > top) top = s
        }
        return top
    }

    /** 牌型的中文名（「同花顺」「两对」……）。 */
    fun name(score: Long): String = NAMES[(score shr 20).toInt().coerceIn(0, 8)]

    private fun pack(cat: Int, ranks: List<Int>): Long {
        var v = cat.toLong()
        for (i in 0 until 5) v = (v shl 4) or (ranks.getOrElse(i) { 0 }.toLong())
        return v
    }

    internal fun five(cs: List<Card>): Long {
        val flush = cs.all { it.suit == cs[0].suit }
        val ranks = cs.map { it.rank }.sortedDescending()
        val distinct = ranks.distinct()
        // A 2 3 4 5 也算顺子，最大的是 5
        val straightTop = when {
            distinct.size == 5 && ranks[0] - ranks[4] == 4 -> ranks[0]
            distinct == listOf(14, 5, 4, 3, 2) -> 5
            else -> 0
        }
        // 按「张数多的在前、同张数大的在前」排
        val groups = ranks.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
        val byGroup = groups.map { it.key }
        val counts = groups.map { it.value }
        return when {
            straightTop > 0 && flush -> pack(8, listOf(straightTop))
            counts[0] == 4 -> pack(7, byGroup)
            counts[0] == 3 && counts[1] == 2 -> pack(6, byGroup)
            flush -> pack(5, ranks)
            straightTop > 0 -> pack(4, listOf(straightTop))
            counts[0] == 3 -> pack(3, byGroup)
            counts[0] == 2 && counts[1] == 2 -> pack(2, byGroup)
            counts[0] == 2 -> pack(1, byGroup)
            else -> pack(0, ranks)
        }
    }
}

/** 一个人的动作。加注写「加到多少」（这一轮总共下多少），不是「再加多少」。 */
sealed class Act {
    data object Fold : Act()
    data object Check : Act()
    data object Call : Act()
    data class Raise(val to: Int) : Act()
    data object AllIn : Act()
}

class Seat(val id: Int, val name: String, val human: Boolean, var chips: Int) {
    var hole: List<Card> = emptyList()
    /** 这一轮（这条街）下了多少。 */
    var bet = 0
    /** 这一手一共下了多少（算边池用）。 */
    var total = 0
    var folded = false
    var allIn = false
    /** 筹码输光了，不再发牌。 */
    var out = false
    /** 最近一个动作（显示在座位上）。 */
    var last = ""
    val live get() = !out && !folded
}

/** 轮到某人时能做什么。 */
data class Legal(val toCall: Int, val minRaiseTo: Int, val maxRaiseTo: Int) {
    val canCheck get() = toCall == 0
    /** 筹码够不够加注（不够就只能全下）。 */
    val canRaise get() = maxRaiseTo >= minRaiseTo
}

/** 一手结束：谁赢了多少、亮出来的牌型。 */
data class HandResult(val won: Map<Int, Int>, val shown: Map<Int, String>, val text: String)

/**
 * 无限注德州扑克的牌桌：发牌、下注、过街、比牌、分边池都在这里，模型只管「这一步做什么」。
 * 所有公开发生的事记在 [log] 里（谁下了多少、发了什么公共牌、谁赢了），对手模型按各自读到哪接着看。
 */
class Holdem(
    names: List<Pair<String, Boolean>>,
    startChips: Int = 1000,
    val small: Int = 10,
    val big: Int = 20,
    private val rnd: Random = Random.Default,
) {
    val seats = names.mapIndexed { i, (n, h) -> Seat(i, n, h, startChips) }
    val board = mutableListOf<Card>()
    val log = mutableListOf<String>()
    var dealer = -1
        private set
    var hand = 0
        private set
    /** 0 翻牌前，1 翻牌，2 转牌，3 河牌。 */
    var street = 0
        private set
    /** 轮到谁；-1 是这一手没在下注（结束了或还没开始）。 */
    var toAct = -1
        private set
    var result: HandResult? = null
        private set
    private var deck = mutableListOf<Card>()
    private var currentBet = 0
    private var minRaise = 0
    private val pending = mutableSetOf<Int>()

    val pot get() = seats.sumOf { it.total }
    val inPlay get() = seats.filter { !it.out }
    val over get() = inPlay.size <= 1 || seats.any { it.human && it.out }

    private fun next(from: Int, ok: (Seat) -> Boolean): Int {
        for (k in 1..seats.size) { val s = seats[(from + k) % seats.size]; if (ok(s)) return s.id }
        return -1
    }

    fun startHand() {
        check(!over)
        hand++
        board.clear(); result = null
        seats.forEach { s ->
            if (s.chips <= 0) s.out = true
            s.hole = emptyList(); s.bet = 0; s.total = 0; s.folded = s.out; s.allIn = false; s.last = ""
        }
        deck = Card.deck().shuffled(rnd).toMutableList()
        dealer = next(dealer.coerceAtLeast(-1).let { if (it < 0) seats.size - 1 else it }) { !it.out }
        val players = inPlay
        // 两个人时庄家下小盲
        val sb = if (players.size == 2) dealer else next(dealer) { !it.out }
        val bb = next(sb) { !it.out }
        players.forEach { it.hole = listOf(deck.removeAt(0), deck.removeAt(0)) }
        log += "—— 第 $hand 手：庄家 ${seats[dealer].name}，小盲 ${seats[sb].name}，大盲 ${seats[bb].name}（$small/$big）"
        street = 0
        currentBet = 0
        post(seats[sb], small, "小盲")
        post(seats[bb], big, "大盲")
        currentBet = big
        minRaise = big
        pending.clear(); pending += players.filter { !it.allIn }.map { it.id }
        toAct = next(bb) { it.id in pending }
        settleIfDone()
    }

    private fun post(s: Seat, amount: Int, what: String) {
        val a = minOf(amount, s.chips)
        s.chips -= a; s.bet += a; s.total += a
        if (s.chips == 0) s.allIn = true
        s.last = "$what $a"
    }

    fun legal(id: Int): Legal {
        val s = seats[id]
        val toCall = (currentBet - s.bet).coerceAtMost(s.chips)
        val maxTo = s.bet + s.chips
        return Legal(toCall, (currentBet + minRaise).coerceAtMost(maxTo + 1), maxTo)
    }

    /** 当前该行动的人做一个动作；不合规的动作按最接近的合规动作处理（不够加注的当全下，不能过牌的当跟注）。 */
    fun act(a: Act) {
        val id = toAct
        check(id >= 0)
        val s = seats[id]
        val l = legal(id)
        val fixed = when (a) {
            Act.Check -> if (l.canCheck) a else Act.Call
            is Act.Raise -> when {
                a.to >= l.maxRaiseTo -> Act.AllIn
                !l.canRaise -> if (l.toCall >= s.chips) Act.AllIn else Act.Call
                a.to < l.minRaiseTo -> Act.Raise(l.minRaiseTo)
                else -> a
            }
            Act.Call -> if (l.toCall == 0) Act.Check else a
            else -> a
        }
        pending -= id
        when (fixed) {
            Act.Fold -> { s.folded = true; s.last = "弃牌"; log += "${s.name} 弃牌" }
            Act.Check -> { s.last = "过牌"; log += "${s.name} 过牌" }
            Act.Call -> {
                val c = l.toCall
                s.chips -= c; s.bet += c; s.total += c
                if (s.chips == 0) s.allIn = true
                s.last = if (s.allIn) "全下 ${s.bet}" else "跟注 ${s.bet}"
                log += "${s.name} ${if (s.allIn) "跟注全下" else "跟注"}到 ${s.bet}"
            }
            is Act.Raise, Act.AllIn -> {
                val to = if (fixed is Act.Raise) fixed.to else l.maxRaiseTo
                val add = to - s.bet
                s.chips -= add; s.bet = to; s.total += add
                if (s.chips == 0) s.allIn = true
                // 这条街还没人下过注：叫「下注」，有人下过才叫「加注」
                val verb = if (currentBet == 0) "下注" else "加注"
                if (to > currentBet) {
                    // 加够了一个完整加注才重新打开下注；全下不够的只让大家补齐
                    if (to - currentBet >= minRaise) minRaise = to - currentBet
                    currentBet = to
                    pending.clear(); pending += seats.filter { it.live && !it.allIn && it.id != id }.map { it.id }
                }
                s.last = if (s.allIn) "全下 $to" else if (verb == "下注") "下注 $to" else "加注到 $to"
                log += if (s.allIn) "${s.name} 全下到 $to" else if (verb == "下注") "${s.name} 下注 $to" else "${s.name} 加注到 $to"
            }
        }
        toAct = next(id) { it.id in pending }
        settleIfDone()
    }

    /** 这一轮下完了就过街；只剩一个人就直接赢；没人能再下注就发完公共牌比牌。 */
    private fun settleIfDone() {
        val live = seats.filter { it.live }
        if (live.size == 1) { finish(); return }
        pending.removeAll { seats[it].folded || seats[it].allIn }
        if (pending.isNotEmpty()) { if (toAct !in pending) toAct = next(toAct) { it.id in pending }; return }
        // 这一轮结束
        seats.forEach { it.bet = 0 }
        currentBet = 0; minRaise = big
        if (street == 3) { finish(); return }
        street++
        val deal = if (street == 1) 3 else 1
        deck.removeAt(0) // 烧一张
        repeat(deal) { board += deck.removeAt(0) }
        log += listOf("翻牌", "转牌", "河牌")[street - 1] + "：" + board.joinToString(" ")
        val canBet = live.filter { !it.allIn }
        if (canBet.size <= 1) { settleIfDone(); return }
        pending.clear(); pending += canBet.map { it.id }
        toAct = next(dealer) { it.id in pending }
    }

    private fun finish() {
        toAct = -1
        val live = seats.filter { it.live }
        val won = mutableMapOf<Int, Int>()
        val shown = mutableMapOf<Int, String>()
        if (live.size == 1) {
            won[live[0].id] = pot
        } else {
            // 没发完的公共牌补齐（大家都全下了）
            while (board.size < 5) board += deck.removeAt(0)
            val score = live.associate { it.id to HandEval.best(it.hole + board) }
            live.forEach { shown[it.id] = it.hole.joinToString(" ") + " " + HandEval.name(score.getValue(it.id)) }
            // 边池：按每个人下的总数一层层分，每层只有下够这层的人能赢
            val levels = live.map { it.total }.distinct().sorted()
            var prev = 0
            for (lv in levels) {
                val layer = seats.sumOf { minOf(it.total, lv) - minOf(it.total, prev) }
                val eligible = live.filter { it.total >= lv }
                val top = eligible.maxOf { score.getValue(it.id) }
                val winners = eligible.filter { score.getValue(it.id) == top }.sortedBy { (it.id - dealer - 1 + seats.size) % seats.size }
                val each = layer / winners.size
                winners.forEachIndexed { i, w -> won[w.id] = won.getOrDefault(w.id, 0) + each + if (i == 0) layer % winners.size else 0 }
                prev = lv
            }
            // 比牌时没人下到的那部分（弃牌的人多下的）给最大那层的赢家
            val rest = pot - won.values.sum()
            if (rest > 0) { val w = won.keys.first(); won[w] = won.getValue(w) + rest }
        }
        won.forEach { (id, v) -> seats[id].chips += v }
        val text = won.entries.joinToString("，") { (id, v) -> "${seats[id].name} 赢 $v" + (shown[id]?.let { "（${it.substringAfterLast(' ')}）" } ?: "") }
        if (shown.isNotEmpty()) log += "亮牌：" + shown.entries.joinToString("；") { (id, h) -> "${seats[id].name} $h" }
        log += "结果：$text"
        seats.forEach { it.total = 0 }
        result = HandResult(won, shown, text)
    }
}
