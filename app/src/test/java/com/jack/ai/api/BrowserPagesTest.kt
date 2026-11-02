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

    @Test
    fun suggestMatchesEveryWordAndPutsPrefixAndMarksFirst() {
        val marks = listOf(Visit("https://news.example.com/a", "每日新闻"))
        val pages = listOf(
            Visit("https://www.github.com/jack/ai", "Jack AI 仓库"),
            Visit("https://example.org/github-tips", "GitHub tips"),
            Visit("https://news.example.com/a", "重复的"),
            Visit("about:blank", "github"),
        )
        // 网址开头对上的排前面；about: 这类不出
        assertEquals(listOf("https://www.github.com/jack/ai", "https://example.org/github-tips"),
            BrowserPages.suggest("git", marks, pages).map { it.url })
        // 每个词都要对上；书签和最近访问里同一个网址只出一次
        assertEquals(listOf("https://www.github.com/jack/ai"), BrowserPages.suggest("jack 仓库", marks, pages).map { it.url })
        assertEquals(1, BrowserPages.suggest("news", marks, pages).size)
        // 都没开头对上时书签排前
        assertEquals("https://news.example.com/a", BrowserPages.suggest("com", marks, pages).first().url)
        assertTrue(BrowserPages.suggest("   ", marks, pages).isEmpty())
    }

    @Test
    fun mediaKindsKnowsCameraAndMicOnly() {
        assertEquals(setOf(BrowserPages.CAMERA, BrowserPages.MIC),
            BrowserPages.mediaKinds(arrayOf("android.webkit.resource.VIDEO_CAPTURE", "android.webkit.resource.AUDIO_CAPTURE", "android.webkit.resource.MIDI_SYSEX")))
        assertEquals("相机和麦克风", BrowserPages.kindsText(setOf(BrowserPages.MIC, BrowserPages.CAMERA)))
    }

    @Test
    fun tabsToSaveSkipsIncognitoAndBlankAndKeepsCurrent() {
        fun t(url: String, incognito: Boolean = false) = BrowserPages.SavedTab(url) to incognito
        val all = listOf(t("https://a.com"), t("https://secret.com", true), t(""), t("https://b.com", false))
        assertEquals(listOf("https://a.com", "https://b.com"), BrowserPages.tabsToSave(all, 3, 10).tabs.map { it.url })
        assertEquals(1, BrowserPages.tabsToSave(all, 3, 10).current)
        // 当前是隐身页：退到前面最近的一个
        assertEquals(0, BrowserPages.tabsToSave(all, 1, 10).current)
        assertEquals(0, BrowserPages.tabsToSave(listOf(t("", false)), 0, 10).tabs.size)
    }
}
