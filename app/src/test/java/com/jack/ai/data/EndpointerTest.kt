package com.jack.ai.data

import com.jack.ai.data.media.Endpointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EndpointerTest {
    private val rnd = Random(1)
    private fun frame(ep: Endpointer, amp: Int) = ShortArray(ep.frame) { (rnd.nextInt(-amp, amp + 1)).toShort() }
    private fun feed(ep: Endpointer, amp: Int, ms: Int): Endpointer.State {
        var st = ep.state
        repeat(ms / 20) { st = ep.push(frame(ep, amp)) }
        return st
    }

    @Test fun hearsASentenceAndItsEnd() {
        val ep = Endpointer()
        assertEquals(Endpointer.State.WAITING, feed(ep, 150, 1000))   // 背景噪声
        assertEquals(Endpointer.State.SPEAKING, feed(ep, 8000, 1500)) // 说话
        assertEquals(Endpointer.State.SPEAKING, feed(ep, 150, 600))   // 句中停顿不算说完
        feed(ep, 8000, 500)
        assertEquals(Endpointer.State.DONE, feed(ep, 150, 1400))      // 停够 1.2 秒
        val sec = (ep.end - ep.start) / 16000.0
        assertTrue("录下 $sec 秒", sec in 2.5..3.6)
    }

    @Test fun givesUpWhenNobodySpeaks() {
        val ep = Endpointer(waitMs = 3000)
        assertEquals(Endpointer.State.TIMEOUT, feed(ep, 150, 3500))
    }
}
