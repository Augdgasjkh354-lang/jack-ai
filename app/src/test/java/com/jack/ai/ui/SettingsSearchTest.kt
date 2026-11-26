package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun keys(q: String) = searchSettings(q).map { it.topic.key }

    @Test fun titleHitsComeFirst() {
        val k = keys("模型")
        assertEquals(listOf("PROVIDERS", "MODEL", "LOCAL"), k.take(3))
    }

    @Test fun keywordFindsPage() {
        assertEquals(listOf("LOOK"), keys("字体"))
        assertTrue("PRIVACY" in keys("备份"))
        assertTrue("KEY" in keys("余额"))
    }

    @Test fun sentenceContainingKeywordStillMatches() {
        assertTrue("LOOK" in keys("怎么改深色"))
    }

    @Test fun connectRowsOpenDirectly() {
        val h = searchSettings("邮件").first { it.topic.key == "CONNECT" }
        assertEquals("邮箱", h.row)
        assertEquals("Kavi 专用代理（VPN）", searchSettings("vpn").first().row)
    }

    @Test fun blankAndUnknownGiveNothing() {
        assertTrue(searchSettings("  ").isEmpty())
        assertTrue(searchSettings("完全不相干的词").isEmpty())
    }
}
