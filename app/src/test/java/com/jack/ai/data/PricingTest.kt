package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PricingTest {
    private fun bj(s: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.parse(s)!!.time

    @Test
    fun peakHours() {
        assertTrue(Pricing.isPeak(bj("2026-10-16 09:00")))   // 周五上午
        assertTrue(Pricing.isPeak(bj("2026-10-16 17:59")))
        assertFalse(Pricing.isPeak(bj("2026-10-16 12:30")))  // 午休
        assertFalse(Pricing.isPeak(bj("2026-10-16 18:00")))
        assertFalse(Pricing.isPeak(bj("2026-10-16 08:59")))
        assertFalse(Pricing.isPeak(bj("2026-10-17 10:00")))  // 周六
        assertFalse(Pricing.isPeak(bj("2026-10-05 10:00")))  // 国庆假期里的周一
        assertFalse(Pricing.isPeak(bj("2026-02-18 10:00")))  // 春节假期
    }

    @Test
    fun nextOffPeakJumpsToEndOfPeak() {
        assertEquals(bj("2026-10-16 12:00"), Pricing.nextOffPeak(bj("2026-10-16 10:15")))
        val idle = bj("2026-10-16 13:00")
        assertEquals(idle, Pricing.nextOffPeak(idle))
    }

    @Test
    fun deepseekPriceHalvesOffPeak() {
        val ep = Settings(apiKey = "k").endpoint(MODEL_FLASH)!!
        val peak = Pricing.priceAt(ep, bj("2026-10-16 10:00"))!!
        val idle = Pricing.priceAt(ep, bj("2026-10-16 20:00"))!!
        assertEquals(2.0, peak.miss, 1e-9)
        assertEquals(1.0, idle.miss, 1e-9)
        // 100 万输入其中 60 万命中，10 万输出（高峰）：0.6×0.04 + 0.4×2 + 0.1×8
        assertEquals(1.624, Pricing.cost(peak, 1_000_000, 600_000, 100_000), 1e-9)
    }

    @Test
    fun customModelUsesItsOwnPrice() {
        val s = Settings(providers = listOf(Provider(id = "p", name = "x", baseUrl = "u",
            models = listOf(ProviderModel("a", price = Price(0.5, 4.0, 16.0)), ProviderModel("b")))))
        assertEquals(4.0, Pricing.priceAt(s.endpoint(customModelKey("p", "a"))!!, 0)!!.miss, 1e-9)
        assertNull(Pricing.priceAt(s.endpoint(customModelKey("p", "b"))!!, 0))
    }

    @Test
    fun usageDayCost() {
        assertNull(UsageDay("2026-10-16", "c:p:b", requests = 3, costed = true, unpriced = 3).costYuan())
        assertEquals(1.5, UsageDay("2026-10-16", MODEL_FLASH, requests = 1, cost = 1.5, costed = true).costYuan()!!, 1e-9)
        // 旧记录按高峰价估
        assertEquals(2.0, UsageDay("2026-01-01", MODEL_FLASH, prompt = 1_000_000).costYuan()!!, 1e-9)
    }
}
