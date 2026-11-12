package com.jack.ai

import com.jack.ai.data.BizStarts
import com.jack.ai.ui.Hue
import com.jack.ai.ui.skyGlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DesignTest {
    @Test
    fun `老色值归到最接近的分类色`() {
        assertEquals(Hue.BLUE, Hue.of(0xFF3B82F6))
        assertEquals(Hue.VIOLET, Hue.of(0xFF8B5CF6))
        assertEquals(Hue.GREEN, Hue.of(0xFF22A06B))
        assertEquals(Hue.RED, Hue.of(0xFFEF5A5A))
        assertEquals(Hue.AMBER, Hue.of(0xFFE8A33D))
        assertEquals(Hue.GRAY, Hue.of(0xFF6B7280))
    }

    @Test
    fun `常用开场的颜色都认得出、不全挤成一种`() {
        val hues = BizStarts.ALL.map { Hue.of(it.tint) }
        assertEquals(BizStarts.ALL.size, hues.size)
        assert(hues.toSet().size >= 5) { "颜色挤在一起了：$hues" }
    }

    @Test
    fun `天光跟着钟点换`() {
        assertEquals(skyGlow(23), skyGlow(2))
        assertNotEquals(skyGlow(6), skyGlow(12))
        assertNotEquals(skyGlow(12), skyGlow(18))
        assertNotEquals(skyGlow(18), skyGlow(23))
    }
}
