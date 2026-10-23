package com.jack.ai.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** 销售三件套里，GPT 和小 Jack 评审时要求的几条边界都写在技能里，改技能时别丢。 */
class SalesSkillsTest {
    private fun skill(id: String) = BUILTIN_SKILLS.first { it.id == id }

    @Test
    fun theyAreOnByDefault() {
        val on = Settings().activeSkills().map { it.id }
        listOf(SkillIds.SALES_LOG, SkillIds.SALES_ADVISOR, SkillIds.SALES_REVIEW).forEach { assertTrue(it, it in on) }
    }

    @Test
    fun logKeepsIdentityFactsAndFriction() {
        val t = skill(SkillIds.SALES_LOG).instructions
        listOf("编号", "别名", "拿不准", "只记事实", "最多问一句", "原话", "只把对方的气泡", "不重复记", "具体日子", "锁屏", "只问一句").forEach {
            assertTrue("记客户里少了「$it」", it in t)
        }
    }

    @Test
    fun advisorIsHonestAndNotFalselyPrecise() {
        val t = skill(SkillIds.SALES_ADVISOR).instructions
        listOf("依据", "不报百分比", "最多一两个", "不编造事实", "不逼迫", "直说不编", "先跟公司确认").forEach {
            assertTrue("参谋里少了「$it」", it in t)
        }
    }

    @Test
    fun reviewCountsFromRecordsAndAdmitsSmallSamples() {
        val t = skill(SkillIds.SALES_REVIEW).instructions
        listOf("query_table", "不心算", "发生的日期", "样本太少", "三件事").forEach {
            assertTrue("复盘里少了「$it」", it in t)
        }
    }
}
