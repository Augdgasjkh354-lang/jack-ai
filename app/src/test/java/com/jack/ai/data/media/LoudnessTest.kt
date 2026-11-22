package com.jack.ai.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LoudnessTest {
    private fun tone(m: Loudness, rate: Int, amp: Double, sec: Double, hz: Double = 1000.0) {
        val n = (rate * sec).toInt()
        for (i in 0 until n) m.push((amp * sin(2 * PI * hz * i / rate)).toFloat())
    }

    @Test fun sineAtMinus20dbfsIsAboutMinus23Lufs() {
        // BS.1770：1 kHz 正弦、峰值 -20 dBFS 的单声道约 -23 LUFS（K 加权在 1 kHz 约 +0.7 dB）
        val m = Loudness(16000)
        tone(m, 16000, 0.1, 5.0)
        val l = m.integrated()!!
        assertTrue("got $l", l in -24.0..-22.0)
    }

    @Test fun silenceIsNullAndFound() {
        val m = Loudness(16000)
        tone(m, 16000, 0.3, 2.0); tone(m, 16000, 0.0, 3.0); tone(m, 16000, 0.3, 2.0)
        val s = m.silences()
        assertEquals(1, s.size)
        assertTrue(s[0].first in 1900L..2100L && s[0].second in 4900L..5100L)
        val q = Loudness(16000); tone(q, 16000, 0.0, 2.0)
        assertNull(q.integrated())
    }

    @Test fun gainRespectsPeak() {
        assertEquals(0.0, Loudness.gainDb(-14.5, 0.5).first, 0.0)
        assertEquals(-6.0, Loudness.gainDb(-8.0, 1.0).first, 1e-9)
        assertEquals(6.0, Loudness.gainDb(-20.0, 0.3).first, 1e-9)
        val (g, limited) = Loudness.gainDb(-30.0, 0.5)   // 要 +16 dB，峰值只剩约 5 dB
        assertTrue(limited && g in 4.9..5.1)
    }

    @Test fun blackRuns() {
        val marks = listOf(0L to false, 500L to true, 1000L to true, 1500L to false, 2000L to true, 2500L to false)
        assertEquals(listOf(250L to 1250L), VideoQc.runs(marks, 500))
        assertTrue(VideoQc.isBlack(IntArray(16) { 0xFF050505.toInt() }))
        assertTrue(!VideoQc.isBlack(IntArray(16) { 0xFF808080.toInt() }))
    }
}
