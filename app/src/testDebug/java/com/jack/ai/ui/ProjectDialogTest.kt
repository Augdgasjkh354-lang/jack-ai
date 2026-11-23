package com.jack.ai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.jack.ai.data.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 新建项目：选类型（图标跟着换），选「仅项目」就是隐私项目。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun kindChipFillsNameAndIconAndMemoryModeSetsIsolated() {
        var saved: Project? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { ProjectDialog(null, onDismiss = {}) { saved = it } } }
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Kavi")
        rule.onNodeWithText("💻 代码项目").performScrollTo().performClick()
        rule.onNodeWithText("仅项目").performScrollTo().performClick()
        rule.onNodeWithText("创建项目").performClick()
        val p = saved!!
        assertEquals("Kavi", p.name)
        assertEquals("💻", p.icon)
        assertEquals("code", p.kind)
        assertTrue(p.isolated)
    }
}
