package com.jack.ai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jack.ai.data.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 新建项目：点一类就填好名字和图标，选「仅项目」就是隐私项目。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun kindChipFillsNameAndIconAndMemoryModeSetsIsolated() {
        var saved: Project? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { ProjectDialog(null, onDismiss = {}) { saved = it } } }
        rule.onNodeWithText("✈️ 旅行").performClick()
        rule.onNodeWithText("仅项目").performScrollTo().performClick()
        rule.onNodeWithText("创建项目").performClick()
        val p = saved!!
        assertEquals("旅行", p.name)
        assertEquals("✈️", p.icon)
        assertTrue(p.isolated)
    }
}
