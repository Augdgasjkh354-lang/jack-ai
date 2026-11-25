package com.jack.ai.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 侧边栏里长按拖动底栏的格子：排顺序、拖过分界线换组、底栏满了挤出最后一个。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabArrangerTest {
    @get:Rule val rule = createComposeRule()

    private fun run(saved: List<String>, label: String, rows: Float): List<String>? {
        var out: List<String>? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { TabArranger(saved, onOpen = {}, onSave = { out = it }) } }
        val px = with(rule.density) { 48f * density }
        rule.onNodeWithText(label).performTouchInput {
            down(center)
            advanceEventTime(800)
            // 慢慢拖，一步一点
            repeat(20) { moveBy(Offset(0f, rows * px / 20f)); advanceEventTime(16) }
            up()
        }
        rule.waitForIdle()
        return out
    }

    @Test
    fun reorderInsideTheBar() {
        assertEquals(listOf("routines", "memory"), run(listOf("memory", "routines"), "提醒和任务", -1f))
    }

    @Test
    fun dragFromOthersIntoTheBar() {
        // 「资料库」是「其他」的第一个（第 4 格），往上拖两格落在记忆后面
        assertEquals(listOf("memory", "library", "routines"), run(listOf("memory", "routines"), "资料库", -2f))
    }

    @Test
    fun dragOutOfTheBar() {
        assertEquals(listOf("routines"), run(listOf("memory", "routines"), "记忆", 2f))
    }

    @Test
    fun fullBarPushesTheLastOneOut() {
        assertEquals(listOf("files", "library", "memory", "browser"),
            run(listOf("library", "memory", "browser", "routines"), "文件库", -6f))
    }

    @Test
    fun tapStillOpens() {
        var opened: KaviTab? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { TabArranger(null, onOpen = { opened = it }, onSave = {}) } }
        rule.onNodeWithText("技能").performClick()
        assertEquals(KaviTab.SKILLS, opened)
    }
}
