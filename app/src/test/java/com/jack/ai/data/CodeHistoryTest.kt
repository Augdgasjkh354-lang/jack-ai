package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodeHistoryTest {
    private fun dir() = Files.createTempDirectory("ch").toFile()

    @Test fun savesBeforeChangeAndRestores() {
        val d = dir()
        val f = File(d, "game.py").apply { writeText("v1\n") }
        CodeHistory.save(f, "edit_file", now = 1000); f.writeText("v2\n")
        CodeHistory.save(f, "edit_file", now = 2000); f.writeText("v3 broken\n")
        // 内容没变不重复存
        CodeHistory.save(f, "edit_file", now = 2500)
        val vs = CodeHistory.versions(f)
        assertEquals(3, vs.size)
        assertEquals("v3 broken\n", vs[0].file.readText())
        CodeHistory.restore(f, 2)
        assertEquals("v2\n", f.readText())
        // 退回前的样子也存了一版
        assertEquals("v3 broken\n", CodeHistory.versions(f)[0].file.readText())
        // 旧版本放在隐藏目录，不出现在文件列表里
        assertEquals(listOf("game.py"), Workspace.files(d).map { it.name })
    }

    @Test fun skipsNonCode() {
        val d = dir()
        val f = File(d, "a.docx").apply { writeText("x") }
        CodeHistory.save(f, "write_file")
        assertTrue(CodeHistory.versions(f).isEmpty())
    }

    @Test fun diffShowsChangedLinesWithContext() {
        val old = (1..10).joinToString("\n") { "line $it" }
        val new = old.replace("line 5", "line five")
        val d = CodeHistory.diff(old, new)
        assertTrue(d, d.contains("-line 5") && d.contains("+line five") && d.contains(" line 4") && !d.contains("line 1\n"))
        assertEquals("两版一样。", CodeHistory.diff("a", "a"))
    }

    @Test fun searchFindsLines() {
        val d = dir()
        File(d, "main.py").writeText("def move():\n    pass\nmove()\n")
        File(d, "b.js").writeText("function move(){}\n")
        val r = CodeSearch.search(d, "move", onlyExt = "py")
        assertTrue(r, r.contains("main.py:1:") && r.contains("main.py:3:") && !r.contains("b.js"))
        assertTrue(CodeSearch.search(d, "nothing").startsWith("在"))
    }
}
