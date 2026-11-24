package com.jack.ai.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 改名弹窗：打完新名字按键盘上的「完成」就改好，不用再去点确定。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenameDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun imeDoneConfirmsTrimmedName() {
        var got: String? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { RenameDialog("旧名字", onDismiss = {}) { got = it } } }
        rule.onNode(hasSetTextAction()).performTextReplacement(" 新名字 ")
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals("新名字", got)
    }

    @Test fun imeDoneIgnoresBlank() {
        var got: String? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { RenameDialog("旧名字", onDismiss = {}) { got = it } } }
        rule.onNode(hasSetTextAction()).performTextReplacement("  ")
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(null, got)
    }
}
