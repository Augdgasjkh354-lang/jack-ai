package com.jack.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimTest {
    @Test fun sizes() {
        assertEquals(1080 to 1920, Anim.size("9:16"))
        assertEquals(1920 to 1080, Anim.size("16：9"))
        // 太大的按最长边 1920 缩，取双数
        assertEquals(1920 to 1080, Anim.size("2400x1350"))
        assertEquals(1080 to 1440, Anim.size("xhs"))
        assertNull(Anim.size("long"))
        assertNull(Anim.size("abc"))
    }

    @Test fun attrsRoundTrip() {
        val h = Anim.withAttrs("<html lang=\"zh\"><body><div class=\"canvas\"></div></body></html>", "1080x1920", 12.5)
        assertEquals("1080x1920", Anim.attr(h, "data-anim-size"))
        assertEquals("12.50", Anim.attr(h, "data-anim-duration"))
        val again = Anim.withAttrs(h, "1080x1080", 20.0)
        assertEquals("20", Anim.attr(again, "data-anim-duration"))
        assertEquals(1, Regex("data-anim-size").findAll(again).count())
        assertTrue(Anim.withAttrs("<div class=\"canvas\"></div>", "1080x1920", 5.0).startsWith("<!doctype html><html data-anim-size"))
    }

    @Test fun previewTimes() {
        val d = Anim.previewTimes(null, 30.0)
        assertEquals(6, d.size)
        assertTrue(d.first() > 0 && d.last() < 30.0)
        assertEquals(listOf(1.0, 2.5), Anim.previewTimes(JsonArray(listOf(JsonPrimitive(2.5), JsonPrimitive(1), JsonPrimitive(99))), 30.0))
    }
}
