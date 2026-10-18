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
        "已在手机时钟里设好 $hm 的闹钟（$repeat${if (label.isNotBlank()) "，标签「$label」" else ""}）。" to
            SearchRecord("设闹钟 $hm")
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
        val repeat = args["repeat"].text().takeIf { it in REPEAT_LABELS } ?: "none"
        val r = if (kind == "reminder") Routine(kind = "reminder", title = args["text"].text(), hour = h, minute = m, repeat = repeat, date = date)
        else Routine(kind = "task", title = args["title"].text(), prompt = args["prompt"].text(), hour = h, minute = m, repeat = repeat, date = date,
            offPeak = args["off_peak"].text() == "true", work = args["work"].text() == "true")
        if (r.nextAt() == null) throw IllegalArgumentException("这个时间已经过去了")
        withContext(Dispatchers.Main) { host.saveRoutine(r) }
        val warn = if (!Routines.canNotify(app)) "（注意：App 还没有通知权限，用户需要打开左侧菜单「提醒和任务」页面授权）" else ""
        "已设置：${r.describe()}，${if (kind == "reminder") "提醒" else "任务"}「${r.title}」。编号 ${r.id.take(8)}。$warn" to
            SearchRecord("$label：${r.title}")
    }
}

internal suspend fun ToolSession.listRoutines() = runTool("查看提醒和任务") {
    val list = host.store.loadRoutines()
    (if (list.isEmpty()) "用户还没有任何提醒和定时任务。" else list.joinToString("\n") { r ->
        "编号 ${r.id.take(8)}｜${if (r.kind == "reminder") "提醒" else "任务"}｜${r.describe()}｜${r.title}" +
            (if (r.enabled) "" else "｜已结束或已关闭")
    }) to SearchRecord("查看提醒和任务")
}

internal suspend fun ToolSession.removeRoutine(id: String) = runTool("删除提醒或任务") {
    val r = host.store.loadRoutines().firstOrNull { it.id.startsWith(id.trim()) }
    if (r == null) "没有找到这个编号。" to SearchRecord("删除提醒或任务", error = "没找到")
    else {
        withContext(Dispatchers.Main) { host.deleteRoutine(r.id) }
        "已删除「${r.title}」。" to SearchRecord("删除：${r.title}")
    }
}

internal suspend fun ToolSession.addEvent(args: JsonObject): String {
    val title = args["title"].text().ifBlank { "日程" }
    // 改日程：新的写进日历后再删旧的
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
        val id = if (CalendarTool.canWrite(app)) withContext(Dispatchers.IO) {
            CalendarTool.insert(app, title, begin, end, allDay, location, notes, remind.takeIf { it >= 0 }, rrule)
        } else null
        if (id != null) {
            val removed = oldId != null && withContext(Dispatchers.IO) { CalendarTool.delete(app, oldId) }
            host.refreshWidget()
            val replaced = when {
                oldId == null -> ""
                removed -> "旧日程 $oldId 已删掉。"
                else -> "没找到旧日程 $oldId，没删。"
            }
            "已写进手机日历：$shown「$title」$repeatText${if (remind >= 0) "，提前 $remind 分钟提醒" else ""}。编号 $id。$replaced" to
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
    // 改时间：先设新的，设成功了再删旧的，免得新的没设上旧的也没了
    val before = succeeded.get()
    val created = createSchedule(args)
    if (succeeded.get() == before) return created
    return created + "\n" + cancelSchedule(buildJsonObject { put("target", replace) })
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
        else -> "kind 要填 reminder、task 或 alarm"
    }
}

/** cancel_schedule：先按编号或名字找提醒和任务，找不到就当成闹钟（时间或标签）去关。 */
internal suspend fun ToolSession.cancelSchedule(args: JsonObject): String {
    val target = args["target"].text().trim().removePrefix("编号").trim()
    if (target.isBlank()) return "要填编号、闹钟时间或标签"
    val routines = host.store.loadRoutines()
    val r = routines.firstOrNull { target.length >= 4 && it.id.startsWith(target) } ?: routines.firstOrNull { it.title == target }
    return when {
        r != null -> removeRoutine(r.id)
        ToolSession.TIME.matches(target) -> cancelAlarm(buildJsonObject { put("time", target) })
        else -> cancelAlarm(buildJsonObject { put("label", target) })
    }
}

private val REPEAT_NAMES = mapOf("daily" to "每天重复", "weekdays" to "工作日重复", "weekly" to "每周重复", "monthly" to "每月重复", "yearly" to "每年重复")
