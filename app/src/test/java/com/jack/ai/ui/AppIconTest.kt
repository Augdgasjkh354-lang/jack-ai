package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 换桌面图标：只留选中的那个入口开着，换来换去桌面上始终有一个。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIconTest {
    private val ctx = RuntimeEnvironment.getApplication()

    private fun enabled() = AppIcon.enabled(ctx)

    @Test
    fun onlyTheChosenIconIsOnTheHomeScreen() {
        AppIcon.apply(ctx, "moon")
        assertEquals(listOf("moon"), enabled())
        AppIcon.ensure(ctx, "classic")
        assertEquals(listOf("classic"), enabled())
        // 认不出的 id 当成 K
        AppIcon.apply(ctx, "???")
        assertEquals(listOf("k"), enabled())
    }

    @Test
    fun defaultIsK() {
        // 清单里 K 默认开着：设置也是 K 时什么都不用改
        AppIcon.ensure(ctx, "k")
        assertEquals(listOf("k"), enabled())
    }
}
