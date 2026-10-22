package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TablesTest {
    private val t = Table("记账", listOf("日期", "类别", "金额", "备注"), listOf(
        listOf("2026-09-30", "餐饮", "30", ""),
        listOf("2026-10-01", "餐饮", "25", "午饭, 和朋友"),
        listOf("2026-10-02", "交通", "100", "说\"快点\"\n第二行"),
    ))

    @Test
    fun csvRoundTrip() {
        val back = Tables.parseCsv(Tables.toCsv(t))
        assertEquals(t.columns, back[0])
        assertEquals(t.rows, back.drop(1))
    }

    @Test
    fun filtersCompareNumbersAndDates() {
        fun hit(vararg w: Tables.Where) = t.rows.filter { Tables.matches(t, it, w.toList()) }.size
        assertEquals(2, hit(Tables.Where("日期", ">=", "2026-10-01")))
        assertEquals(1, hit(Tables.Where("金额", ">", "50")))
        assertEquals(2, hit(Tables.Where("类别", "=", "餐饮")))
        assertEquals(1, hit(Tables.Where("类别", "=", "餐饮"), Tables.Where("日期", ">=", "2026-10-01")))
        assertEquals(1, hit(Tables.Where("备注", "contains", "朋友")))
        // 9 < 10 按数字比，不按文字比
        val n = Table("n", listOf("x"), listOf(listOf("9"), listOf("10")))
        assertTrue(Tables.matches(n, listOf("9"), listOf(Tables.Where("x", "<", "10"))))
        assertFalse(Tables.matches(t, t.rows[0], listOf(Tables.Where("不存在", "=", "1"))))
    }

    @Test
    fun safeNameStripsPathCharacters() {
        assertEquals("a_b_c", Tables.safeName("a/b\\c"))
    }

    @Test
    fun ledgerTotalsTodayAndMonth() {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse("2026-10-17")!!.time
        val l = Table("记账", listOf("日期", "类别", "金额"), listOf(
            listOf("2026-09-30", "餐饮", "30"),
            listOf("2026-10-01", "餐饮", "¥25"),
            listOf("2026-10-17", "交通", "18"),
            listOf("2026-10-17", "餐饮", "1,000"),
        ))
        val (day, month) = com.jack.ai.LedgerWidget.totals(l, now)
        assertEquals(1018.0, day, 0.001)
        assertEquals(1043.0, month, 0.001)
    }

    @Test
    fun ledgerAcceptsLooseDates() {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse("2026-10-05")!!.time
        val l = Table("记账", listOf("日期", "金额"), listOf(
            listOf("2026-10-5", "10"),
            listOf("2026/10/05", "20"),
            listOf("2026年10月5日 12:30", "30"),
            listOf("2026-10-1", "40"),
            listOf("2026-9-30", "50"),
        ))
        val (day, month) = com.jack.ai.LedgerWidget.totals(l, now)
        assertEquals(60.0, day, 0.001)
        assertEquals(100.0, month, 0.001)
    }

    @Test
    fun looseDatesCompareAsDates() {
        val d = Table("d", listOf("日期"), listOf(listOf("2026-10-5")))
        // 按文字比 "2026-10-5" > "2026-10-10"，按日期比才对
        assertTrue(Tables.matches(d, d.rows[0], listOf(Tables.Where("日期", "<", "2026-10-10"))))
        assertTrue(Tables.matches(d, d.rows[0], listOf(Tables.Where("日期", "=", "2026-10-05"))))
    }

    @Test
    fun formulasAreGuardedInCsvButNotInData() {
        val f = Table("f", listOf("a", "b"), listOf(listOf("=HYPERLINK(\"x\")", "-12.5"), listOf("@SUM(A1)", "+86 123")))
        val csv = Tables.toCsv(f)
        assertTrue(csv.contains("'=HYPERLINK"))
        assertTrue(csv.contains("'@SUM"))
        assertTrue(csv.contains("'+86 123"))
        // 数字不加引号，Excel 里照样能算
        assertTrue(csv.contains(",-12.5"))
        assertFalse(csv.contains("'-12.5"))
    }

    @Test
    fun sortAndGroup() {
        val led = Table("记账", listOf("日期", "类别", "金额"), listOf(
            listOf("2026-9-28", "餐饮", "30"),
            listOf("2026-10-02", "交通", "1,200"),
            listOf("2026-10-5", "餐饮", "25.5"),
            listOf("2026-09-01", "餐饮", "100"),
            listOf("", "其他", ""),
        ))
        val rows = led.rows.withIndex().toList()
        // 金额从大到小，空的排最后，千分位逗号认得
        assertEquals(listOf("1,200", "100", "30", "25.5", ""), Tables.sortBy(led, rows, "金额", true).map { it.value[2] })
        // 日期写法不统一也按日期排，并保留原来的行号
        val byDate = Tables.sortBy(led, rows, "日期", false)
        assertEquals(listOf(4, 1, 2, 3, 5), byDate.map { it.index + 1 })
        // 按月分组求和：上个月 130，这个月 1225.5
        val months = Tables.group(led, led.rows.dropLast(1), "日期", "month", listOf("金额")).associate { it.key to it.sums["金额"] }
        assertEquals(mapOf("2026-09" to 130.0, "2026-10" to 1225.5), months)
        // 按类别：行数和合计
        val cats = Tables.group(led, led.rows, "类别", "", listOf("金额")).associateBy { it.key }
        assertEquals(3, cats["餐饮"]!!.count)
        assertEquals(155.5, cats["餐饮"]!!.sums["金额"]!!, 0.001)
        assertEquals(0.0, cats["其他"]!!.sums["金额"]!!, 0.0)
        // 中文按拼音排
        assertTrue(Tables.compareCells("餐饮", "交通") < 0)
    }
}
