package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneExamTest {
    @Test fun gradesAnswersAndQuotesAgainstWhatWasOnScreen() {
        val calc = PhoneExam.TASKS.first { it.id == "calc-mul" }
        assertTrue(PhoneExam.grade(calc, "结果是 56088。", emptySet()).first)
        assertFalse(PhoneExam.grade(calc, "结果是 56000。", emptySet()).first)
        val look = PhoneExam.TASKS.first { it.id == "app-first" }
        val seen = setOf("今日推荐", "热门话题 · 30万讨论", "我的")
        assertTrue(PhoneExam.grade(look, "看到「今日推荐」「热门话题」「我的」", seen).first)
        // 编出来的原文不算
        assertFalse(PhoneExam.grade(look, "看到「限时秒杀」「新人福利」", seen).first)
    }

    @Test fun picksCalculatorAndOtherApps() {
        val ticked = listOf("com.android.calculator2" to "计算器", "com.tencent.mm" to "微信", "com.ss.android.ugc.aweme" to "抖音", "x.y" to "别的")
        assertEquals(listOf("计算器"), PhoneExam.appsFor(PhoneExam.TASKS.first { it.app == "calculator" }, ticked).map { it.second })
        assertEquals(2, PhoneExam.appsFor(PhoneExam.TASKS.first { it.app == "each" }, ticked).size)
    }
}
