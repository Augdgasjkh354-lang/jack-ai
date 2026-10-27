package com.jack.ai.tools

import com.jack.ai.data.FreeTime
import com.jack.ai.data.Reader
import com.jack.ai.data.Wish
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自由时间里 Kavi 自己的事（free_time 工具，只有自由时间里有）：
 * status 看钱、心愿、下次时间；use_savings 从积蓄拿钱；next 自己约下一次；wish 跟用户要一笔预算做某件事；
 * buy 想买个东西（Kavi 不能付钱：告诉用户买什么、多少钱、愿意从积蓄出多少，用户决定）。
 */
internal fun ToolSession.freeTimeTool(args: JsonObject): String {
    val b = freeBudget ?: return "现在不是自由时间，没有这个。"
    fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
    fun money(v: Double) = FreeTime.money(v)
    val ctx = app
    val action = args["action"].text()
    succeeded.incrementAndGet()
    return when (action) {
        "use_savings" -> {
            val want = args.num("amount") ?: return "要给出 amount（拿多少，元）。"
            val got = b.use(want)
            if (got <= 0) "积蓄是 ${money(b.savings)}，拿不出来。"
            else "从积蓄里拿了 ${money(got)}，这次能花到 ${money(b.soft)}（已花 ${money(b.spentNow())}），积蓄还剩 ${money(b.savings)}。没花完的结束时退回积蓄。"
        }
        "next" -> {
            val h = args.num("hours")?.coerceIn(0.5, 48.0) ?: return "要给出 hours（多少小时以后，0.5 到 48）。"
            val at = System.currentTimeMillis() + (h * 3_600_000).toLong()
            Reader.scheduleExtra(ctx, at)
            FreeTime.save(ctx, FreeTime.load(ctx).copy(nextAt = at))
            "约好了：" + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(at)) +
                " 再来一次（花的还是那天的预算和你的积蓄）。每天凌晨那次照旧，不想来到时候随便待一下就走也行。"
        }
        "wish", "buy" -> {
            val text = args["text"].text().trim().take(300)
            if (text.length < 4) return "要给出 text：想做什么 / 想买什么，说清楚一点。"
            val st = FreeTime.load(ctx)
            if (st.wishes.count { it.status == "pending" } >= 3) return "已经有 3 个心愿在等回音了，等回了再提新的。"
            val wish = if (action == "buy") {
                val price = args.num("price")?.takeIf { it > 0 } ?: return "要给出 price（大概多少钱，元）。"
                val url = args["url"].text().trim()
                if (url.isNotEmpty() && Sources.unknown(listOf(url), seenUrls).isNotEmpty()) return "这个链接这次没打开过。先读一下那个页面，确认东西和价格再提。"
                val offer = (args.num("offer") ?: 0.0).coerceIn(0.0, minOf(price, b.savings))
                Wish(kind = "buy", text = text, amount = price, offer = offer, url = url)
            } else {
                val amount = args.num("amount")?.coerceIn(0.05, 20.0) ?: return "要给出 amount（需要多少预算，元）。"
                Wish(kind = "budget", text = text, amount = amount)
            }
            FreeTime.save(ctx, st.copy(wishes = st.wishes + wish))
            Reader.notifyWish(ctx)
            "已经告诉 ${com.jack.ai.data.Persona.name(settings).ifEmpty { "用户" }} 了（白天会通知，不会半夜吵醒）。回了之后，下次自由时间你会知道结果。"
        }
        else -> {
            val st = FreeTime.load(ctx)
            "这次已花 ${money(b.spentNow())}；今天的预算到 ${money(b.today)}" + (if (b.fromSavings > 0) "，加上从积蓄拿的 ${money(b.fromSavings)}" else "") +
                "；积蓄还有 ${money(b.savings)}；收尾用的额外预算 ${money(b.extra)}。" +
                (st.wishes.filter { it.status == "pending" }.takeIf { it.isNotEmpty() }?.let { w -> "\n在等回音的心愿：" + w.joinToString("；") { FreeTime.describe(it) } } ?: "") +
                (if (st.nextAt > System.currentTimeMillis()) "\n你约的下一次：" + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(st.nextAt)) else "")
        }
    }
}

/**
 * 结束当前对话（极端辱骂时的最后一招）。代码这边只拦最明显的误用：对话里至少要有过几句话（先劝过一次才轮得到它），
 * 什么时候算极端交给提示词里的规矩。结束后不再给工具，模型平静地说一声就收尾。
 */
internal fun ToolSession.endConversation(args: JsonObject): String {
    val reason = args["reason"].text().trim().take(120)
    if (reason.length < 2) return "要写一句为什么结束（会显示给对方看）。"
    if (conv.messages.count { it.role == "user" } < 3) return "这个对话才刚开始，不能就这样结束。先明确请对方停下；对方坚持极端辱骂再说。"
    succeeded.incrementAndGet()
    ui.endConversation(reason)
    maxRounds = 0
    stopNote = "这个对话已经结束了，不要再调用工具。"
    return "这个对话已经结束（之后不能再发消息）。最后平静地说一两句：你结束了这个对话、为什么，对方可以开一个新对话。不指责，不说教。"
}
