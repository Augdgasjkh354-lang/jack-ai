package com.jack.ai.tools

import com.jack.ai.data.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收尾关卡：清单齐了才放行，缺什么说清楚；复核结果解析得了才算。 */
class WorkGateTest {
    private fun p(text: String, status: String) = PlanItem(text, status)

    @Test
    fun noPlanMeansWriteOneFirst() {
        val msg = WorkGate.unfinished(emptyList(), emptyList())
        assertNotNull(msg)
        assertTrue(msg!!.contains("no plan yet"))
    }

    @Test
    fun openStepsAndUncheckedCriteriaAreListed() {
        val msg = WorkGate.unfinished(
            listOf(p("搜供应商", "done"), p("核对规格", "doing"), p("做 Excel", "pending")),
            listOf(p("找满 5 家", "met"), p("每家都有来源", "pending")),
        )!!
        assertTrue(msg.contains("step 2 「核对规格」 is still doing"))
        assertTrue(msg.contains("step 3 「做 Excel」 is still pending"))
        assertTrue(msg.contains("「每家都有来源」 hasn't been checked"))
        assertFalse(msg.contains("找满 5 家"))
    }

    @Test
    fun missingCriteriaIsAskedFor() {
        val msg = WorkGate.unfinished(listOf(p("搜", "done")), emptyList())!!
        assertTrue(msg.contains("no acceptance criteria"))
    }

    @Test
    fun skippedAndFailedCountAsSettled() {
        assertNull(WorkGate.unfinished(
            listOf(p("搜", "done"), p("登录后台", "skipped")),
            listOf(p("找满 5 家", "met"), p("价格可核验", "failed")),
        ))
    }

    @Test
    fun reviewParsing() {
        assertEquals(WorkGate.Review(true, emptyList()), WorkGate.parse("""{"pass": true, "problems": []}"""))
        val r = WorkGate.parse("```json\n{\"pass\": false, \"problems\": [\"只找到 4 家\", \" \"]}\n```")!!
        assertFalse(r.pass)
        assertEquals(listOf("只找到 4 家"), r.problems)
        assertNull(WorkGate.parse("不是 JSON"))
    }
}
