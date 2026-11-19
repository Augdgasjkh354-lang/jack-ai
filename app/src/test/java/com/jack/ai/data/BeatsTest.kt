package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BeatsTest {
    /** 合成一段鼓点：bpm 一拍一下（每 4 拍第一下更响），底下垫一点噪声和持续音。 */
    private fun drums(bpm: Double, sec: Int, offsetMs: Int = 120): Pair<Beats.Onsets, List<Long>> {
        val on = Beats.Onsets()
        val rnd = java.util.Random(7)
        val beatMs = 60_000 / bpm
        val hits = generateSequence(offsetMs.toDouble()) { it + beatMs }.takeWhile { it < sec * 1000 }.map { it.toLong() }.toList()
        var hi = 0
        for (i in 0 until sec * Beats.RATE) {
            val ms = i * 1000.0 / Beats.RATE
            while (hi + 1 < hits.size && hits[hi + 1] <= ms) hi++
            val since = ms - hits[hi]
            val amp = if (since in 0.0..80.0) (if (hi % 4 == 0) 0.9 else 0.5) * (1 - since / 80) else 0.0
            val s = amp * (rnd.nextDouble() * 2 - 1) + 0.05 * sin(2 * PI * 220 * i / Beats.RATE) + 0.01 * (rnd.nextDouble() * 2 - 1)
            on.push(s.toFloat())
        }
        return on to hits
    }

    @Test fun findsTempoAndBeats() {
        for (bpm in listOf(100.0, 128.0)) {
            val (on, hits) = drums(bpm, 20)
            val r = Beats.detect(on.flux(), on.samples)!!
            assertEquals("bpm", bpm, r.bpm, 2.5)
            // 每个拍子离真的鼓点不超过 50 毫秒
            val near = r.beatsMs.count { b -> hits.any { abs(it - b) <= 50 } }
            assertTrue("$bpm: $near / ${r.beatsMs.size}", near >= r.beatsMs.size * 0.9)
            // 重拍是每 4 拍响的那一下
            val strongHits = hits.filterIndexed { i, _ -> i % 4 == 0 }
            val strongNear = r.strongMs.count { b -> strongHits.any { abs(it - b) <= 50 } }
            assertTrue("strong $bpm: $strongNear / ${r.strongMs.size}", strongNear >= r.strongMs.size * 0.8)
            assertTrue(Beats.describe(r, "a.mp3").contains("BPM"))
        }
    }

    @Test fun tooShortIsNull() {
        val (on, _) = drums(120.0, 2)
        assertNull(Beats.detect(on.flux(), on.samples))
    }
}
