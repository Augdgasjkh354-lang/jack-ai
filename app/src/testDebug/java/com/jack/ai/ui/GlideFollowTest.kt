package com.jack.ai.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回答超过一屏后，每写出新的一行，画面要滑上去，不能整屏一下跳一行（Jack 看到的「整个屏幕一闪一闪」，写到几行之后才开始）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // 真的排字、换行
class GlideFollowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun newLinesSlideInsteadOfJumping() {
        rule.mainClock.autoAdvance = false
        var text by mutableStateOf("")
        var listState: androidx.compose.foundation.lazy.LazyListState? = null
        rule.setContent {
            val s = rememberLazyListState().also { listState = it }
            val f = rememberFollowBottom(s, "c", "u1", moving = true)
            LazyColumn(state = s, modifier = Modifier.width(320.dp).height(500.dp).nestedScroll(f.connection)) {
                items(6) { i -> Text("第 $i 条 " + "旧消息。".repeat(20), Modifier.fillMaxWidth().let { if (i == 5) it.testTag("above") else it }) }
                item { Text(text, Modifier.fillMaxWidth(), fontSize = 16.sp, lineHeight = 26.sp) }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        val ys = mutableListOf<Float>()
        // 每帧一两个字（每秒 100 字），写 4 秒
        repeat(240) { i ->
            text += if (i % 3 == 0) "字字" else "字"
            rule.mainClock.advanceTimeByFrame()
            runCatching { rule.onNodeWithTag("above").getBoundsInRoot().top.value }.getOrNull()?.let { ys += it }
        }
        val steps = ys.zipWithNext { a, b -> a - b }
        val biggest = steps.maxOrNull() ?: 0f
        assertTrue("上面的消息一帧最多挪了 ${biggest}dp（一行是 26dp，直接跳就会是 26）", biggest < 9f)
        assertTrue("一直没动：没跟着往下走", ys.first() - ys.last() > 100f)
        // 停下来以后最终贴到底
        rule.mainClock.advanceTimeBy(1500)
        assertFalse("最后没到底", listState!!.canScrollForward)
    }

    /** 一个聊天：上面 6 条旧消息，最后一条在长；moving 可以关掉（输出结束）。 */
    private fun chat(): Triple<(String) -> Unit, (Boolean) -> Unit, () -> androidx.compose.foundation.lazy.LazyListState> {
        rule.mainClock.autoAdvance = false
        var text by mutableStateOf("")
        var moving by mutableStateOf(true)
        var listState: androidx.compose.foundation.lazy.LazyListState? = null
        rule.setContent {
            val s = rememberLazyListState().also { listState = it }
            val f = rememberFollowBottom(s, "c", "u1", moving = moving)
            LazyColumn(state = s, modifier = Modifier.width(320.dp).height(500.dp).testTag("list").nestedScroll(f.connection)) {
                items(6) { i -> Text("第 $i 条 " + "旧消息。".repeat(20), Modifier.fillMaxWidth().let { if (i == 5) it.testTag("above") else it }) }
                item { Text(text, Modifier.fillMaxWidth().testTag("answer"), fontSize = 16.sp, lineHeight = 26.sp) }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        return Triple({ t -> text += t }, { m -> moving = m }, { listState!! })
    }

    @Test
    fun whenOutputEndsItSettlesExactlyAtTheBottom() {
        val (add, setMoving, ls) = chat()
        repeat(120) { add("字"); rule.mainClock.advanceTimeByFrame() }
        // 最后一下来了一大段，紧接着就结束了：还差一截没滑完
        add("最后一段话。".repeat(12))
        rule.mainClock.advanceTimeByFrame()
        setMoving(false)
        rule.mainClock.advanceTimeBy(1500)
        assertFalse("输出结束后没贴到底，差了一点", ls().canScrollForward)
    }

    @Test
    fun aSingleAnswerTallerThanTwoScreensStillGlides() {
        val (add, _, ls) = chat()
        // 用列表自己的排版数据（屏幕上的边界会被裁到屏幕内）：回答这一项的起点每帧挪多少
        val tops = mutableListOf<Int>()
        repeat(900) { i ->
            add(if (i % 3 == 0) "字字" else "字")
            rule.mainClock.advanceTimeByFrame()
            ls().layoutInfo.visibleItemsInfo.lastOrNull { it.index == 6 }?.let { tops += it.offset }
        }
        val px = with(rule.density) { 1.dp.toPx() }
        val info = ls().layoutInfo
        val height = info.visibleItemsInfo.last { it.index == 6 }.size
        assertTrue("这条回答还没超过两屏（${height / px}dp），测试没测到", height > 2 * (info.viewportEndOffset - info.viewportStartOffset))
        val biggest = tops.zipWithNext { a, b -> kotlin.math.abs(a - b) }.maxOrNull() ?: 0
        assertTrue("回答一帧挪了 ${biggest / px}dp", biggest / px < 9f)
        rule.mainClock.advanceTimeBy(1000)
        assertFalse(ls().canScrollForward)
    }

    @Test
    fun scrollingUpWhileItWritesIsNotPulledBack() {
        val (add, _, ls) = chat()
        repeat(150) { add("字"); rule.mainClock.advanceTimeByFrame() }
        // 输出中往上翻（手指往下拖）
        rule.onNodeWithTag("list").performTouchInput { swipeDown() }
        rule.mainClock.advanceTimeBy(800)
        val index = ls().firstVisibleItemIndex
        val offset = ls().firstVisibleItemScrollOffset
        // 接着输出 2 秒：停在用户翻到的位置，不被拉回底部
        repeat(120) { add("字"); rule.mainClock.advanceTimeByFrame() }
        assertTrue("被拉走了", ls().firstVisibleItemIndex == index && ls().firstVisibleItemScrollOffset == offset)
        assertTrue(ls().canScrollForward)
    }
}
