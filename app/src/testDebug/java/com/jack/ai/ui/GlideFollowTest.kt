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
}
