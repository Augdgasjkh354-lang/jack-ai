package com.jack.ai.data

import com.jack.ai.data.memory.ChatMemory
import com.jack.ai.data.memory.MemoryOps
import com.jack.ai.data.memory.MemoryReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 项目记忆分块（设定、决定、进度、待办）和项目对话的后台回看。 */
class ProjectMemoryTest {
    @Test fun sectionsRoundTrip() {
        val items = listOf("【进度】写完第三章", "【设定】主角叫林舟", "随手记的一条", "【待办】改第二章结尾", "【决定】每章三千字")
        val text = ChatMemory.render("项目记忆", items)
        // 按块写，块里不重复写标记
        assertTrue(text.indexOf("## 设定") < text.indexOf("## 决定") && text.indexOf("## 进度") < text.indexOf("## 待办") && text.indexOf("## 待办") < text.indexOf("## 其他"))
        assertTrue("- 主角叫林舟" in text && "【设定】" !in text)
        // 读回来标记补上，没标记的原样
        assertEquals(items.toSet(), ChatMemory.items(text).toSet())
    }

    @Test fun plainFilesStayPlain() {
        val items = listOf("Jack 喜欢短回答", "住在上海")
        assertEquals("# 本对话记忆\n\n- Jack 喜欢短回答\n- 住在上海\n", ChatMemory.render("本对话记忆", items))
        assertEquals(items, ChatMemory.items(ChatMemory.render("本对话记忆", items)))
    }

    @Test fun reviewOpsCarryScopeAndKeepSections() {
        val ops = MemoryReview.parse("""{"ops":[
            {"scope":"project","op":"update","old":"写完第三章","text":"写完第四章"},
            {"scope":"project","op":"delete","old":"改第二章结尾"},
            {"scope":"global","op":"add","text":"Jack 写小说喜欢短句"}]}""", MemoryReview.PROJECT_MAX_OPS)
        assertEquals(listOf("project", "project", "global"), ops.map { it.scope })
        val live = listOf("【进度】写完第三章", "【待办】改第二章结尾", "【设定】主角叫林舟")
        val fixed = MemoryReview.keepSections(live, ops.filter { it.scope == "project" })
        // 忘了带标记的沿用原来的块
        assertEquals("【进度】写完第四章", fixed[0].text)
        val (next, done) = MemoryReview.apply(live, fixed, MemoryOps.PROJECT_BUDGET)
        assertEquals(listOf("【进度】写完第四章", "【设定】主角叫林舟"), next)
        assertEquals(2, done.size)
    }
}
