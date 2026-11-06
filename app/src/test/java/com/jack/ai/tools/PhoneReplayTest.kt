package com.jack.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneReplayTest {
    @Test fun findsOnlyBlanksInsideStrings() {
        val json = """[{"action":"open","app":"com.tencent.mm"},{"action":"tap","text":"搜索"},{"action":"type","content":"{联系人}"},{"action":"back"}]"""
        assertEquals(listOf("联系人"), replayBlanks(json))
    }
}
