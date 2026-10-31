package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaviSkillsTest {
    private val body = "1. 先做这个。\n2. 再做那个。\n3. 最后检查输出的文件能打开，行数对得上。"
    private val mineSkill = Skill(name = "整理发票", description = "发票拍照后整理", instructions = body, byKavi = true)
    private val userSkill = Skill(name = "写周报", description = "用户写的", instructions = body)
    private val s = Settings(customSkills = listOf(mineSkill, userSkill))

    @Test fun createsNew() {
        assertNull(KaviSkills.problem(s, "查火车票", "查票时用", body, ""))
        val (list, skill) = KaviSkills.apply(s, "查火车票", "查票时用", body, "")
        assertEquals(3, list.size)
        assertTrue(skill!!.byKavi)
    }

    @Test fun cannotTouchUsersOrBuiltinOrClash() {
        assertNotNull(KaviSkills.problem(s, "写周报", "x", body, "写周报"))
        assertNotNull(KaviSkills.problem(s, "写周报", "x", body, ""))
        assertNotNull(KaviSkills.problem(s, "新的", "x", "太短", ""))
    }

    @Test fun updatesAndDeletesOwn() {
        val (list, skill) = KaviSkills.apply(s, "整理发票", "更新的说明", body + "\n4. 再核对金额。", "发票")
        assertEquals(mineSkill.id, skill!!.id)
        assertEquals(2, list.size)
        assertNull(KaviSkills.problem(s, "", "", "", "整理发票"))
        val (left, gone) = KaviSkills.apply(s, "", "", "", "整理发票")
        assertNull(gone)
        assertEquals(listOf(userSkill), left)
    }

    @Test fun capsCount() {
        val many = Settings(customSkills = List(KaviSkills.MAX_SKILLS) { Skill(name = "k$it", description = "d", instructions = body, byKavi = true) })
        assertNotNull(KaviSkills.problem(many, "再一个", "d", body, ""))
    }
}
