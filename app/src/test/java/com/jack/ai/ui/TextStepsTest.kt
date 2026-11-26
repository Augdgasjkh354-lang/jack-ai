package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStepsTest {
    private fun labels(t: String) = TextSteps.of(t).map { it.first }

    @Test fun tableOffersExcelAndChart() {
        val t = "这是这几个月的花销：\n\n| 月份 | 金额 |\n|---|---:|\n| 1 月 | 300 |\n| 2 月 | 420 |\n"
        assertEquals(listOf("导出 Excel", "做成图表"), labels(t))
    }

    @Test fun codeOffersSaveAndExplain() {
        val t = "可以这样写一个小脚本来批量改名：\n\n```python\nimport os\nprint('hi')\n```\n跑一下就行。"
        assertEquals(listOf("存成文件", "逐行解释"), labels(t))
    }

    @Test fun stepsWithTimesBecomeReminders() {
        val t = "明天的安排可以这样：\n1. 早上 8:00 跑步\n2. 10:30 开会\n3. 晚上 9 点前交报告\n"
        assertEquals(listOf("设成提醒"), labels(t))
        val plain = "搬家要做的事情大概有这些：\n1. 打包衣服和书\n2. 联系搬家公司\n3. 退掉旧房子的宽带\n"
        assertEquals(listOf("做成清单"), labels(plain))
    }

    @Test fun shortOrPlainGivesNothing() {
        assertTrue(labels("好的，明白了。").isEmpty())
        assertTrue(labels("今天天气不错，适合出去走走，记得多喝水，晚上早点睡，明天还要上班呢。").isEmpty())
    }
}
