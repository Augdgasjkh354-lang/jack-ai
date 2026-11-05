package com.jack.ai.data

import com.jack.ai.data.AiTone.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToneTest {
    @Test fun flagsTypicalAiHabits() {
        val text = """
            这不是一次简单的升级，而是一场彻底的变革。核心是：用户要的是确定性。
            它覆盖了火锅、快餐、家庭聚餐和独自进食等场景。叫什么名字？承载什么功能？嵌入哪个生态？
            这是一个能够让团队在不增加人力的情况下显著提升审核速度的工具。值得注意的是，市场还在变化。
        """.trimIndent()
        val r = AiTone.check(text)
        listOf(Rule.NOT_BUT, Rule.COLON, Rule.LIST, Rule.TRIPLET, Rule.LONG_MOD, Rule.CLICHE).forEach {
            assertTrue("$it 没命中：${r.hits}", r.count(it) >= 1)
        }
        // 前后对比：改掉以后对应的数降下来
        val after = AiTone.format(AiTone.check("用户要的是确定性。我们把审核做快了，团队没加人。"), r)
        assertTrue(after, after.contains("不是 A，而是 B：1 → 0"))
    }

    @Test fun leavesOrdinaryWritingAlone() {
        val text = """
            老周说：明天的会推到下午。我们买了苹果、香蕉和梨，回家路上下起了雨。
            她在杭州读研，周末偶尔回来。猫趴在窗台上，一动不动地看着楼下的车。
        """.trimIndent()
        assertEquals(emptyList<AiTone.Hit>(), AiTone.check(text).hits)
    }
}
