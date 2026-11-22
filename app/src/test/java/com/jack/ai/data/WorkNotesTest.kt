package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun appendsAndRecalls() {
        val d = tmp.newFolder()
        assertEquals("", WorkNotes.recall(d))
        WorkNotes.append(d, "finding", "A 公司 2025 年营收 12 亿\n来源 https://a.com/report")
        WorkNotes.append(d, "decision", "只比三家，第四家数据太旧")
        val r = WorkNotes.recall(d)
        assertTrue(r.contains("【发现】A 公司 2025 年营收 12 亿；来源 https://a.com/report"))
        assertTrue(r.contains("【决定】只比三家"))
    }

    @Test fun recallKeepsTheLatest() {
        val d = tmp.newFolder()
        repeat(400) { WorkNotes.append(d, "finding", "第 $it 条：" + "x".repeat(30)) }
        val r = WorkNotes.recall(d)
        assertTrue(r.contains("第 399 条"))
        assertTrue(!r.contains("第 0 条"))
        assertTrue(r.length < WorkNotes.RECALL_CHARS + 200)
    }
}
