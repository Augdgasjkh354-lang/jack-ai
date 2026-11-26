package com.jack.ai.tools

import com.jack.ai.a11y.Element
import com.jack.ai.a11y.KaviAccessibility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手机上的「对列表里每一条做同一件事」（phone action=each）：模型想一次，Kavi 自己一条条做完，最后交一份摘要。
 *
 * 以前逐条做是「看一屏 → 点开 → 做 → 返回 → 再看一屏」，每条都要问一次模型、每次重发整段对话，越做越贵
 * （Mika 的真实记录：看 3 个广告领奖励用了 35 轮，输入从 1.5 万涨到 14.8 万 token）。
 *
 * 思路来自 Mika 的 do_each（EachCursor / ScrollCursor / EachLoopDetector），Apache-2.0；这里是照 Kavi 的手机工具重写的：
 * 条目按「类型 + 文字」认（编号每次读屏都会变，不能拿来认同一条），body 就是 steps 那样的一串动作，
 * 里面的 "@item" 换成这一条的编号；每步照单独调用走（该问用户的照样问、节奏照样等）。
 */
internal object PhoneEach {
    const val MAX_BODY = 20
    const val DEFAULT_ITEMS = 20
    const val MAX_ITEMS = 50
    const val MAX_SCROLLS = 30

    const val NUDGE = "（提醒：你在对列表里的条目一条一条地做同样的事。剩下的用 action=each 一次做完：item 写怎么认出一条（text 填每条都有的字，或 role 填类型），" +
        "body 写一条要做的动作（点这一条用 {\"action\":\"tap\",\"text\":\"@item\"}，点开了最后要 back 回列表），Kavi 会自己一条条做、往下翻找更多，最后交一份摘要。）"

    /** 认条目：类型对得上、文字里含着要的字。两样都没给就不认。 */
    class Matcher(val role: String?, val text: String?) {
        fun matches(e: Element): Boolean =
            e.role != "列表" && e.label.isNotBlank() &&
                (role == null || e.role == role) && (text == null || e.label.contains(text, ignoreCase = true))
        fun key(e: Element) = e.role + "|" + e.label
    }

    /**
     * 游标：跨多次读屏、翻页，把每一条（按「类型|文字」认）只做一遍；刚翻过一页却没冒出新的条目，就是到底了。
     * 同名的两条会被当成一条（元素本身分不出来），item 写细一点能避开。
     */
    class Cursor(private val m: Matcher) {
        val seen = mutableSetOf<String>()
        val done = mutableSetOf<String>()

        sealed class Step {
            data class Do(val e: Element) : Step()
            object Scroll : Step()
            object End : Step()
        }

        fun advance(elements: List<Element>, justScrolled: Boolean, reveal: Boolean): Step {
            val items = elements.filter(m::matches)
            val fresh = items.map(m::key).filter { it !in seen }
            seen += items.map(m::key)
            items.firstOrNull { m.key(it) !in done }?.let { return Step.Do(it) }
            return if (!reveal || (justScrolled && fresh.isEmpty())) Step.End else Step.Scroll
        }

        fun finish(e: Element) { done += m.key(e) }
    }

    /** body 里的 "@item" 换成这一条的编号（tap、long_press 的 text，或 n 直接写 "@item"）。 */
    fun resolve(body: List<JsonObject>, e: Element): List<JsonObject> = body.map { st ->
        val isItem = (st["text"] as? JsonPrimitive)?.content == "@item" || (st["n"] as? JsonPrimitive)?.content == "@item"
        if (!isItem) st else buildJsonObject {
            st.forEach { (k, v) -> if (k != "text" && k != "n") put(k, v) }
            put("n", e.n)
        }
    }

    /**
     * 认模型自己一条条做：最近几次动作按「点开 → 做 → 返回」的样子（一个周期里恰好一次 back、至少一次 tap）连着重复三遍，
     * 就提醒一次 each。失败的、别的动作会打断；用过 each 就不再提醒。
     */
    class RepeatDetector {
        private val seq = ArrayDeque<String>()
        private var fired = false
        private var disarmed = false

        fun feed(action: String, ok: Boolean): Boolean = synchronized(this) {
            if (action == "each") disarmed = true
            if (!ok || action !in COUNTED) { seq.clear(); return false }
            seq.addLast(action)
            while (seq.size > REPEATS * MAX_MOTIF) seq.removeFirst()
            if (fired || disarmed || !repeating()) return false
            fired = true
            true
        }

        private fun repeating(): Boolean {
            for (l in 2..MAX_MOTIF) {
                if (seq.size < REPEATS * l) continue
                val window = seq.toList().takeLast(REPEATS * l)
                val motif = window.take(l)
                if (window.chunked(l).any { it != motif }) continue
                if (motif.count { it == "back" } == 1 && "tap" in motif) return true
            }
            return false
        }

        companion object {
            const val REPEATS = 3
            const val MAX_MOTIF = 5
            private val COUNTED = setOf("read", "tap", "long_press", "type", "enter", "scroll", "swipe", "back", "wait", "steps")
        }
    }
}

/** phone action=each：见 [PhoneEach]。 */
internal suspend fun ToolSession.phoneEach(args: JsonObject): String {
    val item = args["item"] as? JsonObject
        ?: return "each 要填 item（怎么认出一条：{\"text\":\"每条都有的字\"} 或 {\"role\":\"按钮\"}）和 body（一条要做的动作）。"
    val role = (item["role"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    val text = (item["text"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    if (role == null && text == null) return "item 至少填 text（每条都有的字）或 role（清单里的类型，比如 按钮）。"
    val body = (args["body"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (body.isEmpty()) return "body 要填一条要做的动作，比如 [{\"action\":\"tap\",\"text\":\"@item\"},{\"action\":\"tap\",\"text\":\"领取\"},{\"action\":\"back\"}]。"
    if (body.size > PhoneEach.MAX_BODY) return "body 最多 ${PhoneEach.MAX_BODY} 步。"
    stepsProblem(body)?.let { return "body 里$it" }
    val skip = args["on_error"].text() == "skip"
    val reveal = args["reveal"].text() != "none"
    val maxItems = args["max_items"].text().toIntOrNull()?.coerceIn(1, PhoneEach.MAX_ITEMS) ?: PhoneEach.DEFAULT_ITEMS

    val m = PhoneEach.Matcher(role, text)
    val cursor = PhoneEach.Cursor(m)
    val per = mutableListOf<String>()
    var ok = 0
    var failed = 0
    var scrolls = 0
    var justScrolled = false
    var why = ""
    phoneBatchDepth++
    try {
        while (true) {
            if (ok + failed >= maxItems) { why = "做满 $maxItems 条（max_items）"; break }
            if (phoneStops >= 0 && KaviAccessibility.stops.get() != phoneStops) { why = "用户按了停止"; break }
            val limit = phoneLimitStop
            if (limit != null) { why = limit; break }
            val screenText = phone(buildJsonObject { put("action", "read") })
            val s = phoneSeen
            if (!phoneStepOk || s == null) { why = screenText.lines().firstOrNull().orEmpty().take(120).ifBlank { "读不到屏幕" }; break }
            // 做完一条却回不到列表：body 多半少了 back，别在详情页里瞎翻
            if (ok + failed > 0 && s.elements.none(m::matches) && !justScrolled) { why = "做完一条后屏幕上找不到条目了（body 最后要 back 回到列表）"; break }
            when (val step = cursor.advance(s.elements, justScrolled, reveal)) {
                PhoneEach.Cursor.Step.End -> { why = if (reveal) "到底了，没有新的条目" else "这一屏的都做完了"; break }
                PhoneEach.Cursor.Step.Scroll -> {
                    if (++scrolls > PhoneEach.MAX_SCROLLS) { why = "翻了 ${PhoneEach.MAX_SCROLLS} 次还没到底，先停下"; break }
                    phone(buildJsonObject { put("action", "scroll") })
                    if (!phoneStepOk) { why = "翻不动了"; break }
                    justScrolled = true
                }
                is PhoneEach.Cursor.Step.Do -> {
                    justScrolled = false
                    val e = step.e
                    val r = runPhoneSteps(PhoneEach.resolve(body, e))
                    cursor.finish(e)
                    val name = e.label.replace('\n', ' ').take(30)
                    if (r.ok) { ok++; per += "✓ $name" }
                    else {
                        failed++
                        per += "✗ $name：" + (r.lines.lastOrNull()?.substringAfter("：", "")?.take(80)?.ifBlank { null } ?: r.stop)
                        if (!skip) { why = "「$name」没做成，按 on_error=stop 停下"; break }
                    }
                }
            }
        }
    } finally { phoneBatchDepth-- }
    phoneRepeats.feed("each", true)
    // 停下时交一次当前屏幕（循环里最后读的那屏可能已经过时）
    val now = phone(buildJsonObject { put("action", "read") })
    val screen = now.indexOf("【手机屏幕】").takeIf { it >= 0 }?.let { now.substring(it) } ?: now
    return "逐条做了 ${ok + failed} 条（做成 $ok" + (if (failed > 0) "，没做成 $failed" else "") + "；停下：$why）：\n" +
        per.joinToString("\n").ifBlank { "（一条都没认到：item 的 text / role 和屏幕清单对不上，先 read 看看条目长什么样）" } + "\n\n" + screen
}
