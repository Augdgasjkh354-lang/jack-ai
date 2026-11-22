package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 改写稿核对：原文里没有的数字、把作者写成「我」的地方。 */
class RewriteCheckTest {
    private val source = "Dan Koe 说他的团队一年做到 $480,000，转化率 3.5%，2027 年会更好。我整天都这么做。"

    @Test
    fun findsNumbersNotInTheSource() {
        val r = RewriteCheck.check("一年做到 48 万美元（480,000）\n转化率 3.5%\n第 3 步：先做 120 个访谈", source)
        // 48 万 = 原文的 480,000，3.5 原文有，3 是序号不查；只有 120 原文没有
        assertEquals(listOf("120"), r.missing.map { it.first })
        assertTrue(RewriteCheck.check("年入 $10k", "they make 10,000 a year").missing.isEmpty())
    }

    @Test
    fun flagsFirstPersonOutsideQuotes() {
        val r = RewriteCheck.check("我的团队一年翻三倍\n他说：「我整天都这么做」", source)
        assertEquals(1, r.firstPerson.size)
        assertTrue(r.firstPerson[0].startsWith("我的团队"))
        assertTrue(RewriteCheck.check("我的团队一年翻三倍", source, self = true).firstPerson.isEmpty())
    }

    @Test
    fun cleanDraftSaysSo() {
        assertTrue(RewriteCheck.format(RewriteCheck.check("他的团队做到 480,000", source)).startsWith("没发现问题"))
    }
}
