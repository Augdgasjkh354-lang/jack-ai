package com.jack.ai.api

import com.jack.ai.api.BrowserPages.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPagesTest {
    @Test
    fun recentKeepsNewestFirstWithoutDuplicates() {
        var list = BrowserPages.visit(emptyList(), "https://a.com", "A", 1)
        list = BrowserPages.visit(list, "https://b.com", "B", 2)
        list = BrowserPages.visit(list, "https://a.com", "A2", 3)
        assertEquals(listOf("https://a.com", "https://b.com"), list.map { it.url })
        assertEquals("A2", list[0].title)
        // 起始页、文件这类不记
        assertSame(list, BrowserPages.visit(list, "about:blank", "", 4))
        val many = (1..40).fold(emptyList<Visit>()) { l, i -> BrowserPages.visit(l, "https://s$i.com", "", i.toLong()) }
        assertEquals(BrowserPages.RECENT_MAX, many.size)
        assertEquals("https://s40.com", many.first().url)
    }

    @Test
    fun lateTitleIsFilledInPlace() {
        val list = listOf(Visit("https://a.com", "", 1), Visit("https://b.com", "B", 2))
        assertEquals("A", BrowserPages.retitle(list, "https://a.com", " A ")[0].title)
        assertSame(list, BrowserPages.retitle(list, "https://b.com", "B"))
        assertSame(list, BrowserPages.retitle(list, "https://c.com", "C"))
    }

    @Test
    fun bookmarkToggles() {
        val on = BrowserPages.toggle(emptyList(), "https://a.com", "A", 1)
        assertEquals(1, on.size)
        assertTrue(BrowserPages.toggle(on, "https://a.com", "A", 2).isEmpty())
        assertTrue(BrowserPages.toggle(emptyList(), "about:blank", "", 1).isEmpty())
    }

    @Test
    fun labelFallsBackToHost() {
        assertEquals("zhihu.com", BrowserPages.label(Visit("https://www.zhihu.com/question/1")))
        assertEquals("知乎", BrowserPages.label(Visit("https://www.zhihu.com", "知乎")))
    }

    @Test
    fun userAgentLooksLikePlainChrome() {
        val wv = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.100 Mobile Safari/537.36",
            BrowserPages.cleanUserAgent(wv),
        )
    }

    @Test
    fun fileTypesFromAccept() {
        assertEquals("*/*" to emptyList<String>(), BrowserPages.pickTypes(null))
        assertEquals("*/*" to emptyList<String>(), BrowserPages.pickTypes(arrayOf("")))
        assertEquals("image/*" to emptyList<String>(), BrowserPages.pickTypes(arrayOf("image/*")))
        assertEquals("application/pdf" to emptyList<String>(), BrowserPages.pickTypes(arrayOf(".pdf")))
        assertEquals("*/*" to listOf("image/jpeg", "image/png"), BrowserPages.pickTypes(arrayOf(".jpg,.jpeg", "image/png")))
        // 有看不懂的扩展名就不限制，免得选不到
        assertEquals("*/*" to emptyList<String>(), BrowserPages.pickTypes(arrayOf(".pdf", ".xyz")))
    }

    @Test
    fun desktopIsRememberedPerSiteIncludingSubdomains() {
        val sites = setOf("taobao.com")
        assertTrue(BrowserPages.isDesktop(sites, "https://www.taobao.com/"))
        assertTrue(BrowserPages.isDesktop(sites, "https://item.taobao.com/item.htm?id=1"))
        assertEquals(false, BrowserPages.isDesktop(sites, "https://nottaobao.com/"))
        assertEquals(false, BrowserPages.isDesktop(emptySet(), "https://taobao.com/"))
        assertEquals("taobao.com", BrowserPages.site("https://WWW.Taobao.com/x"))
    }

    @Test
    fun desktopAgentKeepsChromeVersion() {
        val ua = BrowserPages.desktopUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.100 Mobile Safari/537.36")
        assertTrue(ua, ua.contains("Windows NT 10.0") && ua.contains("Chrome/129.0.6668.100") && !ua.contains("Mobile"))
    }

    @Test
    fun askKaviFileName() {
        assertEquals("报价_方案.txt", BrowserPages.fileName("报价/方案", "https://a.com"))
        assertEquals("a.com.txt", BrowserPages.fileName("", "https://www.a.com/x"))
    }
}
