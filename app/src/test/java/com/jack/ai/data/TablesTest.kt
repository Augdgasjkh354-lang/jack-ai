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
}
