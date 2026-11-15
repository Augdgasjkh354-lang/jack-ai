package com.jack.ai.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnowledgeBaseWriteTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun kaviWritesUpdatesAndDeletesDocsInItsOwnBase() {
        val store = Store(app)
        val kb = KnowledgeBases.create(store, "露营装备")
        KnowledgeBases.write(store, kb.id, "帐篷怎么选", "看防水指数，3000mm 以上。")
        KnowledgeBases.write(store, kb.id, "帐篷怎么选", "看防水指数，3000mm 以上；双层更好。")
        val docs = store.loadLibrary().filter { it.kb == kb.id }
        assertEquals(listOf("帐篷怎么选.md"), docs.map { it.name })
        assertTrue(store.libraryText(docs[0].id).contains("双层"))
        // 检索时名字前面带知识库名；关掉就不搜
        assertTrue(KnowledgeBases.searchable(store).any { it.first == "露营装备/帐篷怎么选.md" })
        KnowledgeBases.setEnabled(store, kb.id, false)
        assertTrue(KnowledgeBases.searchable(store).none { it.first.startsWith("露营装备/") })
        KnowledgeBases.write(store, kb.id, "帐篷怎么选", "")
        assertTrue(store.loadLibrary().none { it.kb == kb.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun githubBasesAreReadOnly() {
        val store = Store(app)
        val kb = KnowledgeBase(name = "外面的", source = "a/b@main:")
        store.saveKbs(listOf(kb))
        KnowledgeBases.write(store, kb.id, "x", "y")
    }
}
