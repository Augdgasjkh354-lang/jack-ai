package com.jack.ai.tools

import com.jack.ai.data.Rail12306
import com.jack.ai.data.SearchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 火车票：查 12306 余票。只查不买。 */
internal suspend fun ToolSession.queryTrains(args: JsonObject): String {
    val from = args["from"].text().trim()
    val to = args["to"].text().trim()
    val date = args["date"].text().trim()
    if (from.isEmpty() || to.isEmpty() || date.isEmpty()) return "要填出发地 from、目的地 to 和日期 date"
    return runTool("查火车票：$from → $to $date") {
        val text = withContext(Dispatchers.IO) { trainText(app.filesDir, args) }
        text to SearchRecord("查火车票：$from → $to $date")
    }
}

/** 查票并按条件筛好，返回给模型看的文字。后台监控也用它。 */
internal fun trainText(dir: java.io.File, args: JsonObject): String {
    val date = args["date"].text().trim()
    if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) throw Rail12306.RailException("date 要写成 yyyy-MM-dd，比如 2026-11-01")
    val (route, all) = Rail12306.query(dir, date, args["from"].text(), args["to"].text())
    // 车型：G 高铁、D 动车、C 城际、Z/T/K 普速……写了就只留这几种
    val types = args["types"].text().uppercase().filter { it.isLetter() }.toSet()
    val after = args["depart_after"].text().trim().takeIf { it.matches(Regex("""\d{1,2}:\d{2}""")) }?.padStart(5, '0')
    val before = args["depart_before"].text().trim().takeIf { it.matches(Regex("""\d{1,2}:\d{2}""")) }?.padStart(5, '0')
    val trains = all.filter { t ->
        (types.isEmpty() || t.code.first().uppercaseChar() in types || (t.code.first().isDigit() && 'K' in types)) &&
            (after == null || t.depart >= after) && (before == null || t.depart <= before)
    }
    if (trains.isEmpty()) {
        return "$date $route：" + (if (all.isEmpty()) "12306 上没有这天的车次（可能还没开售，一般提前 15 天开售）。" else "共 ${all.size} 趟车，但没有符合条件的。")
    }
    val shown = trains.take(60)
    return "$date $route，符合条件的 ${trains.size} 趟（余票：有＝充足，数字＝剩几张，无＝卖完）：\n" +
        shown.joinToString("\n") { Rail12306.describe(it) } +
        (if (trains.size > shown.size) "\n……还有 ${trains.size - shown.size} 趟没列出，可以用 types、depart_after、depart_before 缩小范围" else "") +
        "\n购票请用户自己在 12306 App 里买。"
}
