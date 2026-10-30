package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 交付清单：比对文件夹前后，分出新建、改动、删除，文字文件数加删了几行；spill 文件只认自己的格式。 */
class DeliverablesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun diffFindsNewChangedAndDeleted() {
        val dir = tmp.newFolder("ws")
        dir.resolve("report.md").writeText("一\n二\n三")
        dir.resolve("old.txt").writeText("x")
        dir.resolve("same.csv").writeText("a,b")
        val before = Deliverables.snapshot(dir)
        dir.resolve("report.md").apply { writeText("一\n二改\n三\n四"); setLastModified(lastModified() + 2000) }
        dir.resolve("old.txt").delete()
        dir.resolve("new.md").writeText("甲\n乙")
        dir.resolve(".hidden").writeText("不算")
        val d = Deliverables.diff(before, dir).associateBy { it.name }
        assertEquals(3, d.size)
        assertEquals(Delivered("report.md", "changed", 2, 1), d["report.md"])
        assertEquals(Delivered("new.md", "new", 2), d["new.md"])
        assertEquals("deleted", d["old.txt"]!!.kind)
        assertEquals("新建 1 个 · 改动 1 个 · 删除 1 个", Deliverables.summary(d.values.toList()))
    }

    @Test
    fun spillSavesByContentAndOnlyReadsItsOwnNames() {
        val ws = tmp.newFolder("ws2")
        val name = Spill.save(ws, "全文")!!
        assertEquals(name, Spill.save(ws, "全文"))
        assertTrue(name.startsWith(".spill/r"))
        assertNotNull(Spill.file(ws, name))
        assertEquals("全文", Spill.file(ws, name)!!.readText())
        assertNull(Spill.file(ws, ".spill/../../secret.txt"))
        assertNull(Spill.file(ws, "report.md"))
    }
}
