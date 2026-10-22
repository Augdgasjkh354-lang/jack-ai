package com.jack.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 聊天页往右滑开侧边栏：手指落在消息气泡（TextView）上也要能开。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeTest {
    @get:Rule val rule = createComposeRule()

    private fun setUp(plain: Boolean = false, ours: Boolean = true): () -> DrawerState {
        var state: DrawerState? = null
        rule.setContent {
            val s = rememberDrawerState(DrawerValue.Closed).also { state = it }
            val scope = rememberCoroutineScope()
            // 和 ChatScreen 一样：关着时只靠 swipeToOpen 打开
            ModalNavigationDrawer(drawerState = s, gesturesEnabled = !ours || s.isOpen, drawerContent = { ModalDrawerSheet { Text("侧边栏") } }) {
                Box(Modifier.fillMaxSize().testTag("chat").let { if (ours) it.swipeToOpen { scope.launch { s.open() } } else it }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(30) { i ->
                            if (plain) Text("第 $i 条消息，" + "这是一段比较长的回答文字。".repeat(6), Modifier.fillMaxWidth())
                            else MarkdownText("第 $i 条消息，" + "这是一段比较长的回答文字。".repeat(6), Color.Black, Color.LightGray,
                                Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        return { state!! }
    }

    @Test
    fun swipeRightOverMessagesOpensDrawer() {
        val state = setUp()
        rule.onNodeWithTag("chat").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.8f, durationMillis = 300) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        assertTrue("侧边栏没打开：${state().currentValue}", state().isOpen)
    }

    @Test
    fun plainTextControl() {
        val state = setUp(plain = true)
        rule.onNodeWithTag("chat").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.8f, durationMillis = 300) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1000)
        assertTrue("纯文字也打不开：${state().currentValue}", state().isOpen)
    }

    @Test
    fun drawerOwnGestureOverText() {
        val state = setUp(plain = true, ours = false)
        rule.onNodeWithTag("chat").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.8f, durationMillis = 300) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1000)
        assertTrue("抽屉自带手势打不开：${state().currentValue}", state().isOpen)
    }

    @Test
    fun verticalScrollDoesNotOpen() {
        val state = setUp()
        rule.onNodeWithTag("chat").performTouchInput { swipeUp() }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1000)
        assertTrue("竖着滑把侧边栏打开了", !state().isOpen)
    }

    @Test
    fun swipeLeftClosesAfterOpening() {
        val state = setUp()
        rule.onNodeWithTag("chat").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.8f, durationMillis = 300) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1000)
        assertTrue(state().isOpen)
        rule.onNodeWithTag("chat").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.05f, durationMillis = 200) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1000)
        assertTrue("往左划没关上：${state().currentValue}", !state().isOpen)
    }
}
