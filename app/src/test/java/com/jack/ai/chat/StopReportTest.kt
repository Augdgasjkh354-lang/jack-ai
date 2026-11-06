package com.jack.ai.chat

import com.jack.ai.data.ChatMessage
import com.jack.ai.data.PlanItem
import com.jack.ai.data.SearchRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopReportTest {
    @Test fun saysWhatWasDoneAndWhatIsLeft() {
        val m = ChatMessage(role = "assistant", content = "在查", searches = listOf(SearchRecord("搜索：机票"), SearchRecord("打开 携程"), SearchRecord("读页面", error = "超时")),
            plan = listOf(PlanItem("查价格", "done"), PlanItem("对比三家"), PlanItem("写结论")))
        val r = StopReport.build(m)
        assertTrue(r, r.contains("做了 2 步（另有 1 步没成）"))
        assertTrue(r, r.contains("清单完成 1/3，还没做：对比三家；写结论"))
        assertTrue(r.contains("说「继续」"))
        assertEquals("", StopReport.build(ChatMessage(role = "assistant", content = "你好")))
    }
}
