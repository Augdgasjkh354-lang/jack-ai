package com.jack.ai.tools

import com.jack.ai.ui.PageCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodeRunTest {
    @Test fun runFileCodeQuotesPathAndArgs() {
        val code = runFileCode("游戏 main.py", listOf("a\"b", "c\\d"))
        assertTrue(code, code.contains("""_sys.argv = ["游戏 main.py", "a\"b", "c\\d"]"""))
        assertTrue(code.contains("""run_path("游戏 main.py", run_name="__main__")"""))
        // 最后一行是 None：不把 run_path 返回的一大堆全局变量打印出来
        assertEquals("None", code.lines().last())
    }

    @Test fun pageResolveStaysInFolder() {
        val dir = Files.createTempDirectory("pc").toFile()
        File(dir, "index.html").writeText("<p>hi</p>")
        File(dir, "app.js").writeText("1")
        File(dir.parentFile, "secret.txt").writeText("x")
        assertEquals("index.html", PageCheck.resolve(dir, "/")!!.name)
        assertEquals("app.js", PageCheck.resolve(dir, "/app.js")!!.name)
        assertNull(PageCheck.resolve(dir, "/../secret.txt"))
        assertNull(PageCheck.resolve(dir, "/nope.js"))
    }

    @Test fun wrapCapturesReturnAndErrors() {
        val w = PageCheck.wrap("return 1")
        assertTrue(w.contains("return 1") && w.contains("__jackCheck") && w.contains("error"))
    }
}
