package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileLibraryTest {
    @Test fun scansEveryConversationAndProjectFolderNewestFirst() {
        val root = Files.createTempDirectory("ws").toFile()
        try {
            val chat = Conversation(id = "c1", title = "旅行计划")
            val inProject = Conversation(id = "c2", title = "周报", projectId = "p1")
            val project = Project(id = "p1", name = "工作")
            File(root, "c1").mkdirs(); File(root, "p_p1").mkdirs(); File(root, "gone").mkdirs()
            File(root, "c1/行程.pdf").apply { writeText("x"); setLastModified(1_000_000) }
            File(root, "c1/.src").mkdirs()
            File(root, "c1/.hidden.txt").writeText("x")
            File(root, "p_p1/数据.xlsx").apply { writeText("x"); setLastModified(3_000_000) }
            File(root, "p_p1/照片.JPG").apply { writeText("x"); setLastModified(2_000_000) }
            File(root, "gone/孤儿.txt").writeText("x")

            val all = FileLibrary.scan(root, listOf(chat, inProject), listOf(project))
            assertEquals(listOf("数据.xlsx", "照片.JPG", "行程.pdf"), all.map { it.file.name })
            assertEquals(listOf(FileLibrary.Kind.SHEET, FileLibrary.Kind.IMAGE, FileLibrary.Kind.DOC), all.map { it.kind })
            assertEquals("项目「工作」", all[0].place)
            assertEquals("c2", all[0].convId)
            assertEquals("c1", all[2].convId)

            assertEquals(listOf("照片.JPG"), FileLibrary.filter(all, FileLibrary.Kind.IMAGE, "").map { it.file.name })
            assertEquals(listOf("行程.pdf"), FileLibrary.filter(all, null, "旅行").map { it.file.name })
            assertTrue(FileLibrary.filter(all, FileLibrary.Kind.MEDIA, "").isEmpty())
        } finally { root.deleteRecursively() }
    }
}
