package com.jack.ai.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 国内电商连同它们的图片、风控、支付域名都直连：一部分走代理的话，网站会断开或要求登录验证。 */
class DomesticRouteTest {
    @Test
    fun shoppingSitesAndTheirHelpersGoDirect() {
        listOf("s.1688.com", "detail.1688.com", "img14.360buyimg.com", "search.jd.com", "g.alicdn.com", "log.mmstat.com",
            "mobile.yangkeduo.com", "www.mi.com", "consumer.huawei.com", "p3-pc.douyinpic.com", "www.zhipin.com")
            .forEach { assertFalse(it, Net.foreign(it)) }
    }

    @Test
    fun foreignSitesStillGoThroughTheProxy() {
        listOf("www.google.com", "tinypng.com", "github.com", "en.wikipedia.org", "notmi.com.evil.io")
            .forEach { assertTrue(it, Net.foreign(it)) }
    }
}
