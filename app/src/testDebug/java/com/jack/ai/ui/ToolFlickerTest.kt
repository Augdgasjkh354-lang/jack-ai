package com.jack.ai.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.SearchRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 调用工具时回答框的高度不来回变（以前每开始/结束一次工具就跳一下，屏幕一闪一闪）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolFlickerTest {
    @get:Rule val rule = createComposeRule()

    private fun heights(msgs: List<ChatMessage>): List<Float> {
        var msg by mutableStateOf(msgs.first())
        // 转圈的动画停不下来，用手动时钟
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JackTheme(com.jack.ai.data.ThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Box(Modifier.testTag("m").width(360.dp)) {
                    AssistantMessage(msg, streaming = true, isLast = true, onRegenerate = {}, onDelete = {})
                }
            }
        }
        return msgs.map { m ->
            msg = m
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithTag("m").getBoundsInRoot().let { (it.bottom - it.top).value }
        }
    }

    @Test
    fun thinkingOnStaysStillAcrossToolCalls() {
        val base = ChatMessage(role = "assistant", content = "", reasoning = "先查一下天气\n再看看日历")
        val h = heights(listOf(
            base.copy(searches = listOf(SearchRecord("北京天气", pending = true))),
            base.copy(searches = listOf(SearchRecord("北京天气"))),
            base.copy(searches = listOf(SearchRecord("北京天气"), SearchRecord("查看日程", pending = true))),
            base.copy(searches = listOf(SearchRecord("北京天气"), SearchRecord("查看日程"))),
        ))
        assertEquals(h.toString(), 1, h.distinct().size)
    }

    @Test
    fun thinkingOffStaysStillAcrossToolCalls() {
        val base = ChatMessage(role = "assistant", content = "")
        val h = heights(listOf(
            base.copy(searches = listOf(SearchRecord("北京天气", pending = true))),
            base.copy(searches = listOf(SearchRecord("北京天气"))),
            base.copy(searches = listOf(SearchRecord("北京天气"), SearchRecord("查看日程", pending = true))),
        ))
        assertEquals(h.toString(), 1, h.distinct().size)
    }
}
