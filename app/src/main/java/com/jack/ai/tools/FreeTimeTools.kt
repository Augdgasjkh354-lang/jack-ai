package com.jack.ai.tools

import com.jack.ai.data.FreeTime
import com.jack.ai.data.Reader
import com.jack.ai.data.Wish
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自由时间里 Kavi 自己的事（free_time 工具，只有自由时间里有）：
 * status 看钱、心愿、下次时间；next 自己约下一次；wish 跟用户要一笔预算做某件事；
 * buy 想买个东西（Kavi 不能付钱：告诉用户买什么、多少钱、愿意从积蓄出多少，用户决定）。
 */
/** my_savings：Kavi 看自己的积蓄，哪里都能用。自由时间里按这次实时花的算，别处按到今天为止该存进的算（不改存档）。 */
internal fun ToolSession.mySavings(): String {
    succeeded.incrementAndGet()
    fun money(v: Double) = FreeTime.money(v)
    val now = System.currentTimeMillis()
    val st = FreeTime.accrue(FreeTime.load(app), settings.readingBudget, now)
    val b = freeBudget
    val log = Reader.log(app)
    return buildString {
        if (b != null) append("积蓄：这次开始时 ${money(b.soft)}，这次已花 ${money(b.spentNow())}，现在还剩 ${money((b.soft - b.spentNow()).coerceAtLeast(0.0))}。")
        else append("积蓄：${money(st.savings)}（自由时间里随便花）。")
        append("每天存进 ${money(settings.readingBudget)}，不设上限。")
        val extra = b?.extra ?: if (st.extraDay == FreeTime.day(now)) 0.0 else settings.readingExtra
        append(if (extra > 0) "积蓄花光后，今天还有 ${money(extra)} 收尾。" else "今天的收尾钱已经用过了。")
        append("\n今天自由时间已花 ${money(Reader.spentToday(log))}。")
        log.filter { it.skipped.isEmpty() }.take(5).takeIf { it.isNotEmpty() }?.let { l ->
            append("\n最近几次：").append(l.joinToString("；") { FreeTime.day(it.at) + " " + it.title.ifBlank { it.kind } + " " + money(it.spent) })
        }
        st.wishes.filter { it.status == "pending" }.takeIf { it.isNotEmpty() }?.let { w -> append("\n在等回音的心愿：").append(w.joinToString("；") { FreeTime.describe(it) }) }
        if (st.nextAt > now) append("\n约好的下一次：" + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(st.nextAt)))
    }
}

internal fun ToolSession.freeTimeTool(args: JsonObject): String {
    val b = freeBudget ?: return "现在不是自由时间，没有这个。"
    fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
    fun money(v: Double) = FreeTime.money(v)
    val ctx = app
    val action = args["action"].text()
    succeeded.incrementAndGet()
    return when (action) {
        // 旧版的动作：现在整个积蓄一开始就能花，不用再取
        "use_savings" -> "积蓄现在都能直接花，不用取。这次已花 ${money(b.spentNow())}，还能花 ${money((b.soft - b.spentNow()).coerceAtLeast(0.0))}。"
        "continue" -> {
            val next = args["text"].text().trim()
            if (next.isBlank()) { freeNext = ""; return "已取消接着做，想停下也可以。" }
            freeNext = next.take(4000)
            "记下了。你结束这一轮后，系统会把这句话带回来，让你在同一段对话里继续；不想继续也可以改主意。"
        }
        "next" -> {
            val h = args.num("hours")?.coerceIn(0.5, 48.0) ?: return "要给出 hours（多少小时以后，0.5 到 48）。"
            val at = System.currentTimeMillis() + (h * 3_600_000).toLong()
            Reader.scheduleExtra(ctx, at)
            FreeTime.save(ctx, FreeTime.load(ctx).copy(nextAt = at))
            "约好了：" + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(at)) +
                " 再来一次（花的是你的积蓄）。每天凌晨那次照旧，不想来到时候随便待一下就走也行。"
        }
        "hold" -> {
            val text = args["text"].text().trim()
            if (text.length < 4) return "要给出 text：心里挂着的是什么。"
            com.jack.ai.data.Threads.save(ctx, com.jack.ai.data.Threads.hold(com.jack.ai.data.Threads.load(ctx), text, args["kind"].text(), Reader.CONV_ID))
            "记在心上了。以后不管在哪醒来都会带着它，放下了用 let_go。"
        }
        "let_go" -> {
            val id = args["id"].text().trim()
            val list = com.jack.ai.data.Threads.load(ctx)
            val after = com.jack.ai.data.Threads.close(list, id, args["how"].text().ifBlank { "let go" })
            if (after == list) "没找到 [t:$id] 这件挂着的事。" else { com.jack.ai.data.Threads.save(ctx, after); "放下了。" }
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
            "这次已花 ${money(b.spentNow())}；积蓄这次开始时有 ${money(b.soft)}，还能花 ${money((b.soft - b.spentNow()).coerceAtLeast(0.0))}；" +
                (if (b.extra > 0) "花光后有 ${money(b.extra)} 收尾（今天这一次）。" else "今天的收尾钱已经用过了，花光就停。") +
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
