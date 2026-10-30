package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** 开发者指令：填了名字处处用名字、没填用 the user；保密和先后顺序一直在；Chat 和 Work 共用原则。 */
class PersonaTest {
    @Test
    fun nameReplacesTheUserEverywhere() {
        val s = Settings(userName = "Jack")
        val chat = Persona.devBlock(s, work = false)
        val work = Persona.devBlock(s, work = true)
        listOf(chat, work).forEach { t ->
            assertFalse(t, t.contains("{user}") || t.contains("{User}") || t.contains("{lang}"))
            assertFalse(t.contains("the user's requests", ignoreCase = true))
            assertTrue(t.contains("Jack's requests come first"))
            assertTrue(t.contains("in your own reasoning"))
            assertTrue(t.contains("Confidentiality:"))
            assertTrue(t.contains("this order decides"))
        }
        // 共用原则只写一遍；Work 多出自己的几条
        assertEquals(1, Regex("Working principles").findAll(work).count())
        assertTrue(work.contains("Work mode:") && !chat.contains("Work mode:"))
    }

    @Test
    fun noNameFallsBackToTheUser() {
        val t = Persona.devBlock(Settings(userName = ""), work = false)
        assertTrue(t.contains("The user's requests come first"))
        assertFalse(t.contains("You're talking with"))
    }

    @Test
    fun wordLikeNamesStillReadAsNames() {
        // 小写的普通词：首字母大写，读起来是名字，不会当成动词
        val have = Persona.devBlock(Settings(userName = "have"), work = false)
        assertTrue(have.contains("Do what Have asks"))
        assertTrue(have.contains("named \"have\" (written Have below)"))
        // 代词、「用户」这类：正文还是 the user，只告诉模型他叫什么
        listOf("you", "I", "我", "用户").forEach { n ->
            val t = Persona.devBlock(Settings(userName = n), work = false)
            assertTrue(n, t.contains("Do what the user asks"))
            assertTrue(n, t.contains("The user's name is \"$n\""))
        }
        assertEquals("用户", Persona.zh(Settings(userName = "你")))
        assertEquals("小明", Persona.zh(Settings(userName = "小明")))
    }

    @Test
    fun nameIsFlattenedAndCapped() {
        assertEquals("a b", Persona.name(Settings(userName = "  a\n\nb ")))
        assertEquals(30, Persona.name(Settings(userName = "x".repeat(100))).length)
    }

    @Test
    fun languageFollowsThePhone() {
        assertEquals("Simplified Chinese", Persona.language(Locale.SIMPLIFIED_CHINESE))
        assertEquals("Traditional Chinese", Persona.language(Locale.TRADITIONAL_CHINESE))
        assertEquals("English", Persona.language(Locale.US))
    }

    @Test
    fun printForReview() {
        System.getenv("PERSONA_OUT")?.let { File(it).writeText(Persona.devBlock(Settings(userName = "Jack"), work = true)) }
    }
}
