package com.jack.ai.data.games

import android.content.Context
import com.jack.ai.data.Settings
import com.jack.ai.data.endpoint
import com.jack.ai.data.modelLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

/** 一个对手：名字、性格、用哪个模型（空的是规则机器人，不花钱）。 */
data class Opponent(val name: String, val style: String, val model: String = "")

/**
 * 一桌德州扑克：你和 4 个对手。牌局在 [Holdem] 里跑，对手轮到时问各自的模型（[GameMind]，上下文各自独立），
 * 模型出错、回得不对就按规则机器人的走法来，牌局不会卡住。
 * 放在单例里：离开页面再回来，牌局还在。
 */
class PokerTable(private val ctx: Context, settings: Settings, val opponents: List<Opponent>, startChips: Int = 1000) {
    val game = Holdem(listOf("你" to true) + opponents.map { it.name to false }, startChips)
    // 都在主线程上改牌桌（你点按钮也在主线程），不会两边同时动；问模型的网络请求自己会切到后台
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val minds: Map<Int, GameMind?> = opponents.mapIndexed { i, o ->
        val ep = o.model.takeIf { it.isNotBlank() }?.let { settings.endpoint(it) }?.takeIf { it.apiKey.isNotBlank() }?.copy(fallbacks = emptyList())
        (i + 1) to ep?.let { GameMind(ctx, it, systemFor(o, settings), "游戏 · 德州扑克") }
    }.toMap()
    /** 每个对手读到 log 的哪一条了：下次只发后面新的。 */
    private val cursor = IntArray(game.seats.size)
    /** 对手最近说的话（座位 → 一句）。 */
    val says = mutableMapOf<Int, String>()
    /** 正在想的是谁（-1 没人）。 */
    var thinking = -1
        private set
    private val _tick = MutableStateFlow(0)
    /** 牌桌有变化就加一，界面跟着重画。 */
    val tick: StateFlow<Int> = _tick
    private var loop: Job? = null

    fun modelName(seat: Int): String = minds[seat]?.let { modelLabel(it.ep.model) } ?: "规则机器人"

    private fun bump() { _tick.value++ }

    fun startHand() {
        if (game.over) return
        says.clear()
        game.startHand()
        bump()
        drive()
    }

    /** 你的动作。 */
    fun play(a: Act) {
        val me = game.seats.first { it.human }
        if (game.toAct != me.id) return
        game.act(a)
        bump()
        drive()
    }

    /** 轮到对手就一个个问下去，直到轮到你或这一手结束。 */
    private fun drive() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                val id = game.toAct
                if (id < 0 || game.seats[id].human) break
                thinking = id; bump()
                val started = System.currentTimeMillis()
                val a = decide(id)
                // 规则机器人一下子就出牌，看着太快：至少停 0.6 秒
                (600 - (System.currentTimeMillis() - started)).takeIf { it > 0 }?.let { delay(it) }
                thinking = -1
                if (game.toAct == id) game.act(a)
                bump()
            }
        }
    }

    fun close() { scope.coroutineContext[Job]?.cancel() }

    private suspend fun decide(id: Int): Act {
        val mind = minds[id] ?: return PokerBot.decide(game, id)
        val prompt = situation(id)
        return runCatching {
            val raw = mind.ask(prompt, recap = { recap(id) })
            parse(raw, id)
        }.getOrNull() ?: PokerBot.decide(game, id)
    }

    /** 给对手看的：上次之后桌上发生的事（只给公开的和它自己的手牌），再说现在轮到它、能做什么。 */
    internal fun situation(id: Int): String {
        val s = game.seats[id]
        val news = game.log.drop(cursor[id])
        cursor[id] = game.log.size
        val l = game.legal(id)
        return buildString {
            if (news.isNotEmpty()) append(news.joinToString("\n")).append("\n")
            append("你的手牌：").append(s.hole.joinToString(" "))
            if (game.board.isNotEmpty()) append("；公共牌：").append(game.board.joinToString(" "))
            append("\n底池 ${game.pot}，你的筹码 ${s.chips}，")
            append(if (l.canCheck) "可以过牌" else "跟注要 ${l.toCall}")
            append(if (l.canRaise) "，加注最少到 ${l.minRaiseTo}、最多到 ${l.maxRaiseTo}（全下）" else "，筹码不够加注（只能跟或全下）")
            append("。其他人筹码：").append(game.seats.filter { it.id != id && !it.out }.joinToString("，") { "${it.name} ${it.chips}${if (it.folded) "（已弃）" else ""}" })
            append("\n轮到你。")
        }
    }

    private fun recap(id: Int) = "已经打了 ${game.hand} 手。现在筹码：" + game.seats.filter { !it.out }.joinToString("，") { "${it.name} ${it.chips}" }

    /** 模型回的 {"a": "...", "to": 数, "say": "..."} → 动作；看不懂返回 null。 */
    internal fun parse(raw: String, id: Int): Act? {
        val o = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty().trim()
        str("say").take(24).takeIf { it.isNotBlank() }?.let { says[id] = it }
        val to = str("to").toDoubleOrNull()?.toInt()
        return when (str("a").lowercase()) {
            "fold" -> Act.Fold
            "check" -> Act.Check
            "call" -> Act.Call
            "raise", "bet" -> to?.let { Act.Raise(it) } ?: Act.Raise(game.legal(id).minRaiseTo)
            "allin", "all-in", "all_in" -> Act.AllIn
            else -> null
        }
    }

    companion object {
        /** 现在这一桌（离开页面再回来还在；结束牌局时清掉）。 */
        @Volatile var current: PokerTable? = null

        val STYLES = listOf("稳健，只玩好牌", "激进，爱加注施压", "爱诈唬，时不时空手偷池", "冷静计算赔率")
        val NAMES = listOf("阿杰", "小美", "老K", "Kavi")

        private fun systemFor(o: Opponent, s: Settings) = """你在打一桌无限注德州扑克（现金局，盲注 10/20），你是「${o.name}」，打法：${o.style}。桌上一共 5 个人，你只知道自己的手牌和公开发生的事。
每次我会告诉你上次之后桌上发生了什么、你的手牌、底池和你能做什么。照你的打法想清楚，但只回一个 JSON，不要别的字：
{"a": "fold|check|call|raise|allin", "to": 加注时这一轮总共下到多少（整数，别的动作不写）, "say": "可选，一句很短的牌桌台词，不超过 12 个字，不要透露手牌"}
不能过牌时别选 check；加注额在给的范围内。台词偶尔说就好，多数时候留空。"""
    }
}

/** 规则机器人：按牌力和要跟的钱粗算，带一点随机；模型出错时也用它兜底。 */
object PokerBot {
    fun decide(g: Holdem, id: Int, rnd: Random = Random.Default): Act {
        val s = g.seats[id]
        val l = g.legal(id)
        val strength = if (g.board.isEmpty()) preflop(s.hole) else post(s.hole, g.board)
        val price = if (l.toCall == 0) 0.0 else l.toCall.toDouble() / (g.pot + l.toCall)
        val r = rnd.nextDouble()
        return when {
            strength > 0.8 && l.canRaise && r < 0.7 -> Act.Raise((l.minRaiseTo + g.pot / 2).coerceAtMost(l.maxRaiseTo))
            strength > 0.55 && l.canRaise && r < 0.25 -> Act.Raise(l.minRaiseTo)
            l.canCheck -> if (r < 0.08 && l.canRaise) Act.Raise(l.minRaiseTo) else Act.Check
            strength >= price + 0.15 || (strength > 0.35 && price < 0.2) -> Act.Call
            r < 0.05 -> Act.Call
            else -> Act.Fold
        }
    }

    /** 翻牌前：对子、大牌、同花连张越好越高（0～1）。 */
    internal fun preflop(h: List<Card>): Double {
        val (a, b) = h.sortedByDescending { it.rank }
        var v = (a.rank + b.rank - 4) / 24.0 * 0.6
        if (a.rank == b.rank) v += 0.35 + a.rank / 14.0 * 0.15
        if (a.suit == b.suit) v += 0.06
        if (a.rank - b.rank == 1) v += 0.04
        return v.coerceIn(0.0, 1.0)
    }

    /** 翻牌后：按成了什么牌型算。 */
    internal fun post(h: List<Card>, board: List<Card>): Double {
        val cat = (HandEval.best(h + board) shr 20).toInt()
        // 公共牌自己就有的牌型不算自己的
        val boardCat = if (board.size >= 5) (HandEval.best(board) shr 20).toInt() else 0
        val mine = if (cat == boardCat && board.size >= 5) 0 else cat
        return listOf(0.15, 0.45, 0.65, 0.75, 0.82, 0.86, 0.92, 0.97, 0.99)[mine]
    }
}
