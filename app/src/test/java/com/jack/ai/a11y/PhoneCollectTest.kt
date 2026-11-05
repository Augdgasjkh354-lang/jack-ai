package com.jack.ai.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneCollectTest {
    @Test fun downMergesOverlapAndMarksGap() {
        var acc = PhoneCollect.Lines(listOf("a", "b", "c"))
        acc = PhoneCollect.merge(acc, listOf("b", "c", "d"), prepend = false)
        assertEquals(listOf("a", "b", "c", "d"), acc.lines)
        acc = PhoneCollect.merge(acc, listOf("x", "y"), prepend = false)
        assertEquals(listOf("a", "b", "c", "d", PhoneCollect.GAP, "x", "y"), acc.lines)
        assertEquals(1, acc.gaps)
    }

    @Test fun upPutsOlderScreenFirst() {
        val acc = PhoneCollect.merge(PhoneCollect.Lines(listOf("c", "d", "e")), listOf("a", "b", "c", "d"), prepend = true)
        assertEquals(listOf("a", "b", "c", "d", "e"), acc.lines)
        // 翻动很小、新的一屏整个已经有了：不重复加
        assertEquals(acc, PhoneCollect.merge(acc, listOf("b", "c"), prepend = true))
    }
}
