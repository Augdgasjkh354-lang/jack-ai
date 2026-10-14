package com.jack.ai.data

import org.junit.Assert.assertTrue
import org.junit.Test

class JsRunnerTest {
    @Test
    fun basics() {
        val r = JsRunner.run("const rate = 0.039 / 12; let p = 1000000; const n = 360;\nconst m = p * rate * Math.pow(1 + rate, n) / (Math.pow(1 + rate, n) - 1);\nconsole.log('月供', m.toFixed(2)); [1,2,3].map(x => x * 2)")
        println(r)
        assertTrue(r, r.contains("月供 4716.68") && r.contains("[2,4,6]"))
    }

    @Test
    fun errorsAndTimeout() {
        assertTrue(JsRunner.run("foo(").startsWith("出错"))
        val t = JsRunner.run("while (true) {}")
        println(t)
        assertTrue(t, t.contains("超时"))
    }
}
