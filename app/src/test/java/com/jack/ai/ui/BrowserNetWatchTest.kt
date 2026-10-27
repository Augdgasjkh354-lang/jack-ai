package com.jack.ai.ui

import com.jack.ai.data.BrowserStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台请求：读得懂网页给的状态，只报同一个网站、真失败的；成功率按网站算。 */
class BrowserNetWatchTest {
    @Test
    fun parsesWhatThePageReturns() {
        // evaluateJavascript 回来的是带引号的 JSON 字符串
        assertEquals(2 to 5, BrowserNetWatch.state("\"{\\\"inflight\\\":2,\\\"seq\\\":5}\""))
        assertNull(BrowserNetWatch.state("null"))
        val fails = BrowserNetWatch.fails("\"[{\\\"i\\\":6,\\\"m\\\":\\\"POST\\\",\\\"u\\\":\\\"/api/order\\\",\\\"s\\\":500}]\"")
        assertEquals(listOf(BrowserNetWatch.Fail("POST", "/api/order", 500)), fails)
    }

    @Test
    fun onlySameSiteRealFailuresCount() {
        val fails = listOf(
            BrowserNetWatch.Fail("POST", "/api/order", 500),                       // 同站相对地址：算
            BrowserNetWatch.Fail("GET", "https://h5api.m.taobao.com/x", 503),      // 同站子域名：算
            BrowserNetWatch.Fail("GET", "https://ads.other.com/pixel", 404),       // 别的网站（广告、统计）：不算
            BrowserNetWatch.Fail("GET", "/api/suggest", 0),                        // GET 连不上（取消、被拦）：不算
            BrowserNetWatch.Fail("POST", "/api/pay", 0),                           // 提交连不上：算
        )
        val got = BrowserNetWatch.relevant(fails, "m.taobao.com").map { it.url }
        assertEquals(listOf("/api/order", "https://h5api.m.taobao.com/x", "/api/pay"), got)
        assertEquals("example.com.cn", BrowserNetWatch.base("shop.example.com.cn"))
        assertEquals("POST /api/order → 500", BrowserNetWatch.describe(fails[0]))
    }

    @Test
    fun successRateBySite() {
        val now = 1_000_000_000_000L
        val ev = listOf("ok", "ok", "relocated", "miss").map { BrowserStats.Event(now - 1000, "jd.com", "click", it) } +
            BrowserStats.Event(now - 1000, "taobao.com", "click", "request_failed") +
            BrowserStats.Event(now - 40L * 24 * 3600 * 1000, "old.com", "click", "error")  // 30 天前的不算
        val (all, bySite) = BrowserStats.rates(ev, now)
        assertEquals(5, all.total)
        assertEquals(60, all.percent)
        assertEquals(listOf("jd.com", "taobao.com"), bySite.map { it.site })
        assertEquals(75, bySite[0].percent)
        assertEquals("request_failed", bySite[1].worst)
        assertTrue(BrowserStats.summary(emptyList()).isEmpty())
    }
}
