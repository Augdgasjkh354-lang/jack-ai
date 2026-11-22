package com.jack.ai.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubTest {
    private fun cue(s: Long, e: Long) = Subtitles.Cue(s, e, "x")

    @Test fun fitsAtOriginalTime() {
        val p = Dub.plan(listOf(cue(1000, 3000), cue(4000, 6000)), listOf(1800, 1500))
        assertEquals(1000, p[0]!!.startMs); assertEquals(1f, p[0]!!.speed); assertEquals(Dub.Fit.OK, p[0]!!.fit)
        assertEquals(4000, p[1]!!.startMs)
    }

    @Test fun speedsUpWhenALittleLong() {
        // 能用 1000→3920，配音 3500：要 1.19 倍
        val p = Dub.plan(listOf(cue(1000, 3000), cue(4000, 6000)), listOf(3500, 1000))
        assertEquals(Dub.Fit.FAST, p[0]!!.fit)
        assertTrue(p[0]!!.speed in 1.15f..1.25f)
        assertEquals(4000, p[1]!!.startMs)
    }

    @Test fun flagsTooLongAndPushesNext() {
        val p = Dub.plan(listOf(cue(0, 1000), cue(1000, 2000)), listOf(3000, 500))
        assertEquals(Dub.Fit.LONG, p[0]!!.fit)
        assertEquals(Dub.MAX_SPEED, p[0]!!.speed)
        assertTrue(p[1]!!.startMs > 1000)
        assertTrue(Dub.shorter("一二三四五六七八九十", p[0]!!) < 10)
    }

    @Test fun skipsEmptyLines() {
        val p = Dub.plan(listOf(cue(0, 1000), cue(1000, 2000), cue(5000, 6000)), listOf(800, 0, 800))
        assertNull(p[1])
        // 第一句可以一直用到第三句开始
        assertEquals(1f, p[0]!!.speed)
    }

    @Test fun laysAtOffsetsAndPadsToVideo() {
        val rate = 1000
        val places = Dub.plan(listOf(cue(500, 1000)), listOf(100))
        val pcm = ByteArray(200) { 7 }
        val out = Dub.lay(places, listOf(pcm), rate, minMs = 2000)
        assertTrue(out.size >= 4000)
        assertEquals(0, out[999].toInt())
        assertEquals(7, out[1000].toInt())
    }

    @Test fun stretchShortens() {
        val rate = 24000
        val pcm = ByteArray(rate * 2) { i -> ((i / 2 % 50) * 300 shr (8 * (i % 2))).toByte() }
        val out = Dub.stretch(pcm, rate, 1.25f)
        val ratio = out.size.toDouble() / pcm.size
        assertTrue("ratio $ratio", ratio in 0.75..0.85)
    }
}
