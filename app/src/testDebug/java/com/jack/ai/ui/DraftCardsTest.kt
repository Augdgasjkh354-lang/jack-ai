package com.jack.ai.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jack.ai.data.DraftCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 发帖草稿卡片：按平台显示标题正文话题；「改一下」点快捷项就把要求发给 Kavi；不是最后一条时没有「改一下」。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftCardsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun showsPostAndSendsEdit() {
        val sent = mutableListOf<String>()
        val d = DraftCard("xhs", "三步做好封面", "第一步先定钩子", listOf("封面", "设计"))
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { DraftCards(listOf(d), true) { sent += it } } }
        rule.onNodeWithText("三步做好封面").assertIsDisplayed()
        rule.onNodeWithText("#封面 #设计").assertIsDisplayed()
        rule.onNodeWithText("改一下").performClick()
        rule.onNodeWithText("短一点").performClick()
        assertEquals(listOf("这篇小红书草稿改一下：短一点"), sent)
    }

    @Test fun inactiveHasNoEdit() {
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { DraftCards(listOf(DraftCard("wechat", body = "明天见", to = "老王")), false) {} } }
        rule.onNodeWithText("明天见").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("改一下")).fetchSemanticsNodes().size)
    }
}
