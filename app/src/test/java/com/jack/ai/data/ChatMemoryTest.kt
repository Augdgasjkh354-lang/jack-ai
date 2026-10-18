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

    @Test
    fun replaceChangesOrDeletesOneItem() {
        val items = listOf("用户在准备考大学语文", "用户喜欢简短的回答", "用户养了一只猫")
        assertEquals(listOf("用户在准备考研", "用户喜欢简短的回答", "用户养了一只猫"),
            ChatMemory.replace(items, "考大学语文", "用户在准备考研"))
        assertEquals(listOf("用户喜欢简短的回答", "用户养了一只猫"), ChatMemory.replace(items, "- 用户在准备考大学语文", ""))
        // 认不出、认出好几条都报错，并列出候选
        val none = runCatching { ChatMemory.replace(items, "狗", "") }.exceptionOrNull()!!
        assertTrue(none.message!!.contains("养了一只猫"))
        val many = runCatching { ChatMemory.replace(items, "用户", "") }.exceptionOrNull()!!
        assertTrue(many.message!!.contains("3 条"))
        // 一字不差的优先，即使别的条目也包含它
        assertEquals(listOf("猫"), ChatMemory.replace(listOf("猫", "一只猫"), "一只猫", ""))
        assertEquals(listOf("一只猫"), ChatMemory.replace(listOf("猫", "一只猫"), "猫", ""))
    }
}
