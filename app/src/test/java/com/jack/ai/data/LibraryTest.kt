package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test
    fun searchFindsRightChunk() {
        val book = (1..30).joinToString("\n") { "第${it}章，这里是一些普通的填充文字，讲述日常生活的琐事。" } +
            "\n福贵把家产都输光了，后来他的儿子有庆因为给县长夫人献血而死去。\n" +
            (1..30).joinToString("\n") { "后记第${it}段，依然是填充内容。" }
        val idx = LibraryIndex(listOf("活着.txt" to book, "notes.md" to "Kotlin coroutines use structured concurrency."))
        val hit = idx.search("有庆是怎么死的").first()
        println(hit)
        assertEquals("活着.txt", hit.doc)
        assertTrue(hit.text.contains("有庆"))
        assertEquals("notes.md", idx.search("coroutines").first().doc)
    }

    @Test
    fun recallIntervalsGrow() {
        var c = Flashcard(deck = "d", front = "f", back = "b")
        c = Recall.review(c, 2); assertEquals(1f, c.interval)
        c = Recall.review(c, 2); assertEquals(3f, c.interval)
        c = Recall.review(c, 2); assertTrue(c.interval > 6f)
        c = Recall.review(c, 0); assertEquals(0f, c.interval)
    }
}
