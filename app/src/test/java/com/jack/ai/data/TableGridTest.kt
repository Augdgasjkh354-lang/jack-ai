package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 照片里的表格排回行列：一行里隔得远的拆开、缺的格子留空、歪一点也认得出同一行。 */
class TableGridTest {
    /** 一个字 20 像素宽、30 高；x 是第几个字的位置。 */
    private fun box(text: String, x: Int, y: Int, conf: Float = 1f) = TableGrid.Box(text, x, y, x + text.length * 20, y + 30, conf)

    @Test
    fun rebuildsRowsAndColumns() {
        val lines = listOf(
            // 表头被识别成一整行，几格之间空得多：要拆开
            listOf(box("姓名", 0, 0), box("部门", 200, 2), box("工资", 400, 0)),
            listOf(box("张三", 0, 50), box("产品", 200, 52), box("12,000", 400, 49)),
            // 这一行部门没填
            listOf(box("李四", 0, 100), box("9,800", 400, 101, conf = 0.4f)),
            // 歪了一点，第二格单独成一行识别出来
            listOf(box("王五", 0, 150)), listOf(box("研发", 205, 158)), listOf(box("15,500", 398, 153)),
            // 同一格里两个英文词：连起来，中间留空格
            listOf(box("Ann", 0, 200), box("Lee", 70, 200), box("设计", 200, 200), box("8,000", 400, 200)),
        )
        val r = TableGrid.build(lines)
        assertEquals(listOf(
            listOf("姓名", "部门", "工资"),
            listOf("张三", "产品", "12,000"),
            listOf("李四", "", "9,800"),
            listOf("王五", "研发", "15,500"),
            listOf("Ann Lee", "设计", "8,000"),
        ), r.rows)
        assertEquals(listOf("9,800"), r.unsure)
    }

    @Test
    fun emptyInputGivesEmptyTable() {
        assertEquals(0, TableGrid.build(emptyList()).rows.size)
    }
}
