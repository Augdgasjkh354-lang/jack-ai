package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    @Test fun queryAtStartAndAfterChinese() {
        assertEquals("", mentionQuery("@", 1))
        assertEquals("写", mentionQuery("帮我@写", 4))
        assertEquals("ppt", mentionQuery("hi @ppt", 7))
    }

    @Test fun noQueryInEmailOrAfterSpace() {
        assertNull(mentionQuery("jack@gm", 7))
        assertNull(mentionQuery("@ppt 做个", 6))
        assertNull(mentionQuery("没有", 2))
    }

    @Test fun candidatesPreferPrefixAndSkipUsed() {
        val names = listOf("PPT 助手", "做PPT", "写作")
        assertEquals(listOf("PPT 助手", "做PPT"), mentionCandidates(names, "ppt", "@ppt"))
        assertEquals(listOf("做PPT", "写作"), mentionCandidates(names, "", "@PPT 助手 @"))
    }

    @Test fun completeReplacesQuery() {
        assertEquals("帮我@写作 " to 6, completeMention("帮我@写", 4, "写作"))
        assertEquals("@写作 后面" to 4, completeMention("@写 后面", 2, "写作"))
    }
}
