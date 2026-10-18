package com.jack.ai.features

import com.jack.ai.data.Feedback
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {
    @Test
    fun exportHasEverythingNeededToDebug() {
        val md = FeedbackFeature.render(listOf(Feedback(
            at = 0, convTitle = "邮件", model = "deepseek-flash", appVersion = 48, note = "删错了信",
            question = "把那封删了", answer = "已删除", steps = listOf("""{"role":"tool","content":"已把 1 封移到「已删除」"}"""),
            errors = listOf("删除邮件：找不到"),
        )))
        listOf("删错了信", "把那封删了", "deepseek-flash", "48", "已把 1 封移到", "删除邮件：找不到").forEach { assertTrue(it, it in md) }
    }
}
