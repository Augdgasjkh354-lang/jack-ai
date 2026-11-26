package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrictionHelpersTest {
    @Test fun skillBlurbTakesFirstSentence() {
        assertEquals("把这周做的事整理成周报", skillBlurb("## 把这周做的事整理成周报。\n- 先列出每天"))
        assertEquals("先问用户要哪个城市", skillBlurb("\n\n- 先问用户要哪个城市！然后查"))
    }

    @Test fun skillBlurbCaps() {
        val b = skillBlurb("字".repeat(60))
        assertEquals(41, b.length)
        assertTrue(b.endsWith("…"))
    }

    @Test fun proxyLinks() {
        assertTrue(looksLikeProxy("https://sub.example.com/abc"))
        assertTrue(looksLikeProxy("vmess://eyJ2Ijo...\nss://abc"))
        assertTrue(looksLikeProxy("  Trojan://pw@host:443"))
        assertFalse(looksLikeProxy("随便复制的一句话"))
        assertFalse(looksLikeProxy(""))
    }
}
