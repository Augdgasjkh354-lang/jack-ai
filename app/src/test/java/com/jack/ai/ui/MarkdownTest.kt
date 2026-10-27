package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun priceRangesAreNotMath() {
        assertEquals("价格 $5-$10，大概 $8 左右", normalizeLatex("价格 $5-$10，大概 $8 左右"))
        assertEquals("$5到$10元", normalizeLatex("$5到$10元"))
    }

    @Test
    fun inlineMathStillWorks() {
        assertTrue(normalizeLatex("面积是 \$x^2\$。").contains("\$\$x^2\$\$"))
        assertTrue(normalizeLatex("当 \$a+b=2\$ 时").contains("\$\$a+b=2\$\$"))
    }

    @Test
    fun listItemTitlesSplitOnlyOnColonDashOrNewline() {
        assertEquals("设计工作节奏" to "找到精力最好的时段", itemTitle("**设计工作节奏**：找到精力最好的时段"))
        assertEquals("管理注意力" to "一次只做一件事", itemTitle("**管理注意力：**一次只做一件事"))
        assertEquals("先建立整体认知" to "花点时间了解设计理念", itemTitle("**先建立整体认知**\n花点时间了解设计理念"))
        assertEquals("边学边做" to "从小项目开始", itemTitle("**边学边做** - 从小项目开始"))
        // 加粗后面直接接句号、正文：不是小标题
        assertEquals(null to "**结论**。后面接着说", itemTitle("**结论**。后面接着说"))
        assertEquals(null to "普通的一项", itemTitle("普通的一项"))
        // 输出中只写出了小标题：先不拆
        assertEquals(null to "**标题**：", itemTitle("**标题**："))
    }
}
