package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMatcherTest {
    private fun sk(n: String) = Skill(id = n, name = n, description = n, instructions = "")

    @Test fun picksTopTwoWhenClose() {
        val got = SkillMatcher.pick(listOf(sk("a") to 0.70f, sk("b") to 0.68f, sk("c") to 0.40f), 0.5f)
        assertEquals(listOf("a", "b"), got.map { it.name })
    }

    @Test fun dropsSecondWhenFarBehind() {
        assertEquals(listOf("a"), SkillMatcher.pick(listOf(sk("a") to 0.80f, sk("b") to 0.60f), 0.5f).map { it.name })
    }

    @Test fun nothingBelowThreshold() {
        assertTrue(SkillMatcher.pick(listOf(sk("a") to 0.45f), 0.5f).isEmpty())
    }

    @Test fun everyBuiltinIsEitherPluginOrSkill() {
        // 插件清单里的都还在；技能这边留着做法类的
        val ids = BUILTIN_SKILLS.map { it.id }.toSet()
        assertTrue((SkillIds.PLUGINS - SkillIds.FLOWS).all { it in ids })
        assertTrue(SkillIds.TOOL_PLUGIN.values.all { it in SkillIds.PLUGINS })
        val playbooks = BUILTIN_SKILLS.filter { it.id !in SkillIds.PLUGINS }.map { it.name }
        assertEquals(20, playbooks.size)
        assertTrue("视频剪辑" in playbooks && "小红书视觉导演" in playbooks && "一稿多发" in playbooks && "图解" in playbooks && "动画短片" in playbooks && "长视频切片" in playbooks)
    }
}
