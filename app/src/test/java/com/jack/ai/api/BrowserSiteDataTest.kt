package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSiteDataTest {
    @Test
    fun root() {
        assertEquals("taobao.com", BrowserSiteData.root("m.taobao.com"))
        assertEquals("taobao.com", BrowserSiteData.root("taobao.com"))
        assertEquals("bbc.co.uk", BrowserSiteData.root("www.bbc.co.uk"))
        assertEquals("sina.com.cn", BrowserSiteData.root("news.sina.com.cn"))
        assertEquals("192.168.1.1", BrowserSiteData.root("192.168.1.1"))
    }

    @Test
    fun belongs() {
        assertTrue(BrowserSiteData.belongs("login.taobao.com", "taobao.com"))
        assertFalse(BrowserSiteData.belongs("nottaobao.com", "taobao.com"))
    }

    @Test
    fun levelsAndPaths() {
        assertEquals(listOf("www.a.taobao.com", "a.taobao.com", "taobao.com"), BrowserSiteData.levels("www.a.taobao.com", "taobao.com"))
        assertEquals(listOf("/", "/a", "/a/b"), BrowserSiteData.paths("/a/b"))
        assertEquals(listOf("/"), BrowserSiteData.paths(""))
    }

    @Test
    fun names() {
        assertEquals(listOf("a", "__Host-b"), BrowserSiteData.names("a=1; __Host-b=x=y; a=2"))
        assertEquals(emptyList<String>(), BrowserSiteData.names(null))
    }

    @Test
    fun expireCoversHostOnlyAndDomainCookies() {
        val out = BrowserSiteData.expire(listOf("sid"), listOf("m.taobao.com"), "taobao.com", "/")
        assertTrue(out.contains("https://m.taobao.com" to "sid=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure"))
        assertTrue(out.contains("https://m.taobao.com" to "sid=; Domain=taobao.com; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure"))
        assertTrue(out.any { it.first == "http://m.taobao.com" && !it.second.contains("Secure") })
    }

    @Test
    fun sizeText() {
        assertEquals("0", BrowserSiteData.sizeText(0))
        assertEquals("2 KB", BrowserSiteData.sizeText(2048))
        assertEquals("1.5 MB", BrowserSiteData.sizeText(1024 * 1536))
    }
}
