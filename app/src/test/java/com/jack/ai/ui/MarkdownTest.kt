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
}
