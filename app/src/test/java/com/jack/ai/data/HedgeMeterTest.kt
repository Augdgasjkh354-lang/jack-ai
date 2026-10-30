package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HedgeMeterTest {
    @Test fun countsTurnsOnce() {
        assertEquals(1, HedgeMeter.count("这很好，但是不够。"))
        assertEquals(3, HedgeMeter.count("有道理。但这不意味着要改，而是要等。"))
        assertEquals(1, HedgeMeter.count("Good, but late."))
        assertEquals(0, HedgeMeter.count("直接去做。"))
    }

    @Test fun skipsCode() {
        assertEquals(0, HedgeMeter.count("看：\n```\nbut however\n```"))
    }

    @Test fun perThousand() {
        val r = HedgeMeter.read(listOf("但" + "字".repeat(99), "x".repeat(100)))
        assertEquals(5.0, r.perThousand, 0.001)
    }
}
