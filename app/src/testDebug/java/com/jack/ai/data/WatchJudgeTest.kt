package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 监控一次检查的结论：满足 / 没满足 / 没查成，要分开，不能把「没查成」说成「没满足」。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchJudgeTest {
    private fun judge(text: String, ok: Int = 1, failed: Int = 0) = Routines.judgeWatch(text, ok, failed)

    @Test
    fun threeAnswersStayApart() {
        assertEquals(Routines.Outcome("ok", "G123 有票，二等座 553 元"), judge("【满足】\nG123 有票，二等座 553 元"))
        assertEquals("not_met", judge("【不满足】\n还没有余票").status)
        assertEquals(Routines.Outcome("unknown", "12306 接口超时"), judge("【没查成】\n12306 接口超时"))
    }

    @Test
    fun noReliableDataIsNotNotMet() {
        // 工具全出错，模型却说「不满足」：不能当成查过了
        assertEquals("unknown", judge("【不满足】\n查询失败", ok = 0, failed = 2).status)
        // 有一次查成了：信它
        assertEquals("not_met", judge("【不满足】\n还没票", ok = 1, failed = 1).status)
        // 没按格式答：不知道到底怎样
        assertEquals("unknown", judge("好像还没有票吧").status)
    }
}
