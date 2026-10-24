package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFlowsTest {
    private val compare = BrowserFlow(name = "1688 比价", site = "1688.com", whenToUse = "找货比价时",
        steps = "1. 打开 https://s.1688.com 搜 {关键词}\n2. 点「销量」\n3. extract 读前 {页数} 页，存成 {关键词}.csv")
    private val shop = BrowserFlow(name = "查店铺资质", site = "1688.com", steps = "1. 打开店铺首页")
    private val jd = BrowserFlow(name = "京东查价", site = "jd.com", steps = "1. 搜 {商品}")
    private val all = listOf(compare, shop, jd)

    @Test
    fun siteIsNormalised() {
        assertEquals("1688.com", BrowserFlows.siteOf("https://www.1688.com/xx"))
        assertEquals("1688.com", BrowserFlows.siteOf(" www.1688.com "))
        assertEquals("s.1688.com", BrowserFlows.siteOf("s.1688.com"))
        assertEquals("", BrowserFlows.siteOf(""))
    }

    @Test
    fun flowsMatchTheSiteAndItsSubdomains() {
        assertEquals(listOf(compare, shop), BrowserFlows.forUrl(all, "https://s.1688.com/selloffer/offer_search.htm?keywords=x"))
        assertEquals(listOf(jd), BrowserFlows.forUrl(all, "https://search.jd.com/Search"))
        assertTrue(BrowserFlows.forUrl(all, "https://not1688.com/").isEmpty())
        assertTrue(BrowserFlows.forUrl(all, "about:blank").isEmpty())
    }

    @Test
    fun findByExactThenPartialName() {
        assertEquals(compare, BrowserFlows.find(all, "1688 比价"))
        assertEquals(shop, BrowserFlows.find(all, "店铺资质"))
        assertNull(BrowserFlows.find(all, "淘宝"))
    }

    @Test
    fun blanksAreListedOnce() {
        assertEquals(listOf("关键词", "页数"), BrowserFlows.blanks(compare.steps))
        assertTrue(BrowserFlows.blanks(shop.steps).isEmpty())
    }

    @Test
    fun indexAndHintAreShort() {
        val idx = BrowserFlows.index(all)
        assertTrue(idx, idx.contains("「1688 比价」（1688.com）：找货比价时") && idx.contains("「京东查价」（jd.com）"))
        assertEquals("（还没有存过流程）", BrowserFlows.index(emptyList()))
        val hint = BrowserFlows.hint(listOf(compare))
        assertTrue(hint.contains("「1688 比价」（找货比价时）") && hint.contains("action=flow"))
        // 步骤本身不进提醒（用到才读）
        assertTrue(!hint.contains("销量"))
    }

    @Test
    fun siteNotesAreDedupedCappedAndMatched() {
        var notes = emptyList<SiteNote>()
        notes = BrowserFlows.addNote(notes, "jd.com", "搜索结果要等 2 秒才出来", now = 1)
        notes = BrowserFlows.addNote(notes, "jd.com", "搜索结果要等 2 秒才出来", now = 2)
        assertEquals(1, notes.size)
        repeat(10) { notes = BrowserFlows.addNote(notes, "1688.com", "窍门 $it", now = 10L + it) }
        assertEquals(BrowserFlows.NOTES_PER_SITE, notes.count { it.site == "1688.com" })
        assertTrue(notes.any { it.text == "窍门 9" } && notes.none { it.text == "窍门 0" })
        assertEquals(listOf("搜索结果要等 2 秒才出来"), BrowserFlows.notesFor(notes, "https://search.jd.com/Search").map { it.text })
        val hint = BrowserFlows.hint(emptyList(), BrowserFlows.notesFor(notes, "https://search.jd.com/x"))
        assertTrue(hint, hint.startsWith("【这个网站的备忘】") && !hint.contains("流程"))
    }
}
