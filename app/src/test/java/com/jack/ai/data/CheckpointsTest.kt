package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheckpointsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun restoresChangedDeletedAndNewFiles() {
        val ws = tmp.newFolder("ws")
        val store = tmp.newFolder("cp")
        File(ws, "a.txt").writeText("一")
        File(ws, "sub").mkdirs(); File(ws, "sub/b.md").writeText("二")
        assertTrue(Checkpoints.save(ws, store, "start_1"))
        // 这一轮改了 a、删了 b、新建了 c 和一个子目录
        File(ws, "a.txt").writeText("改坏了")
        File(ws, "sub/b.md").delete()
        File(ws, "new/deep").mkdirs(); File(ws, "new/deep/c.txt").writeText("新")
        assertTrue(Checkpoints.save(ws, store, "end_1"))
        val r = Checkpoints.restore(ws, store, "start_1")!!
        assertEquals("一", File(ws, "a.txt").readText())
        assertEquals("二", File(ws, "sub/b.md").readText())
        assertFalse(File(ws, "new").exists())
        assertEquals(3, r.total)
        // 切回那个版本：文件跟着回来
        Checkpoints.restore(ws, store, "end_1")!!
        assertEquals("改坏了", File(ws, "a.txt").readText())
        assertEquals("新", File(ws, "new/deep/c.txt").readText())
        assertFalse(File(ws, "sub/b.md").exists())
    }

    @Test fun startIsOnlyRecordedOnce() {
        val ws = tmp.newFolder("ws")
        val store = tmp.newFolder("cp")
        File(ws, "a.txt").writeText("原来")
        Checkpoints.save(ws, store, "start_1", overwrite = false)
        File(ws, "a.txt").writeText("后来")
        Checkpoints.save(ws, store, "start_1", overwrite = false)
        Checkpoints.restore(ws, store, "start_1")
        assertEquals("原来", File(ws, "a.txt").readText())
    }

    @Test fun skipsDependencyDirsAndMissingCheckpoint() {
        val ws = tmp.newFolder("ws")
        val store = tmp.newFolder("cp")
        Checkpoints.save(ws, store, "start_1")
        File(ws, "node_modules/x").mkdirs(); File(ws, "node_modules/x/i.js").writeText("1")
        Checkpoints.restore(ws, store, "start_1")
        assertTrue(File(ws, "node_modules/x/i.js").exists())
        assertNull(Checkpoints.restore(ws, store, "start_nope"))
    }

    @Test fun identicalContentIsStoredOnce() {
        val ws = tmp.newFolder("ws")
        val store = tmp.newFolder("cp")
        File(ws, "a.txt").writeText("同样")
        File(ws, "b.txt").writeText("同样")
        Checkpoints.save(ws, store, "s1")
        Checkpoints.save(ws, store, "s2")
        assertEquals(1, File(store, "blobs").listFiles()!!.size)
    }
}
