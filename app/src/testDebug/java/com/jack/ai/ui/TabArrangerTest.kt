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

    private var order: List<String>? = null

    private fun run(saved: List<String>, label: String, rows: Float): List<String>? {
        var out: List<String>? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { TabArranger(saved, onOpen = {}, onSave = { out = it }, onSaveOrder = { order = it }) } }
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
        // 「资料」是「其他」的第一个（第 4 格），往上拖两格落在记忆后面
        assertEquals(listOf("memory", "data", "routines"), run(listOf("memory", "routines"), "资料", -2f))
    }

    @Test
    fun dragOutOfTheBar() {
        assertEquals(listOf("routines"), run(listOf("memory", "routines"), "记忆", 2f))
        // 拖出来的「记忆」落在「其他」的第一个，并且这个顺序记下来了（以前会跳回默认位置）
        assertEquals("memory", order!!.first())
    }

    @Test
    fun reorderInsideOthersIsKept() {
        // 「其他」里默认是 资料、浏览器、工具箱，把「工具箱」往上拖两格到最前
        run(listOf("memory", "routines"), "工具箱", -2f)
        assertEquals(listOf("tools", "data", "browser"), order!!.take(3))
        assertEquals(listOf("tools", "data", "browser"), KaviTab.others(listOf("memory", "routines"), order!!).map { it.id }.take(3))
    }

    @Test
    fun fullBarPushesTheLastOneOut() {
        assertEquals(listOf("tools", "data", "memory", "browser"),
            run(listOf("data", "memory", "browser", "routines"), "工具箱", -5f))
    }

    @Test
    fun tapStillOpens() {
        var opened: KaviTab? = null
        rule.setContent { JackTheme(com.jack.ai.data.ThemeMode.LIGHT) { TabArranger(null, onOpen = { opened = it }, onSave = {}) } }
        rule.onNodeWithText("工具箱").performClick()
        assertEquals(KaviTab.TOOLS, opened)
    }

    @Test
    fun oldPerPageIdsLandInTheirGroups() {
        // 以前底栏能放「文件库」「资料库」「游戏」这些单页：现在认成分组，重复的只留一个
        assertEquals(listOf(KaviTab.CHAT, KaviTab.DATA, KaviTab.TOOLS, KaviTab.MEMORY), KaviTab.bar(listOf("files", "library", "games", "review")))
        // 「其他」那组以前拖好的顺序（旧 id）也照样认
        assertEquals(listOf(KaviTab.TOOLS, KaviTab.DATA, KaviTab.BROWSER), KaviTab.others(listOf("memory", "routines"), listOf("games", "skills", "files")))
        assertEquals(listOf(SubPage.LIBRARY, SubPage.FILES, SubPage.TABLES, SubPage.DOWNLOADS), KaviTab.DATA.pages)
        assertEquals(SubPage.LIBRARY, SubPage.lastOf(KaviTab.DATA))
        SubPage.remember(SubPage.TABLES)
        assertEquals(SubPage.TABLES, SubPage.lastOf(KaviTab.DATA))
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
