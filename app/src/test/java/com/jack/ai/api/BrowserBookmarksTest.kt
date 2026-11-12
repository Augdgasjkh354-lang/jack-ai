package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBookmarksTest {
    private val chrome = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1700000000" PERSONAL_TOOLBAR_FOLDER="true">书签栏</H3>
            <DL><p>
                <DT><A HREF="https://www.taobao.com/" ADD_DATE="1700000001" ICON="data:image/png;base64,AAA">淘宝 &amp; 天猫</A>
                <DT><H3>工作</H3>
                <DL><p>
                    <DT><A HREF="https://1688.com/?a=1&amp;b=2" ADD_DATE="1700000002">1688</A>
                    <DT><A HREF="javascript:alert(1)">书签小工具</A>
                </DL><p>
                <DT><A HREF="https://github.com">GitHub</A>
            </DL><p>
            <DT><A HREF="https://www.taobao.com/">重复的</A>
            <DT><A HREF='http://example.com'>单引号</A>
        </DL><p>
    """.trimIndent()

    @Test
    fun parsesChromeExport() {
        val list = BrowserBookmarks.parse(chrome)
        assertEquals(listOf("https://www.taobao.com/", "https://1688.com/?a=1&b=2", "https://github.com", "http://example.com"), list.map { it.url })
        assertEquals("淘宝 & 天猫", list[0].title)
        assertEquals("书签栏", list[0].folder)
        assertEquals("工作", list[1].folder)
        // 出了「工作」文件夹又回到书签栏
        assertEquals("书签栏", list[2].folder)
        assertEquals("", list[3].folder)
        assertEquals(1_700_000_001_000L, list[0].at)
    }

    @Test
    fun mergeSkipsExisting() {
        val have = listOf(BrowserPages.Visit("https://github.com", "我的"))
        val (merged, added) = BrowserBookmarks.merge(have, BrowserBookmarks.parse(chrome))
        assertEquals(3, added)
        assertEquals("我的", merged.first().title)
        assertEquals(4, merged.size)
    }

    @Test
    fun exportRoundTrips() {
        val list = BrowserBookmarks.parse(chrome)
        val html = BrowserBookmarks.export(list)
        assertTrue(html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        val back = BrowserBookmarks.parse(html)
        assertEquals(list.map { Triple(it.url, it.title, it.folder) }.sortedBy { it.first }, back.map { Triple(it.url, it.title, it.folder) }.sortedBy { it.first })
        assertEquals(list.first { it.url.contains("taobao") }.at, back.first { it.url.contains("taobao") }.at)
    }

    @Test
    fun entities() {
        assertEquals("a<b>\"'&中 ", BrowserBookmarks.unescape("a&lt;b&gt;&quot;&#39;&amp;&#x4E2D;&nbsp;"))
    }
}
