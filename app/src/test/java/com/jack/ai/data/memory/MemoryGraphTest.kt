package com.jack.ai.data.memory

import com.jack.ai.data.MemoryItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 记忆的关系（涉及同一个就相连）和时间（上周、去年国庆）。 */
class MemoryGraphTest {
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
    private fun ms(d: String) = fmt.parse(d)!!.time
    // 2026-11-23 是周一，中午
    private val now = ms("2026-11-23") + 12 * 3600_000L
    private fun span(q: String) = MemoryGraph.timeOf(q, now)?.let { fmt.format(it.from) + ".." + fmt.format(it.to) }

    @Test fun readsCommonTimeWords() {
        assertEquals("2026-11-22..2026-11-23", span("昨天聊了啥"))
        assertEquals("2026-11-16..2026-11-23", span("上周我去哪了"))
        assertEquals("2026-11-18..2026-11-19", span("上周三"))
        assertEquals("2026-10-01..2026-11-01", span("上个月"))
        assertEquals("2026-03-01..2026-04-01", span("3月份的事"))
        assertEquals("2026-03-05..2026-03-06", span("三月五号"))
        // 接下来两个月算今年，再往后的算去年
        assertEquals("2026-12-01..2027-01-01", span("12月"))
        assertEquals("2025-12-01..2026-01-01", MemoryGraph.timeOf("12月", ms("2026-08-01"))?.let { fmt.format(it.from) + ".." + fmt.format(it.to) })
        assertEquals("2025-10-01..2025-10-08", span("去年国庆"))
        assertEquals("2025-01-01..2026-01-01", span("去年"))
        assertEquals("2025-03-01..2025-04-01", span("去年3月"))
        assertEquals("2026-11-21..2026-11-22", span("两天前"))
        assertEquals("2026-11-17..2026-11-24", span("最近7天"))
        assertEquals("2026-02-14..2026-02-15", span("2026-02-14"))
        assertNull(span("小王喜欢什么"))
    }

    @Test fun cleansModelOutput() {
        assertEquals("2026-11-20", MemoryGraph.cleanAt("2026-11-20"))
        assertEquals("2026-03", MemoryGraph.cleanAt("2026年3月"))
        assertEquals("2026", MemoryGraph.cleanAt("2026"))
        assertEquals("", MemoryGraph.cleanAt("不知道"))
        assertEquals(listOf("小王", "体检"), MemoryGraph.cleanAbout(listOf(" 小王", "「体检」", "小王 ", "")))
    }

    private val wang = MemoryItem(id = "w1", text = "小王是 Jack 的同事，做设计", about = listOf("小王"))
    private val bday = MemoryItem(id = "w2", text = "小王的生日在 12 月 3 日", about = listOf("小王"), at = "2026-12-03")
    private val trip = MemoryItem(id = "t1", text = "Jack 国庆和小王去了杭州", about = listOf("小王", "杭州"), at = "2025-10")
    private val cat = MemoryItem(id = "cat1", text = "Jack 养了一只橘猫叫团子", about = listOf("团子"))
    private val hz = MemoryItem(id = "hz01", text = "Jack 觉得杭州的龙井虾仁一般", about = listOf("杭州"))

    @Test fun mentionedNamesAndNeighbors() {
        val names = MemoryGraph.entities(listOf(wang, bday, trip, cat, hz)).keys
        assertEquals(listOf("小王"), MemoryGraph.mentioned("小王最近怎么样", names))
        assertEquals(emptyList<String>(), MemoryGraph.mentioned("今天天气", names))
        // 杭州那条和小王、杭州都相连，共有的越多越靠前
        val n = MemoryGraph.neighbors(listOf(trip), listOf(wang, bday, trip, cat, hz)).map { it.id }
        assertTrue(n.containsAll(listOf("w1", "w2", "hz01")))
        assertTrue("cat1" !in n)
    }

    @Test fun searchByPersonTimeAndLinks() {
        val hot = listOf(wang, cat)
        val deep = listOf(bday, trip, hz)
        val r = MemoryGraph.search("小王", hot, deep, now).map { it.item.id }
        assertTrue(r.containsAll(listOf("w1", "w2", "t1")))
        // 只说时间：列出那段时间的
        val t = MemoryGraph.search("去年国庆", hot, deep, now)
        assertEquals("t1", t.first().item.id)
        assertTrue(t.first().deep)
        // 人 + 时间：只要那段时间的，再带出相连的
        val wt = MemoryGraph.search("12月 小王", hot, deep, now)
        assertEquals("w2", wt.first().item.id)
    }

    @Test fun dreamOpsCarryAboutAndTag() {
        val ops = MemoryOps.parse(Json.parseToJsonElement("""[
            {"op":"add","text":"Jack 下周三体检","about":["体检"],"at":"2026年12月2日"},
            {"op":"tag","id":"m:cat1","about":["团子","猫"]},
            {"op":"tag","id":"hz01","about":[],"at":""}
        ]""").jsonArray)
        assertEquals("2026-12-02", ops[0].at)
        val r = MemoryOps.apply(listOf(cat), ops, now = now, deepNow = listOf(hz))
        assertEquals(listOf("体检"), r.live.last().about)
        assertEquals(listOf("团子", "猫"), r.live.first { it.id == "cat1" }.about)
        assertEquals(1, r.tagged)
        assertEquals(1, r.skipped)
        // tag 不算改动
        assertTrue(!MemoryOps.tooMany(1, List(10) { MemoryOps.Op("tag", "cat1", about = listOf("x")) }))
        assertTrue(MemoryOps.listing(listOf(cat)).contains("about 团子"))
    }
}
