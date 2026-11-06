package com.jack.ai.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOcrTest {
    @Test fun listsOnlyTextTheTreeMissedInScreenshotCoordinates() {
        val lines = listOf(ScreenOcr.Line("开始游戏", 540, 1600), ScreenOcr.Line("设置", 100, 200), ScreenOcr.Line("排行榜", 540, 1800))
        val out = ScreenOcr.format(lines, known = listOf("设置"), k = 2f)
        assertTrue(out, out.contains("「开始游戏」[270,800]"))
        assertTrue(out.indexOf("开始游戏") < out.indexOf("排行榜"))
        assertEquals(false, out.contains("「设置」"))
        assertEquals("", ScreenOcr.format(lines.take(0), emptyList(), 1f))
    }
}
