package com.jack.ai.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 回答柔和地放出来；输出时只在用户停在底部时跟着走，往上翻就不拽。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmoothStreamTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aBigChunkIsRevealedGraduallyThenCatchesUp() {
        var full by mutableStateOf("")
        var streaming by mutableStateOf(true)
        var shown = ""
        rule.mainClock.autoAdvance = false
        rule.setContent { shown = rememberRevealedText("m1", full, streaming) }
        // 模型一下子吐了 600 个字
        full = "字".repeat(600)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertTrue("一帧就全出来了：${shown.length}", shown.length in 1..100)
        // 大约零点几秒后追上，不会越拖越远
        rule.mainClock.advanceTimeBy(1500)
        assertEquals(600, shown.length)
        // 回答结束：剩下的很快放完
        full += "尾巴".repeat(50)
        streaming = false
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(full.length, shown.length)
    }

    @Test
    fun oldMessagesShowAtOnce() {
        var shown = ""
        rule.setContent { shown = rememberRevealedText("old", "以前的回答", false) }
        rule.waitForIdle()
        assertEquals("以前的回答", shown)
    }

    /** 输出中「每帧贴底」的循环一直在跑，测试框架等不到空闲：手动推进时钟。 */
    private fun frames(ms: Long = 300) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private fun chat(growing: () -> String): Pair<() -> LazyListState, () -> FollowBottom> {
        rule.mainClock.autoAdvance = false
        var ls: LazyListState? = null
        var fb: FollowBottom? = null
        rule.setContent {
            val s = rememberLazyListState().also { ls = it }
            val f = rememberFollowBottom(s, "c", "u1", moving = true).also { fb = it }
            LazyColumn(Modifier.fillMaxSize().testTag("list").nestedScroll(f.connection), state = s) {
                items(20) { i -> Text("第 $i 条：" + "旧消息内容。".repeat(10), Modifier.fillMaxWidth()) }
                item { Text(growing(), Modifier.fillMaxWidth()) }
            }
        }
        return { ls!! } to { fb!! }
    }

    @Test
    fun followsWhileAtTheBottom() {
        var text by mutableStateOf("开始")
        val (ls, _) = chat { text }
        frames()
        repeat(10) { text += "新的一行字。".repeat(20) + "\n"; frames(50) }
        frames()
        assertFalse("在底部时没跟上", ls().canScrollForward)
    }

    @Test
    fun scrollingUpStopsFollowingAndNothingJumps() {
        var text by mutableStateOf("开始")
        val (ls, fb) = chat { text }
        frames()
        rule.onNodeWithTag("list").performTouchInput { swipeDown() }
        frames(1000)
        assertFalse(fb().on)
        val index = ls().firstVisibleItemIndex
        val offset = ls().firstVisibleItemScrollOffset
        repeat(10) { text += "新的一行字。".repeat(20) + "\n"; frames(50) }
        frames()
        // 正在看的那段不动：不被拽到底，也不跳
        assertEquals(index, ls().firstVisibleItemIndex)
        assertEquals(offset, ls().firstVisibleItemScrollOffset)
        assertTrue(ls().canScrollForward)
    }

    @Test
    fun paragraphsSplitButCodeBlocksStayWhole() {
        val md = "第一段\n\n## 标题\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n- 一\n- 二\n\n$$\nx\n\ny\n$$"
        assertEquals(listOf("第一段", "## 标题", "```kotlin\nval a = 1\n\nval b = 2\n```", "- 一\n- 二", "$$\nx\n\ny\n$$"), splitBlocks(md))
    }
}
