package com.jack.ai.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 正式版第一次打开的同意页：能打开协议全文，同意、不同意（先确认一次）都走得通。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun agreeReadAndRefuse() {
        var agreed = 0
        var exited = 0
        rule.setContent {
            JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { ConsentScreen(onAgree = { agreed++ }, onExit = { exited++ }) }
        }
        rule.onNodeWithText("欢迎使用 Kavi").assertIsDisplayed()
        rule.onNodeWithText("《隐私政策》").performScrollTo().performClick()
        // 全文在对话框里（安卓自己的文字控件画的）：在就行
        rule.onNodeWithText("关闭").assertExists()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithText("不同意").performClick()
        rule.onNodeWithText("再看看").performClick()
        assertEquals(0, exited)
        rule.onNodeWithText("不同意").performClick()
        rule.onNodeWithText("退出").performClick()
        assertEquals(1, exited)
        rule.onNodeWithText("同意").performClick()
        assertEquals(1, agreed)
    }
}
