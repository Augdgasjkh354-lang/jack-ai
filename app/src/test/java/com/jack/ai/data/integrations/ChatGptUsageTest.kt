package com.jack.ai.data.integrations

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChatGptUsageTest {
    @Before fun clean() = ChatGptUsage.reset()
    @After fun after() = ChatGptUsage.reset()

    @Test fun calibrationTurnsUsageIntoPercent() {
        val t0 = 1_000_000_000_000L
        // 10 万输入（其中 6 万命中缓存）、1 万输出：40000 + 6000 + 80000 = 126000 单位
        ChatGptUsage.record(100_000, 60_000, 10_000, now = t0)
        assertNull(ChatGptUsage.windows(t0)[0].percent)
        // 用量页说 5 小时已用 12.6%、每周 3%
        ChatGptUsage.calibrate(listOf(12.6, 3.0), now = t0)
        val w = ChatGptUsage.windows(t0)
        assertEquals(12.6, w[0].percent!!, 0.001)
        assertEquals(3.0, w[1].percent!!, 0.001)
        // 再用同样多：百分比翻倍
        ChatGptUsage.record(100_000, 60_000, 10_000, now = t0 + 60_000)
        assertEquals(25.2, ChatGptUsage.windows(t0 + 60_000)[0].percent!!, 0.001)
        // 6 小时后，5 小时窗口里只剩后来那次之后的（都过期了）
        assertEquals(0.0, ChatGptUsage.windows(t0 + 6 * 3600_000L)[0].percent!!, 0.001)
    }

    @Test fun zeroOrEmptyDoesNotCalibrate() {
        ChatGptUsage.calibrate(listOf(10.0, null), now = 5L)
        assertNull(ChatGptUsage.windows(5L)[0].percent)
    }
}
