package com.jack.ai.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 假的系统日历：记着每个日程的各列，看「原地改」写进去的东西对不对。 */
class FakeCalendar : ContentProvider() {
    companion object {
        val events = mutableMapOf<Long, MutableMap<String, Any?>>()
        val reminders = mutableListOf<Pair<Long, Int>>()
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val id = uri.lastPathSegment?.toLongOrNull()
        val c = MatrixCursor(projection ?: arrayOf())
        events[id]?.let { e -> c.addRow(projection!!.map { p -> if (p == CalendarContract.Events.DELETED) 0 else e[p] }.toTypedArray()) }
        return c
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val v = values!!
        reminders += v.getAsLong(CalendarContract.Reminders.EVENT_ID) to v.getAsInteger(CalendarContract.Reminders.MINUTES)
        return Uri.withAppendedPath(uri, "1")
    }
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?): Int {
        val e = events[uri.lastPathSegment?.toLongOrNull()] ?: return 0
        values!!.keySet().forEach { k -> e[k] = values.get(k) }
        return 1
    }
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int {
        val id = args?.firstOrNull()?.toLongOrNull()
        val before = reminders.size
        reminders.removeAll { it.first == id }
        return before - reminders.size
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarUpdateTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeCalendar::class.java, CalendarContract.AUTHORITY)
        FakeCalendar.events.clear(); FakeCalendar.reminders.clear()
        FakeCalendar.events[7] = mutableMapOf(
            CalendarContract.Events.TITLE to "周会", CalendarContract.Events.DTSTART to 1000L,
            CalendarContract.Events.RRULE to "FREQ=WEEKLY", CalendarContract.Events.DURATION to "P3600S",
            CalendarContract.Events.EVENT_LOCATION to "3 楼会议室",
        )
        FakeCalendar.reminders += 7L to 15
    }

    @Test
    fun movingARecurringEventKeepsItsRepeatAndLocation() {
        val ok = CalendarTool.update(ctx, 7, "周会", 5_000_000L, 5_000_000L + 1_800_000L, false,
            location = null, notes = null, remindMinutes = null, rrule = null)
        assertTrue(ok)
        val e = FakeCalendar.events[7]!!
        assertEquals(5_000_000L, e[CalendarContract.Events.DTSTART])
        assertEquals("FREQ=WEEKLY", e[CalendarContract.Events.RRULE])
        assertEquals("P1800S", e[CalendarContract.Events.DURATION])
        assertNull(e[CalendarContract.Events.DTEND])
        assertEquals("3 楼会议室", e[CalendarContract.Events.EVENT_LOCATION])
        // 提醒没说就不动
        assertEquals(listOf(7L to 15), FakeCalendar.reminders)
    }

    @Test
    fun makingItOneOffAndChangingTheReminder() {
        assertTrue(CalendarTool.update(ctx, 7, "改到周六", 9_000_000L, 9_000_000L + 3_600_000L, false,
            location = "", notes = "带电脑", remindMinutes = 30, rrule = ""))
        val e = FakeCalendar.events[7]!!
        assertNull(e[CalendarContract.Events.RRULE]); assertNull(e[CalendarContract.Events.DURATION])
        assertEquals(9_000_000L + 3_600_000L, e[CalendarContract.Events.DTEND])
        assertEquals("改到周六", e[CalendarContract.Events.TITLE])
        assertEquals("", e[CalendarContract.Events.EVENT_LOCATION])
        assertEquals("带电脑", e[CalendarContract.Events.DESCRIPTION])
        assertEquals(listOf(7L to 30), FakeCalendar.reminders)
        assertFalse(CalendarTool.isRecurring(ctx, 7))
    }

    @Test
    fun unknownIdIsReported() {
        assertFalse(CalendarTool.update(ctx, 99, "x", 0, 1, false, null, null, null, null))
    }
}
