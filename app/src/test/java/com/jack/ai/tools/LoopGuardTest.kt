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

    /** 往下翻、读屏幕这类：参数相同但结果在变，翻多少次都不拦。 */
    @Test
    fun statefulToolsWithChangingResultsAreNeverBlocked() {
        val g = LoopGuard()
        val scroll = """{"action":"scroll","direction":"down"}"""
        repeat(20) { i ->
            assertNull(g.check("phone", scroll))
            g.record("phone", scroll, "第 $i 屏")
        }
    }

    /** 翻到底了（连着两次结果一样）：第三次拦；做了别的再来就放行。 */
    @Test
    fun statefulToolsStopOnlyWhenResultRepeats() {
        val g = LoopGuard()
        val scroll = """{"action":"scroll"}"""
        repeat(2) { assertNull(g.check("browser", scroll)); g.record("browser", scroll, "到底了") }
        assertNotNull(g.check("browser", scroll))
        assertNotNull(g.check("browser", scroll))
        assertNull(g.check("browser", """{"action":"back"}"""))
        g.record("browser", """{"action":"back"}""", "上一页")
        assertNull(g.check("browser", scroll))
    }
}
