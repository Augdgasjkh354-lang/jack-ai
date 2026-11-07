package com.jack.ai.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jack.ai.data.AskCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 问题卡片：一个单选问题点一下就发；几个问题选好再发；「其他」能自己写；答过的不能再点。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskCardsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun singleQuestionSendsOnTap() {
        val sent = mutableListOf<String>()
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { AskCards("m1", listOf(AskCard("卖给谁？", listOf("宝妈", "上班族"))), true) { sent += it } } }
        rule.onNodeWithText("上班族").performClick()
        assertEquals(listOf("卖给谁：上班族"), sent)
        // 发过就不能再点
        rule.onNodeWithText("宝妈").assertIsNotEnabled()
    }

    @Test fun severalQuestionsWaitForSendAndOtherCanBeTyped() {
        val sent = mutableListOf<String>()
        val cards = listOf(AskCard("卖什么？", listOf("茶叶", "水果")), AskCard("想突出哪些？", listOf("便宜", "好吃"), multi = true))
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { AskCards("m2", cards, true) { sent += it } } }
        rule.onNodeWithText("便宜").performClick()
        rule.onNodeWithText("好吃").performClick()
        assertTrue(sent.isEmpty())
        rule.onAllNodes(androidx.compose.ui.test.hasText(ASK_OTHER))[0].performClick()
        rule.onNodeWithTag("ask-other-0").performTextInput("自己种的蜂蜜")
        rule.onNodeWithTag("ask-send").performClick()
        assertEquals(listOf("卖什么：自己种的蜂蜜\n想突出哪些：便宜、好吃"), sent)
    }

    @Test fun answeredCardsAreDisabled() {
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { AskCards("m3", listOf(AskCard("要吗？", listOf("要", "不要"))), false) {} } }
        rule.onNodeWithText("要").assertIsNotEnabled()
    }
}
