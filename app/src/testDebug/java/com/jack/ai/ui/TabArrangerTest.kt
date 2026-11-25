package com.jack.ai.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.platform.testTag
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

/** 长按底栏左右滑：松手换到手指下面那一格，只打开一次。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class LiquidScrubTest {
    @get:Rule val rule = createComposeRule()

    private fun bar(clicks: MutableList<Int>) {
        rule.setContent {
            JackTheme(com.jack.ai.data.ThemeMode.LIGHT) {
                LiquidTabBar(KaviTab.bar(null).mapIndexed { i, t -> TabItem(t.icon, t.label) { clicks += i } }, 0,
                    androidx.compose.ui.Modifier.testTag("bar"))
            }
        }
    }

    @Test
    fun longPressAndSlideOpensTheTabUnderTheFinger() {
        val clicks = mutableListOf<Int>()
        bar(clicks)
        rule.onNodeWithContentDescription("聊天").performTouchInput {
            down(center); advanceEventTime(800)
            val step = width.toFloat() // 一格的宽度
            repeat(30) { moveBy(Offset(3 * step / 30f, 0f)); advanceEventTime(16) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(3), clicks)
    }

    @Test
    fun longPressWithoutMovingOpensOnce() {
        val clicks = mutableListOf<Int>()
        bar(clicks)
        rule.onNodeWithContentDescription("记忆").performTouchInput { down(center); advanceEventTime(800); up() }
        rule.waitForIdle()
        assertEquals(listOf(2), clicks)
    }
}
