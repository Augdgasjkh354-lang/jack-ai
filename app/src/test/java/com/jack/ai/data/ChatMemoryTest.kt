package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatMemoryTest {
    private val dir: File = Files.createTempDirectory("mem").toFile()

    @Test
    fun addWritesMarkdownAndSkipsDuplicates() {
        val f = File(dir, ChatMemory.CHAT_FILE)
        assertTrue(ChatMemory.add(f, "主角叫阿明"))
        assertTrue(ChatMemory.add(f, "- 阿明是左撇子"))
        assertFalse(ChatMemory.add(f, "主角叫阿明"))
        assertEquals("# 本对话记忆\n\n- 主角叫阿明\n- 阿明是左撇子\n", f.readText())
        assertEquals(listOf("主角叫阿明", "阿明是左撇子"), ChatMemory.items(ChatMemory.read(f)))
    }

    @Test
    fun handEditedLinesStillCount() {
        // 用户或 AI 改文件时不一定守格式
        assertEquals(listOf("一条", "另一条", "没有横杠的"), ChatMemory.items("# 标题\n\n- 一条\n* 另一条\n没有横杠的\n\n"))
    }

    @Test
    fun emptyListRemovesFile() {
        val f = File(dir, ChatMemory.PROJECT_FILE)
        ChatMemory.write(f, listOf("故事发生在 1990 年"))
        assertTrue(f.readText().startsWith("# 项目记忆"))
        ChatMemory.write(f, emptyList())
        assertFalse(f.exists())
    }

    @Test
    fun projectChatsGetTheirOwnFile() {
        assertEquals("记忆.md", ChatMemory.chatFileName(Conversation()))
        val c = Conversation(projectId = "p1")
        assertEquals("对话记忆-${c.id.take(6)}.md", ChatMemory.chatFileName(c))
    }
}
