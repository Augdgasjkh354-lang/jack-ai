package com.wangzan101.mika.core.cap.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BrowserPacingBarrierTest {
    @Test fun sameSiteOnlyWaitsFromSecondPageAndHasUpperBound() {
        val limits = PagePacing.Limits(firstDwellMs = 100L..100L,
            perPageMs = 50L to 50L, maxDwellMs = 200L)
        val pace = PagePacing(Random(3), limits)
        assertEquals(0L, pace.noteLoad("https://www.shop.com/search?p=1", 1_000))
        assertEquals(0L, pace.waitBefore("https://shop.com/search", 1_000))
        assertEquals(100L, pace.noteLoad("https://m.shop.com/search?p=2", 1_100))
        assertEquals(80L, pace.waitBefore("https://shop.com/product", 1_120))
        assertEquals(0L, pace.waitBefore("https://another.com/", 1_120))
        pace.served("https://shop.com")
        assertEquals(0L, pace.waitBefore("https://shop.com/", 1_120))
        assertEquals(150L, pace.noteLoad("https://shop.com/search?p=3", 1_200))
        assertEquals(200L, pace.noteLoad("https://shop.com/search?p=4", 1_300))
        assertEquals(200L, pace.noteLoad("https://shop.com/search?p=5", 1_400))
    }

    @Test fun mainHttpLimitsAndShortInterstitialsRequireHumanButArticlesDoNot() {
        for (status in listOf(403, 429, 503)) {
            val block = WebBarrier.classify("https://shop.com", "商品列表", "", status, emptyList())
            assertEquals(WebBarrier.Kind.LIMITED, block?.kind)
        }
        assertEquals(WebBarrier.Kind.LIMITED,
            WebBarrier.classify("https://shop.com", "安全检查",
                "您操作过于频繁，请稍后重试", 200, emptyList())?.kind)
        val article = "如何排查访问异常：" + "这是解释网站风控的技术文章。".repeat(70)
        assertNull(WebBarrier.classify("https://shop.com", "风控教程", article, 200, emptyList()))
        assertNull(WebBarrier.classify("https://shop.com", "商品页", "正常浏览", 200, emptyList()))
    }

    @Test fun captchaFramesAndTopLevelApiErrorsAreRecognized() {
        assertEquals(WebBarrier.Kind.CAPTCHA, WebBarrier.classify("https://shop.com",
            "商品页", "正常内容", 200,
            listOf("https://www.google.com/recaptcha/api2/anchor"))?.kind)
        assertEquals(WebBarrier.Kind.CAPTCHA, WebBarrier.classify("https://shop.com",
            "商品页", "正常内容", 200,
            listOf("https://newassets.hcaptcha.com/captcha/v1/check"))?.kind)
        assertNull(WebBarrier.classify("https://shop.com", "商品页", "正常内容",
            200, listOf("https://www.google.com/maps/embed")))
        assertNull(WebBarrier.classify("https://shop.com", "商品页", "正常内容",
            200, listOf("https://hcaptcha.com.evil.test/frame")))
        assertEquals("Too Many Requests", WebBarrier.apiRisk("""{"code":429,"error":"Too Many Requests"}"""))
        assertNull(WebBarrier.apiRisk("""{"code":0,"data":{"title":"访问异常排查指南"}}"""))
        assertTrue(WebBarrier.classify("https://shop.com", "商品页", "正常内容",
            200, emptyList(), "请求过于频繁") != null)
    }
}
