package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdsTranslateTest {
    @Test
    fun adHostsAndTheirSubdomainsAreBlocked() {
        assertTrue(BrowserAds.isAd("doubleclick.net"))
        assertTrue(BrowserAds.isAd("stats.g.doubleclick.net"))
        assertTrue(BrowserAds.isAd("pos.baidu.com"))
        assertTrue(BrowserAds.isAd("POS.BAIDU.COM."))
        // 同一家的正常网站不拦
        assertFalse(BrowserAds.isAd("www.baidu.com"))
        assertFalse(BrowserAds.isAd("baidu.com"))
        assertFalse(BrowserAds.isAd("google.com"))
        // 会连累登录和淘宝客跳转的不拦
        assertFalse(BrowserAds.isAd("s.click.alimama.com"))
        assertFalse(BrowserAds.isAd("notdoubleclick.net"))
    }

    @Test
    fun hideStyleIsAValidJsString() {
        assertTrue(BrowserAds.INJECT_JS.contains("s.textContent=\"ins.adsbygoogle"))
        assertTrue(BrowserAds.INJECT_JS.contains("\\\"google_ads_\\\""))
    }

    @Test
    fun batchesRespectCountAndLength() {
        val segs = List(120) { "x".repeat(10) }
        val b = BrowserTranslate.batches(segs, maxItems = 50, maxChars = 3000)
        assertEquals(listOf(50, 50, 20), b.map { it.size })
        assertEquals((0 until 120).toList(), b.flatten())
        val long = listOf("a".repeat(5000), "b", "c".repeat(2999), "d")
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), BrowserTranslate.batches(long, maxChars = 3000))
        assertTrue(BrowserTranslate.batches(emptyList()).isEmpty())
    }

    @Test
    fun requestAndParseUseTheSameIds() {
        val segs = listOf("Hello", "World", "Sign in")
        assertEquals("""{"1":"World","2":"Sign in"}""", BrowserTranslate.request(segs, listOf(1, 2)))
        val out = BrowserTranslate.parse("```json\n{\"1\": \"世界\", \"2\": \" 登录 \", \"7\": \"多余\", \"x\": \"坏\"}\n```", listOf(1, 2))
        assertEquals(mapOf(1 to "世界", 2 to "登录"), out)
        assertTrue(BrowserTranslate.parse("不是 JSON", listOf(1)).isEmpty())
        assertTrue(BrowserTranslate.parse("""{"1": ""}""", listOf(1)).isEmpty())
    }

    @Test
    fun segmentsUnwrapEvaluateJavascriptResult() {
        // evaluateJavascript 返回的是 JSON 字符串字面量，里面才是数组
        assertEquals(listOf("Hi", "A \"quote\""), BrowserTranslate.segments("\"[\\\"Hi\\\",\\\"A \\\\\\\"quote\\\\\\\"\\\"]\""))
        assertTrue(BrowserTranslate.segments("null").isEmpty())
    }

    @Test
    fun applyEscapesTranslations() {
        val js = BrowserTranslate.applyJs(mapOf(3 to "他说\"好\"</script>"))
        assertTrue(js, js.contains("""{"3":"他说\"好\"</script>"}"""))
    }
}
