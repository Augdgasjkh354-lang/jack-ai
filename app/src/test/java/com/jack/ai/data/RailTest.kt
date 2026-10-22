package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RailTest {
    private val js = "var station_names ='@bjb|北京北|VAP|beijingbei|bjb|0|0357|北京|||@bji|北京|BJP|beijing|bj|2|0357|北京|||" +
        "@shh|上海|SHH|shanghai|sh|3|0712|上海|||@aoh|上海虹桥|AOH|shanghaihongqiao|shhq|4|0712|上海|||';"

    @Test
    fun stationsAndNames() {
        val list = Rail12306.parseStations(js)
        assertEquals(4, list.size)
        assertEquals("BJP", Rail12306.resolve(list, "北京")?.code)
        assertEquals("AOH", Rail12306.resolve(list, "上海虹桥站")?.code)
        assertEquals("SHH", Rail12306.resolve(list, "shanghai")?.code)
        assertEquals("AOH", Rail12306.resolve(list, "shhq")?.code)
        assertNull(Rail12306.resolve(list, "火星"))
    }

    @Test
    fun parsesATrainLine() {
        // 12306 真实返回的一行（前面的加密串截短了）
        val line = "abc|预订|240000G54700|G547|VNP|AOH|VNP|AOH|06:18|12:11|05:53|Y|xyz|20261027|3|P3|01|13|1|0|||||||无||||有|有|无||90M0O0W0|9MOO|0|1"
        val t = Rail12306.parseTrain(line, mapOf("VNP" to "北京南", "AOH" to "上海虹桥"))!!
        assertEquals("G547", t.code)
        assertEquals("北京南", t.from)
        assertEquals("上海虹桥", t.to)
        assertEquals("06:18", t.depart)
        assertEquals(listOf("商务座" to "无", "一等座" to "有", "二等座" to "有", "无座" to "无"), t.seats)
        assertTrue(Rail12306.describe(t).startsWith("G547｜北京南 06:18 → 上海虹桥 12:11"))
    }
}
