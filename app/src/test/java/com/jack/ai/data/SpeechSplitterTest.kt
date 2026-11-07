package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpeechSplitterTest {
    private val rate = AudioPcm.RATE

    /** (开始秒, 结束秒) 的每一句。 */
    private fun split(vararg parts: Pair<Double, Boolean>): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        val s = AudioPcm.SpeechSplitter({ start, _, n -> out += start.toDouble() / rate to (start + n).toDouble() / rate })
        var t = 0L
        for ((sec, voice) in parts) repeat((sec * rate).toInt()) {
            s.push(if (voice) 0.3f * sin(2 * PI * 220 * t / rate).toFloat() else 0.0005f * sin(t * 1.7).toFloat())
            t++
        }
        s.finish()
        return out
    }

    @Test fun cutsAtPausesAndSkipsLeadingSilence() {
        val segs = split(1.0 to false, 2.0 to true, 0.6 to false, 2.5 to true, 1.0 to false)
        assertEquals(2, segs.size)
        assertTrue("第一句从 1 秒左右开始：${segs[0]}", abs(segs[0].first - 1.0) < 0.2)
        assertTrue("第一句在停顿里结束：${segs[0]}", segs[0].second in 3.0..3.4)
        assertTrue("第二句从 3.6 秒左右开始：${segs[1]}", abs(segs[1].first - 3.6) < 0.25)
        assertTrue("第二句尾巴不带整秒静音：${segs[1]}", segs[1].second < 6.4)
    }

    @Test fun shortPauseInsideMinLengthDoesNotCut() {
        // 说 0.6 秒停 0.4 秒再说：不到 1.5 秒不切
        assertEquals(1, split(0.6 to true, 0.4 to false, 1.5 to true, 1.0 to false).size)
    }

    @Test fun nonStopSoundIsCutWithinMaxLength() {
        val segs = split(40.0 to true)
        assertTrue(segs.size >= 3)
        segs.forEach { assertTrue("一句不超过 15 秒：$it", it.second - it.first <= 15.05) }
        segs.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first, 0.001) }
        assertEquals(40.0, segs.last().second, 0.05)
    }

    @Test fun pureSilenceGivesNothing() {
        assertTrue(split(5.0 to false).isEmpty())
    }
}
