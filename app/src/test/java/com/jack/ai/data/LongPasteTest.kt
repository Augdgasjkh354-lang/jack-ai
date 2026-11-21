package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LongPasteTest {
    private val big = "长".repeat(LongPaste.THRESHOLD)

    @Test fun findsTheBigPasteWherever() {
        assertEquals(big, LongPaste.inserted("", big))
        assertEquals(big, LongPaste.inserted("帮我总结：", "帮我总结：$big"))
        assertEquals(big, LongPaste.inserted("前后", "前${big}后"))
    }

    @Test fun ignoresTypingAndReplacements() {
        assertNull(LongPaste.inserted("你好", "你好呀"))
        assertNull(LongPaste.inserted("短", "短" + "x".repeat(LongPaste.THRESHOLD - 1)))
        // 选中一段再贴（原来的字没了）不算
        assertNull(LongPaste.inserted("abc", "a$big"))
    }
}
