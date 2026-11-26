package com.jack.ai.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 长按回答里的字：弹出的是带「引用」的菜单；点「引用」拿到选中的字，不写进系统剪贴板。
 * 跑在 API 27：28 起长按选字会出放大镜，Robolectric 的假窗口画不了放大镜会崩（真机没事）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class QuoteSelectionTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun longPressThenQuote() {
        var got: String? = null
        var toolbar: QuoteToolbar? = null
        rule.setContent {
            JackTheme(com.jack.ai.data.ThemeMode.LIGHT) {
                QuoteSelection({ got = it }) {
                    toolbar = LocalTextToolbar.current as QuoteToolbar
                    MarkdownBlocks("Kavi answer text here", Color.Black, Color.LightGray)
                }
            }
        }
        val clip = rule.activity.getSystemService(android.content.ClipboardManager::class.java)
        clip.setPrimaryClip(android.content.ClipData.newPlainText("", "原来的"))
        rule.onNodeWithText("Kavi answer text here", substring = true).performTouchInput { longClick(center) }
        rule.waitForIdle()
        assertEquals(TextToolbarStatus.Shown, toolbar!!.status)
        rule.runOnUiThread { toolbar!!.quote() }
        rule.waitForIdle()
        assertTrue("拿到选中的字：$got", !got.isNullOrBlank() && "Kavi answer text here".contains(got!!))
        assertEquals("原来的", clip.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
