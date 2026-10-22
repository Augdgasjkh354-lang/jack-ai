package com.jack.ai.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 读写手机系统日历。有权限时直接写入；没有写权限时打开日历 App 的新建页面让用户确认。 */
object CalendarTool {
    data class Event(
        val id: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String,
        val calendar: String,
    )

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun canRead(ctx: Context) = granted(ctx, Manifest.permission.READ_CALENDAR)
    fun canWrite(ctx: Context) = granted(ctx, Manifest.permission.WRITE_CALENDAR) && canRead(ctx)

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val SHOW = SimpleDateFormat("M月d日 EEE HH:mm", Locale.CHINA)

    /**
     * 解析 "yyyy-MM-dd HH:mm" 或 "yyyy-MM-dd"（全天）。返回 (毫秒, 是否只有日期)。
     */
    fun parse(s: String): Pair<Long, Boolean>? {
        val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T]+(\d{1,2})[:：](\d{2}))?""").find(s.trim()) ?: return null
        val c = Calendar.getInstance()
        c.clear()
        val (y, mo, d) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        val hasTime = m.groupValues[4].isNotEmpty()
        val h = if (hasTime) m.groupValues[4].toInt() else 0
        val mi = if (hasTime) m.groupValues[5].toInt() else 0
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
        c.set(y, mo - 1, d, h, mi)
        return c.timeInMillis to !hasTime
    }

    /** 全天日程在日历里要用 UTC 零点。 */
    private fun utcMidnight(localMs: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMs }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun writableCalendarId(ctx: Context): Long? {
        val cols = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        )
        var best: Long? = null
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(3) == 0) continue
                if (c.getInt(1) == 1) return c.getLong(0)
                if (best == null) best = c.getLong(0)
            }
        }
        return best
    }

    /** 直接写进日历，返回日程编号；没有可写的日历时返回 null。 */
    fun insert(
        ctx: Context, title: String, begin: Long, end: Long, allDay: Boolean,
        location: String, notes: String, remindMinutes: Int?, rrule: String? = null,
    ): Long? {
        val calId = writableCalendarId(ctx) ?: return null
        val values = timeValues(begin, end, allDay, rrule, update = false).apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (notes.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, notes)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        if (remindMinutes != null && remindMinutes >= 0) setReminder(ctx, id, remindMinutes)
        return id
    }

    /**
     * 原地改一个日程：编号不变，重复日程里单独改过、删过的那几次也保留。
     * location / notes / remindMinutes / rrule 传 null 就不动原来的；rrule 传 "" 是改成不重复。
     * 返回 false 表示日历里没有这个编号。
     */
    fun update(
        ctx: Context, id: Long, title: String, begin: Long, end: Long, allDay: Boolean,
        location: String?, notes: String?, remindMinutes: Int?, rrule: String?,
    ): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        // 没说重复方式就沿用原来的（改时间不该把「每周一次」弄丢）
        val keep = rrule ?: ctx.contentResolver.query(uri, arrayOf(CalendarContract.Events.RRULE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else null } ?: return false
        val values = timeValues(begin, end, allDay, keep.ifBlank { null }, update = true).apply {
            put(CalendarContract.Events.TITLE, title)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        if (ctx.contentResolver.update(uri, values, null, null) <= 0) return false
        if (remindMinutes != null) {
            runCatching {
                ctx.contentResolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()))
            }
            if (remindMinutes >= 0) setReminder(ctx, id, remindMinutes)
        }
        return true
    }

    /** 这个日程现在是不是重复的。 */
    fun isRecurring(ctx: Context, id: Long): Boolean =
        ctx.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.RRULE), null, null, null)?.use { c -> c.moveToFirst() && !c.getString(0).isNullOrBlank() } ?: false

    private fun setReminder(ctx: Context, id: Long, minutes: Int) {
        runCatching {
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
    }

    /** 时间相关的几列。改日程时把用不上的那一列清空（重复日程用时长、不用结束时间，反过来也一样）。 */
    private fun timeValues(begin: Long, end: Long, allDay: Boolean, rrule: String?, update: Boolean) = ContentValues().apply {
            if (allDay) {
                val start = utcMidnight(begin)
                val days = ((utcMidnight(end) - start) / 86_400_000L).coerceAtLeast(1)
                put(CalendarContract.Events.DTSTART, start)
                // 重复日程不能写结束时间，要写时长
                if (rrule != null) put(CalendarContract.Events.DURATION, "P${days}D")
                else put(CalendarContract.Events.DTEND, start + days * 86_400_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, begin)
                if (rrule != null) put(CalendarContract.Events.DURATION, "P${((end - begin) / 1000).coerceAtLeast(60)}S")
                else put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            if (rrule != null) put(CalendarContract.Events.RRULE, rrule)
            if (update) {
                if (!allDay) put(CalendarContract.Events.ALL_DAY, 0)
                if (rrule == null) { putNull(CalendarContract.Events.RRULE); putNull(CalendarContract.Events.DURATION) }
                else putNull(CalendarContract.Events.DTEND)
            }
        }

    /** 没有写权限时，打开日历 App 的新建日程页面，内容都填好了。 */
    /** 重复方式 → 日历的 RRULE；不重复返回 null。 */
    fun rrule(repeat: String): String? = when (repeat) {
        "daily" -> "FREQ=DAILY"
        "weekdays" -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        "weekly" -> "FREQ=WEEKLY"
        "monthly" -> "FREQ=MONTHLY"
        "yearly" -> "FREQ=YEARLY"
        else -> null
    }

    fun insertIntent(title: String, begin: Long, end: Long, allDay: Boolean, location: String, notes: String, rrule: String? = null): Intent =
        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .apply { if (rrule != null) putExtra(CalendarContract.Events.RRULE, rrule) }
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            .putExtra(CalendarContract.Events.DESCRIPTION, notes)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 列出一段时间里的日程（包括重复日程的每一次）。 */
    fun list(ctx: Context, from: Long, to: Long): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<Event>()
        ctx.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 200) {
                out += Event(
                    c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3),
                    c.getInt(4) == 1, c.getString(5).orEmpty(), c.getString(6).orEmpty(),
                )
            }
        }
        return out
    }

    /** 按编号重新读一个日程：(标题, 开始时间)，找不到返回 null。写入后核实用。 */
    fun get(ctx: Context, id: Long): Pair<String, Long>? =
        ctx.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DELETED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && c.getInt(2) == 0) c.getString(0).orEmpty() to c.getLong(1) else null }

    fun delete(ctx: Context, id: Long): Boolean =
        ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0

    fun describe(e: Event): String {
        val time = if (e.allDay) {
            // 全天日程存的是 UTC 零点，按 UTC 显示日期
            SimpleDateFormat("M月d日 EEE", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(e.begin)) + " 全天"
        } else {
            SHOW.format(Date(e.begin)) + "–" + SimpleDateFormat("HH:mm", Locale.US).format(Date(e.end))
        }
        return "编号 ${e.id}｜$time｜${e.title.ifBlank { "（无标题）" }}" +
            (if (e.location.isNotBlank()) "｜地点：${e.location}" else "") +
            (if (e.calendar.isNotBlank()) "｜${e.calendar}" else "")
    }

    fun dayStart(dateText: String?): Long {
        val d = dateText?.let { runCatching { DAY.parse(it.trim()) }.getOrNull() }
        return Calendar.getInstance().apply {
            if (d != null) time = d
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
