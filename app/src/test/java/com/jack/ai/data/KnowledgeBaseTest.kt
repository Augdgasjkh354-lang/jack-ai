package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseTest {
    private fun f(path: String, sha: String = "s", size: Long = 100) = KbRepo.File(path, sha, size)

    @Test
    fun repoWithoutMarkerIsOneBaseOfDocs() {
        val found = KbRepo.find(listOf(f("README.md"), f("docs/guide.md"), f("src/Main.kt"), f("LICENSE"), f("paper.pdf"), f("notes.txt", size = 0)))
        assertEquals(1, found.size)
        assertEquals("", found[0].dir)
        assertEquals(listOf("README.md", "docs/guide.md", "paper.pdf"), found[0].files.map { it.path })
    }

    @Test
    fun eachMarkerFolderIsOneBaseAndNestedOnesStaySeparate() {
        val found = KbRepo.find(listOf(
            f("README.md"),
            f("law/KB.md"), f("law/civil.md"), f("law/data.json"),
            f("law/labor/KB.md"), f("law/labor/contract.md"),
            f("ai-drama/KB.md"), f("ai-drama/shots/prompt.txt"), f("ai-drama/tool.py"),
        )).associateBy { it.dir }
        assertEquals(setOf("law", "law/labor", "ai-drama"), found.keys)
        assertEquals(listOf("law/civil.md", "law/data.json"), found["law"]!!.files.map { it.path })
        assertEquals(listOf("law/labor/contract.md"), found["law/labor"]!!.files.map { it.path })
        // 根目录的 README 不属于任何知识库；py 不是资料
        assertEquals(listOf("ai-drama/shots/prompt.txt"), found["ai-drama"]!!.files.map { it.path })
        assertEquals("shots/prompt.txt", KbRepo.docName(found["ai-drama"]!!, "ai-drama/shots/prompt.txt"))
    }

    @Test
    fun hashChangesWhenAFileChanges() {
        val a = KbRepo.find(listOf(f("kb/KB.md"), f("kb/a.md", "1"))).single().hash
        val b = KbRepo.find(listOf(f("kb/KB.md"), f("kb/a.md", "2"))).single().hash
        assertNotEquals(a, b)
        assertEquals(a, KbRepo.find(listOf(f("kb/KB.md"), f("kb/a.md", "1"))).single().hash)
    }

    @Test
    fun syncDueOncePerDayOnlyWithGithubBases() {
        val now = 10L * 24 * 3600_000
        val gh = listOf(KnowledgeBase(name = "x", source = "a/b@main:x"))
        assertTrue(KnowledgeBases.syncDue(0, gh, now))
        assertFalse(KnowledgeBases.syncDue(now - 3600_000, gh, now))
        assertFalse(KnowledgeBases.syncDue(0, listOf(KnowledgeBase(name = "local")), now))
    }

    @Test
    fun hybridRankFindsParaphraseThroughVectors() {
        val idx = LibraryIndex(listOf(
            "财报.md" to "本季度营收同比增长三成，净利润创新高。",
            "日记.md" to "今天去公园散步，天气很好。",
        ))
        // 关键词对不上（问的是「赚了多少钱」），向量把财报那段排上来
        assertTrue(idx.rank("赚了多少钱", 5).isEmpty())
        val money = floatArrayOf(1f, 0f, 0f)
        val vecs = mapOf(
            LibraryVectors.key(idx.chunks[0].text) to Facts.quantize(floatArrayOf(0.9f, 0.1f, 0f)),
            LibraryVectors.key(idx.chunks[1].text) to Facts.quantize(floatArrayOf(0f, 0.1f, 1f)),
        )
        val hits = LibraryVectors.rank(idx, "赚了多少钱", money, vecs, 5, 0.35f)
        assertEquals(listOf(0), hits)
        // 没有向量就只用关键词
        assertEquals(listOf(1), LibraryVectors.rank(idx, "公园 散步", null, emptyMap(), 5, 0.35f))
    }
}
