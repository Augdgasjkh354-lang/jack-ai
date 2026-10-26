package com.jack.ai.tools

import android.content.Intent
import android.provider.AlarmClock
import com.jack.ai.api.formatResultsForModel
import com.jack.ai.api.toSources
import com.jack.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间相关：闹钟、提醒、定时任务（对模型只露出 schedule / cancel_schedule）、日历。 */

internal suspend fun ToolSession.setAlarm(args: JsonObject): String {
    val time = args["time"].text()
    return runTool("设闹钟 $time") {
        val m = ToolSession.TIME.find(time) ?: throw IllegalArgumentException("时间要写成 HH:mm")
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) throw IllegalArgumentException("时间不对")
        val days = (args["days"] as? JsonArray).orEmpty().mapNotNull { it.text().toIntOrNull() }.filter { it in 1..7 }.distinct()
        val label = args["label"].text()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        // 1=周一 … 7=周日 → Calendar 的 2…7、1
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it % 7 + 1 }))
        startClock(intent)
        val hm = String.format(Locale.US, "%02d:%02d", h, min)
        val repeat = if (days.isEmpty()) "一次性" else "每周" + days.sorted().joinToString("、") { "一二三四五六日"[it - 1].toString() } + "重复"
        // 回读：系统只告诉「下一个要响的闹钟」是几点，拿它和这个闹钟下一次该响的时间对一对
        kotlinx.coroutines.delay(1500)
        val next = runCatching { app.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()
        val check = alarmCheck(nextAlarmAt(System.currentTimeMillis(), h, min, days), next)
        "已交给手机时钟设 $hm 的闹钟（$repeat${if (label.isNotBlank()) "，标签「$label」" else ""}）。$check" to
            SearchRecord("设闹钟 $hm")
    }
}

/** 这个闹钟下一次该在什么时候响（days：1=周一 … 7=周日，空 = 一次性）。 */
internal fun nextAlarmAt(now: Long, h: Int, min: Int, days: List<Int>): Long {
    val c = java.util.Calendar.getInstance().apply {
        timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    repeat(8) {
        // Calendar：周日 1 … 周六 7 → 1=周一 … 7=周日
        val dow = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        if (c.timeInMillis > now && (days.isEmpty() || dow in days)) return c.timeInMillis
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return c.timeInMillis
}

/** 拿系统的「下一个闹钟」核对：对得上 ✓；系统里有更早的闹钟就没法确认（不算失败）；没有或更晚算没设上。 */
internal fun alarmCheck(expected: Long, next: Long?): String {
    val f = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
    return when {
        next != null && kotlin.math.abs(next - expected) < 60_000 -> Verify.note(emptyList(), "系统的下一个闹钟就是 ${f.format(Date(expected))}，设上了")
        next != null && next < expected -> "\n【核实】系统里还有一个更早的闹钟（${f.format(Date(next))}），这个没法直接确认；要确认就请用户打开时钟看一眼。"
        else -> Verify.note(listOf("系统里查不到 ${f.format(Date(expected))} 要响的闹钟" + (next?.let { "（下一个闹钟是 ${f.format(Date(it))}）" } ?: "（现在一个闹钟都没有）") +
            "，可能时钟 App 没接住"), "", "请用户打开时钟看一眼，没有就手动设，或者改用 schedule 设 Jack 自己的提醒")
    }
}

internal suspend fun ToolSession.cancelAlarm(args: JsonObject): String {
    val time = args["time"].text()
    val label = args["label"].text()
    return runTool("关闹钟 ${time.ifBlank { label }}") {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val m = ToolSession.TIME.find(time)
        when {
            m != null -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, m.groupValues[1].toInt())
                .putExtra(AlarmClock.EXTRA_MINUTES, m.groupValues[2].toInt())
            label.isNotBlank() -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            else -> throw IllegalArgumentException("要给出闹钟的时间或标签")
        }
        startClock(intent)
        "已让时钟 App 关掉这个闹钟。有的手机会弹出确认页面，需要用户点一下。" to SearchRecord("关闹钟 ${time.ifBlank { label }}")
    }
}

internal suspend fun ToolSession.makeRoutine(kind: String, args: JsonObject): String {
    val label = if (kind == "reminder") "设置提醒" else "设置定时任务"
    return runTool("$label：" + (if (kind == "reminder") args["text"] else args["title"]).text()) {
        val (date, h, m) = parseDateTime(args["datetime"].text())
            ?: throw IllegalArgumentException("时间格式不对，要写成 yyyy-MM-dd HH:mm")
        val asked = args["repeat"].text().ifBlank { "none" }
        // 不认识的重复方式别悄悄当成只一次：用户以为每月都会提醒，结果只响一次
        if (asked !in REPEAT_LABELS) throw IllegalArgumentException(
            "提醒和任务的 repeat 只支持 none、daily、weekdays、weekly。每月、每年重复的事用日历（add_calendar_event 的 repeat），或者告诉用户这里设不了")
        val repeat = asked
        val r = if (kind == "reminder") Routine(kind = "reminder", title = args["text"].text(), hour = h, minute = m, repeat = repeat, date = date)
        else Routine(kind = "task", title = args["title"].text(), prompt = args["prompt"].text(), hour = h, minute = m, repeat = repeat, date = date,
            offPeak = args["off_peak"].text() == "true", work = args["work"].text() == "true")
        if (r.nextAt() == null) throw IllegalArgumentException("这个时间已经过去了")
        withContext(Dispatchers.Main) { host.saveRoutine(r) }
        val warn = if (!Routines.canNotify(app)) "（注意：App 还没有通知权限，用户需要打开左侧菜单「提醒和任务」页面授权）" else ""
        "已设置：${r.describe()}，${if (kind == "reminder") "提醒" else "任务"}「${r.title}」。编号 ${r.id.take(8)}。$warn" + verifySaved(r.id) to
            SearchRecord("$label：${r.title}")
    }
}

internal suspend fun ToolSession.listRoutines() = runTool("查看提醒和任务") {
    val list = host.store.loadRoutines()
    (if (list.isEmpty()) "用户还没有任何提醒和定时任务。" else list.joinToString("\n") { r ->
        "编号 ${r.id.take(8)}｜${when (r.kind) { "reminder" -> "提醒"; "watch" -> "监控"; else -> "任务" }}｜${r.describe()}｜${r.title}" +
            (if (r.enabled) "" else "｜已结束或已关闭")
    }) to SearchRecord("查看提醒和任务")
}

internal suspend fun ToolSession.removeRoutine(id: String) = runTool("删除提醒或任务") {
    val r = host.store.loadRoutines().firstOrNull { it.id.startsWith(id.trim()) }
    if (r == null) "没有找到这个编号。" to SearchRecord("删除提醒或任务", error = "没找到")
    else {
        withContext(Dispatchers.Main) { host.deleteRoutine(r.id) }
        "已删除「${r.title}」（编号 ${r.id.take(8)}）。" to SearchRecord("删除：${r.title}")
    }
}

internal suspend fun ToolSession.addEvent(args: JsonObject): String {
    val title = args["title"].text().ifBlank { "日程" }
    // 改日程：原地改（编号不变，重复日程单独改过的那几次也保留）；旧的找不到再新建
    val oldId = args["replace"].text().trim().removePrefix("编号").trim().toLongOrNull()
    return runTool("添加日程：$title") {
        val (begin, dateOnly) = CalendarTool.parse(args["start"].text())
            ?: throw IllegalArgumentException("start 要写成 yyyy-MM-dd HH:mm 或 yyyy-MM-dd")
        val allDay = args["all_day"].text() == "true" || dateOnly
        val end = CalendarTool.parse(args["end"].text())?.first?.takeIf { it > begin }
            ?: (begin + if (allDay) 86_400_000L else 3_600_000L)
        val location = args["location"].text()
        val notes = args["notes"].text()
        val remind = args["remind_minutes"].text().toIntOrNull() ?: 15
        val rrule = CalendarTool.rrule(args["repeat"].text())
        val repeatText = REPEAT_NAMES[args["repeat"].text()]?.let { "，$it" }.orEmpty()
        val shown = SimpleDateFormat(if (allDay) "M月d日 EEE" else "M月d日 EEE HH:mm", Locale.CHINA).format(Date(begin))
        if (oldId != null && CalendarTool.canWrite(app)) {
            // 没写的就不动：地点、备注、提醒、重复方式都沿用原来的
            val updated = withContext(Dispatchers.IO) {
                CalendarTool.update(
                    app, oldId, title, begin, end, allDay,
                    location = args["location"]?.let { location }, notes = args["notes"]?.let { notes },
                    remindMinutes = args["remind_minutes"]?.let { remind }, rrule = args["repeat"]?.let { rrule ?: "" },
                )
            }
            if (updated) {
                val back = withContext(Dispatchers.IO) { runCatching { CalendarTool.get(app, oldId) }.getOrNull() }
                val check = Verify.note(listOfNotNull(
                    when {
                        back == null -> "改完后在日历里查不到这个日程"
                        back.first != title -> "日历里的标题是「${back.first}」，和要改成的不一样"
                        !allDay && back.second != begin -> "日历里的开始时间和要改成的不一样"
                        else -> null
                    },
                ), "已在日历里查到改好的日程，标题和时间都对")
                val series = withContext(Dispatchers.IO) { runCatching { CalendarTool.isRecurring(app, oldId) }.getOrDefault(false) }
                host.refreshWidget()
                return@runTool "已改好日程（编号还是 $oldId）：$shown「$title」" +
                    (if (series) "。这是重复日程，改的是整个系列，之前单独改过或删掉的那几次保持不变" else "") + "。$check" to
                    SearchRecord("改日程：$title")
            }
        }
        val id = if (CalendarTool.canWrite(app)) withContext(Dispatchers.IO) {
            CalendarTool.insert(app, title, begin, end, allDay, location, notes, remind.takeIf { it >= 0 }, rrule)
        } else null
        if (id != null) {
            // 写完重新查一遍：标题和开始时间对得上才算成了（全天日程存的是 UTC 零点，只比日期）
            val back = withContext(Dispatchers.IO) { runCatching { CalendarTool.get(app, id) }.getOrNull() }
            val check = Verify.note(listOfNotNull(
                when {
                    back == null -> "写入后在日历里查不到这个日程"
                    back.first != title -> "日历里的标题是「${back.first}」，和要写的不一样"
                    !allDay && back.second != begin -> "日历里的开始时间和要写的不一样"
                    else -> null
                },
            ), "已在日历里查到这个日程，标题和时间都对")
            val removed = oldId != null && withContext(Dispatchers.IO) { CalendarTool.delete(app, oldId) }
            host.refreshWidget()
            val replaced = when {
                oldId == null -> ""
                removed -> "旧日程 $oldId 已删掉。"
                else -> "没找到旧日程 $oldId，没删。"
            }
            "已写进手机日历：$shown「$title」$repeatText${if (remind >= 0) "，提前 $remind 分钟提醒" else ""}。编号 $id。$replaced$check" to
                SearchRecord(if (removed) "改日程：$title" else "添加日程：$title")
        } else {
            startClock(CalendarTool.insertIntent(title, begin, end, allDay, location, notes, rrule), "手机上没找到日历 App")
            "没有日历写入权限（或没有可写的日历），已打开日历 App 的新建页面并填好内容（$shown「$title」），需要用户点一下保存。" +
                "想让以后自动写入，可以在左侧菜单「提醒和任务」里点「允许读写日历」。" +
                (if (oldId != null) "没有权限也删不了旧日程，请用户自己在日历里删掉。" else "") to SearchRecord("打开日历：$title")
        }
    }
}

internal suspend fun ToolSession.listEvents(args: JsonObject) = runTool("查看日程") {
    if (!CalendarTool.canRead(app)) {
        "没有日历权限。请用户打开左侧菜单「提醒和任务」，点「允许读写日历」。" to SearchRecord("查看日程", error = "没有权限")
    } else {
        val from = CalendarTool.dayStart(args["start_date"].text().ifBlank { null })
        val days = (args["days"].text().toIntOrNull() ?: 1).coerceIn(1, 62)
        val list = withContext(Dispatchers.IO) { CalendarTool.list(app, from, from + days * 86_400_000L) }
        val range = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(from)) + if (days > 1) " 起 $days 天" else ""
        (if (list.isEmpty()) "$range 日历里没有日程。" else "$range 的日程：\n" + list.joinToString("\n") { CalendarTool.describe(it) }) to
            SearchRecord("查看日程：$range")
    }
}

internal suspend fun ToolSession.deleteEvent(id: String) = runTool("删除日程") {
    val n = id.trim().removePrefix("编号").trim().toLongOrNull() ?: throw IllegalArgumentException("编号不对")
    if (!CalendarTool.canWrite(app)) "没有日历权限，删不了。请用户在「提醒和任务」页面授权。" to SearchRecord("删除日程", error = "没有权限")
    else if (withContext(Dispatchers.IO) { CalendarTool.delete(app, n) }) {
        host.refreshWidget()
        "已删除日程 $n。" to SearchRecord("删除日程 $n")
    }
    else "没找到编号 $n 的日程。" to SearchRecord("删除日程", error = "没找到")
}

/**
 * schedule：提醒、定时任务、闹钟合成一个工具，参数统一成 kind + when + text。
 * 里面照旧交给 makeRoutine / setAlarm 去做。
 */
internal suspend fun ToolSession.schedule(args: JsonObject): String {
    val replace = args["replace"].text().trim()
    if (replace.isEmpty()) return createSchedule(args)
    // 提醒、任务、监控：原地改，编号不变，只动给了的字段
    val target = replace.removePrefix("编号").trim()
    val old = withContext(Dispatchers.IO) { host.store.loadRoutines() }.let { l ->
        l.firstOrNull { target.length >= 4 && it.id.startsWith(target) } ?: l.firstOrNull { it.title == target }
    }
    if (old != null && args["kind"].text().ifBlank { old.kind } == old.kind) return updateRoutine(old, args)
    // 闹钟（不归 Jack 管，只能删了重设），或者把提醒改成任务这种换了种类的：
    // 先设新的，设成功了再删旧的，免得新的没设上旧的也没了
    val before = succeeded.get()
    val oldIds = withContext(Dispatchers.IO) { host.store.loadRoutines().map { it.id }.toSet() }
    val created = createSchedule(args)
    if (succeeded.get() == before) return created
    // 新建的那条不能算进“旧的”：新旧同名、用名字指定时，不然可能把刚建的删了
    val newIds = withContext(Dispatchers.IO) { host.store.loadRoutines().map { it.id }.filter { it !in oldIds }.toSet() }
    return created + "\n替换旧的：" + cancelSchedule(buildJsonObject { put("target", replace) }, exclude = newIds)
}

/** 原地改一条提醒 / 任务 / 监控，改完读回核实。 */
private suspend fun ToolSession.updateRoutine(old: Routine, args: JsonObject): String {
    val name = when (old.kind) { "reminder" -> "提醒"; "watch" -> "监控"; else -> "任务" }
    return runTool("修改$name：${old.title}") {
        val r = editedRoutine(old, args, System.currentTimeMillis())
        if (r == old.copy(enabled = true) && old.enabled) return@runTool "没有要改的地方（给的内容和原来一样）。" to SearchRecord("修改$name：没变")
        withContext(Dispatchers.Main) { host.saveRoutine(r) }
        "已改好$name「${r.title}」：${r.describe()}。编号还是 ${r.id.take(8)}。" + verifySaved(r.id) to SearchRecord("修改$name：${r.title}")
    }
}

/**
 * 按 schedule 的参数改一条已有的提醒 / 任务 / 监控：只改给了的字段，其余照旧；关掉的会重新打开。
 * 参数不对时抛 IllegalArgumentException，原因直接给模型看。
 */
internal fun editedRoutine(old: Routine, args: JsonObject, now: Long): Routine {
    fun given(k: String) = args[k]?.text()?.trim()?.takeIf { it.isNotEmpty() }
    var r = old.copy(enabled = true)
    given("text")?.let { r = r.copy(title = it) }
    if (old.kind != "reminder") given("prompt")?.let { r = r.copy(prompt = it) }
    if (old.kind == "watch") {
        given("every_minutes")?.let { r = r.copy(everyMin = (it.toIntOrNull() ?: throw IllegalArgumentException("every_minutes 要写数字")).coerceIn(15, 24 * 60)) }
        given("until")?.let { r = r.copy(until = parseUntil(it, now, r.everyMin)) }
        args["then"]?.let { r = r.copy(then = it.text().trim()) }
        if (r.until <= now) throw IllegalArgumentException("这个监控已经到期了，要接着盯就写一个新的 until")
        return r
    }
    given("when")?.let { w ->
        val (date, h, m) = parseDateTime(w) ?: throw IllegalArgumentException("when 要写成 yyyy-MM-dd HH:mm")
        r = r.copy(date = date, hour = h, minute = m, lastRunAt = 0)
    }
    given("repeat")?.let { rep ->
        if (rep !in REPEAT_LABELS) throw IllegalArgumentException("提醒和任务的 repeat 只支持 none、daily、weekdays、weekly")
        r = r.copy(repeat = rep)
    }
    if (old.kind == "task") {
        args["off_peak"]?.let { r = r.copy(offPeak = it.text() == "true") }
        args["work"]?.let { r = r.copy(work = it.text() == "true") }
    }
    if (r.nextAt() == null) throw IllegalArgumentException("改完的时间已经过去了，换一个以后的时间")
    return r
}

/** 监控的截止时间：只写日期就盯到那天结束；至少要留出一次检查；最多 30 天。 */
internal fun parseUntil(text: String, now: Long, everyMin: Int): Long {
    val (ms, dateOnly) = CalendarTool.parse(text) ?: throw IllegalArgumentException("until 要写成 yyyy-MM-dd HH:mm")
    val until = if (dateOnly) ms + 86_400_000L - 60_000L else ms
    if (until <= now + everyMin * 60_000L) throw IllegalArgumentException("截止时间太近了，至少要留出一次检查的时间")
    return until.coerceAtMost(now + 30 * 86_400_000L)
}

private suspend fun ToolSession.createSchedule(args: JsonObject): String {
    val whenText = args["when"].text()
    val text = args["text"].text().trim()
    val repeat = args["repeat"].text().ifBlank { "none" }
    return when (args["kind"].text()) {
        "reminder" -> {
            if (text.isBlank()) return "提醒要写 text（通知里显示的话）"
            makeRoutine("reminder", buildJsonObject { put("text", text); put("datetime", whenText); put("repeat", repeat) })
        }
        "task" -> {
            val prompt = args["prompt"].text().trim()
            if (prompt.isBlank()) return "定时任务要写 prompt（到时候要执行的完整指令）"
            makeRoutine("task", buildJsonObject {
                put("title", text.ifBlank { prompt.take(20) })
                put("prompt", prompt)
                put("datetime", whenText)
                put("repeat", repeat)
                put("off_peak", args["off_peak"].text() == "true")
                put("work", args["work"].text() == "true")
            })
        }
        "alarm" -> {
            val (date, h, m) = parseDateTime(whenText) ?: return "闹钟时间要写成 HH:mm"
            // 1=周一 … 7=周日；每周就是 when 那一天
            val days = when (repeat) {
                "daily" -> (1..7).toList()
                "weekdays" -> (1..5).toList()
                "weekly" -> {
                    val c = java.util.Calendar.getInstance().apply { time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!! }
                    listOf((c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1)
                }
                else -> emptyList()
            }
            setAlarm(buildJsonObject {
                put("time", String.format(Locale.US, "%02d:%02d", h, m))
                put("label", text)
                put("days", JsonArray(days.map { JsonPrimitive(it) }))
            })
        }
        "watch" -> makeWatch(args, text)
        else -> "kind 要填 reminder、task、alarm 或 watch"
    }
}

/** 监控：每隔 every_minutes 在后台查一次，满足条件才通知；默认盯 3 天，最多 30 天。 */
private suspend fun ToolSession.makeWatch(args: JsonObject, name: String): String {
    val prompt = args["prompt"].text().trim()
    if (prompt.isBlank()) return "监控要写 prompt：每次查什么、怎么查、满足什么条件算有结果"
    return runTool("设置监控：" + name.ifBlank { prompt.take(20) }) {
        val now = System.currentTimeMillis()
        val every = (args["every_minutes"].text().toIntOrNull() ?: 30).coerceIn(15, 24 * 60)
        val untilText = args["until"].text().trim()
        val until = if (untilText.isEmpty()) now + 3 * 86_400_000L else parseUntil(untilText, now, every)
        val r = Routine(
            kind = "watch", title = name.ifBlank { prompt.take(20) }, prompt = prompt, hour = 0, minute = 0,
            everyMin = every, until = until.coerceAtMost(now + 30 * 86_400_000L),
            then = args["then"].text().trim(),
            // 要在网站上看（登录后的页面、要点几下才看得到的）就用浏览器，走 Work
            work = args["work"].text() == "true",
        )
        withContext(Dispatchers.Main) { host.saveRoutine(r) }
        val warn = if (!Routines.canNotify(app)) "（注意：App 还没有通知权限，有结果也弹不出通知，用户需要打开左侧菜单「提醒和任务」页面授权）" else ""
        "已开始监控「${r.title}」：${r.describe()}。" +
            (if (r.then.isNotBlank()) "满足后会接着做：${r.then.take(60)}，做完再通知用户。" else "有结果会通知用户并自动停止。") + "编号 ${r.id.take(8)}。$warn" + verifySaved(r.id) to
            SearchRecord("设置监控：${r.title}")
    }
}

/** cancel_schedule：先按编号或名字找提醒和任务，找不到就当成闹钟（时间或标签）去关。 */
internal suspend fun ToolSession.cancelSchedule(args: JsonObject, exclude: Set<String> = emptySet()): String {
    val target = args["target"].text().trim().removePrefix("编号").trim()
    if (target.isBlank()) return "要填编号、闹钟时间或标签"
    val routines = host.store.loadRoutines().filterNot { it.id in exclude }
    val r = routines.firstOrNull { target.length >= 4 && it.id.startsWith(target) } ?: routines.firstOrNull { it.title == target }
    return when {
        r != null -> removeRoutine(r.id)
        ToolSession.TIME.matches(target) -> cancelAlarm(buildJsonObject { put("time", target) })
        else -> cancelAlarm(buildJsonObject { put("label", target) })
    }
}

private val REPEAT_NAMES = mapOf("daily" to "每天重复", "weekdays" to "工作日重复", "weekly" to "每周重复", "monthly" to "每月重复", "yearly" to "每年重复")

/** 存完重新读一遍：在不在、开没开、排没排上下一次。 */
private suspend fun ToolSession.verifySaved(id: String): String {
    val saved = withContext(Dispatchers.IO) { host.store.loadRoutines().firstOrNull { it.id == id } }
    return Verify.note(listOfNotNull(
        when {
            saved == null -> "保存后查不到这条"
            !saved.enabled || saved.nextAt() == null -> "保存了，但没有排上下一次（时间可能已经过了）"
            else -> null
        },
    ), "已保存并排好下一次")
}
