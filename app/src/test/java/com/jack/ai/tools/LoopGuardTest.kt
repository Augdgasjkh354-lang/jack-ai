package com.jack.ai.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LoopGuardTest {
    @Test
    fun stopsThirdIdenticalCallInARow() {
        val g = LoopGuard()
        assertNull(g.check("web_search", """{"query":"a"}"""))
        assertNull(g.check("web_search", """{"query":"a"}"""))
        assertNotNull(g.check("web_search", """{"query":"a"}"""))
    }

    @Test
    fun differentArgsOrInterleavedReadsAreFine() {
        val g = LoopGuard()
        repeat(4) { i ->
            assertNull(g.check("read_file", """{"name":"a.txt"}"""))
            assertNull(g.check("edit_file", """{"name":"a.txt","old_text":"$i"}"""))
        }
        // 隔着别的调用，同一个搜索第 5 次也拦
        val h = LoopGuard()
        repeat(4) { assertNull(h.check("web_search", """{"query":"x"}""")); h.check("read_webpage", """{"url":"u$it"}""") }
        assertNotNull(h.check("web_search", """{"query":"x"}"""))
    }
}
