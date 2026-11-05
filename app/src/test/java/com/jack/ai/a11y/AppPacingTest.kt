package com.jack.ai.a11y

import com.wangzan101.mika.core.cap.phone.AppDwell
import com.wangzan101.mika.core.cap.phone.AppPacing
import com.wangzan101.mika.core.uistate.LimitNotice
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenFacts
import com.wangzan101.mika.core.uistate.ScreenState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AppPacingTest {
    @Test fun dwellCountsElapsedThinkingAndRespectsCarefulFloor() = runBlocking {
        val limits = AppPacing.Limits(sameScreenMs = 1_000L..1_000L,
            newScreenMs = 1_500L..1_500L, readMsPerChar = 10, maxReadMs = 500,
            pauseChance = 0.0, maxDwellMs = 5_000)
        var now = 0L
        var slept = 0L
        val pace = AppPacing.human(Random(2), limits, limits)
        val dwell = AppDwell(pace, { now }, { slept += it })
        dwell.after("app", now, newScreen = false, careful = false, counts = false)
        now = 400
        assertEquals(600L, dwell.before("app", 0, false, 0, null, gate = false))
        now = 1_000
        dwell.after("app", now, newScreen = true, careful = true, counts = true)
        now = 1_600
        assertEquals(1_100L, dwell.before("app", 20, true, 1_500, 1_000, gate = true))
        assertEquals(1_700L, slept)
    }

    @Test fun repeatedActionsPauseLongerAndCarefulScreensHaveCap() {
        val limits = AppPacing.Limits(sameScreenMs = 100L..100L, newScreenMs = 100L..100L,
            pauseChance = 0.0, maxDwellMs = 5_000)
        val pacing = AppPacing(Random(4), limits, limits, screenCap = 2)
        pacing.noteAction("app", 100, newScreen = false, batchKind = "tap")
        val first = pacing.waitBefore("app", 100)
        pacing.noteAction("app", 200, newScreen = false, batchKind = "tap")
        assertTrue(pacing.waitBefore("app", 200) >= first + 700)
        pacing.noteAction("app", 300, newScreen = true, careful = true)
        pacing.noteAction("app", 400, newScreen = true, careful = true)
        assertTrue(pacing.capReached("app", true))
        pacing.userAnswered()
        assertFalse(pacing.capReached("app", true))
    }

    @Test fun detectsOnlyNewShortLimitsAndHumanScreenBarriers() {
        assertNull(LimitNotice.find(listOf("操作太频繁"), listOf("操作太频繁")))
        assertEquals("今天次数用完了", LimitNotice.find(listOf("正常内容"), listOf("今天次数用完了")))
        assertNull(LimitNotice.find(null, listOf("请参考帮助文档".repeat(50) + "明天再来")))
        assertEquals(ScreenState.NEEDS_HUMAN,
            ScreenDiagnosis.classify(ScreenFacts("app", 3, listOf("向右滑动完成验证"))).state)
        assertEquals(ScreenState.PROTECTED,
            ScreenDiagnosis.classify(ScreenFacts("app", 3, emptyList(), blackFrames = listOf(true, true))).state)
        assertEquals(ScreenState.LOADING,
            ScreenDiagnosis.classify(ScreenFacts("app", 3, emptyList(), changing = true)).state)
        assertEquals(ScreenState.NORMAL,
            ScreenDiagnosis.classify(ScreenFacts("app", 3, listOf("首页"))).state)
    }
}
