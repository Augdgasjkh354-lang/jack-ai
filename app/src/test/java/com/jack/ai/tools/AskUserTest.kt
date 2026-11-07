package com.jack.ai.tools

import com.jack.ai.data.AskCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserTest {
    private fun args(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun parsesCardsAndDropsModelWrittenOther() {
        val cards = parseAskCards(args("""{"questions":[{"question":"卖给谁？","options":["宝妈","上班族","宝妈","其他"]},{"question":"想突出哪些？","options":["便宜","好喝","送礼"],"multi":true}]}""")).getOrThrow()
        assertEquals(listOf(AskCard("卖给谁？", listOf("宝妈", "上班族")), AskCard("想突出哪些？", listOf("便宜", "好喝", "送礼"), multi = true)), cards)
    }

    @Test fun rejectsTooFewOptionsOrTooManyQuestions() {
        val few = parseAskCards(args("""{"questions":[{"question":"要吗？","options":["要","其他"]}]}"""))
        assertTrue(few.exceptionOrNull()!!.message!!.contains("至少要 2 个选项"))
        val q = """{"question":"q","options":["a","b"]}"""
        val many = parseAskCards(args("""{"questions":[$q,$q,$q,$q]}"""))
        assertTrue(many.exceptionOrNull()!!.message!!.contains("最多问 3 个"))
        assertTrue(parseAskCards(args("{}")).isFailure)
    }

    @Test fun answerReadsNaturallyAndSkipsUnanswered() {
        val cards = listOf(AskCard("卖给谁？", listOf("宝妈")), AskCard("什么口气", listOf("亲切")), AskCard("想突出哪些？", listOf("便宜", "好喝"), true))
        assertEquals("卖给谁：宝妈\n想突出哪些：便宜、好喝", askAnswer(cards, listOf(listOf("宝妈"), emptyList(), listOf("便宜", "好喝"))))
    }
}
