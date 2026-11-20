package com.jack.ai

import com.jack.ai.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EditionTest {
    @Test fun devKeepsEverything() {
        // 单测跑在开发版上：设置原样不动
        assertFalse(Edition.STORE)
        val s = Settings(phoneApps = listOf("com.tencent.mm"), proxyEnabled = true, linuxSandbox = true, noticeWatch = true, autoUpdate = true)
        assertEquals(s, Edition.limit(s))
    }
}
