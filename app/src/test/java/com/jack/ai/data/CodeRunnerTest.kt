package com.jack.ai.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 思路来自 deepseek-ai/deepseek-harness packages/ptc-runtime/，MIT。 */
class CodeRunnerTest {
    @Test fun rejectsWritesAndKeepsLogsAndReturn() = runBlocking {
        val result = CodeRunner.run("print(await tools.send_email({})); print(await tools.read_email({save_attachments:true})); return 7;") { _, _ -> error("不能执行") }
        assertTrue(result.contains("普通工具调用")); assertTrue(result.contains("返回值：7"))
    }
    @Test fun stopsAtThirtyAndKeepsPartialResults() = runBlocking {
        var calls = 0
        val result = CodeRunner.run("for(var i=0;i<35;i++){ await tools.read_email({id:i}); }") { _, _ -> "邮件${++calls}" }
        assertEquals(30, calls); assertTrue(result.contains("邮件30")); assertTrue(result.contains("最多调用 30"))
    }
    @Test fun cancelsSlowCallAndKeepsOutput() = runBlocking {
        val result = CodeRunner.run("print('开始'); await tools.read_email({});", 500) { _, _ -> delay(1500); "太晚" }
        assertTrue(result.contains("开始")); assertTrue(result.contains("停止原因")); assertFalse(result.contains("太晚"))
    }
    @Test fun leavesStringsAndCommentsUntouched() {
        assertEquals("print('await tools.x'); // await tools.x\n tools.read_file({});", CodeRunner.sequential("print('await tools.x'); // await tools.x\nawait tools.read_file({});"))
        assertEquals("var x = 6 / 2;  tools.read_file({});", CodeRunner.sequential("var x = 6 / 2; await tools.read_file({});"))
    }
}
