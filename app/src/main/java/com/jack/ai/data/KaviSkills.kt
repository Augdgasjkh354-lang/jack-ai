package com.jack.ai.data

/**
 * Kavi 自己写的技能（借鉴 Hermes Agent 从经验里长出技能）：做完一件好几步、以后还会遇到的事，把做法存下来，下次 use_skill 读出来照着做。
 * 边界：只能新建、改、删它自己写的（[Skill.byKavi]），内置的和用户写的碰不了；名字不能和别的技能撞；有长度和数量上限。
 */
object KaviSkills {
    const val MAX_SKILLS = 30
    const val MAX_NAME = 30
    const val MAX_DESC = 200
    const val MIN_BODY = 40
    const val MAX_BODY = 4000

    fun mine(s: Settings) = s.customSkills.filter { it.byKavi }

    /** replace 填的名字对上哪个自己写的技能：先一字不差，再包含。 */
    fun target(s: Settings, old: String): Skill? {
        if (old.isBlank()) return null
        val mine = mine(s)
        return mine.firstOrNull { it.name == old } ?: mine.filter { it.name.contains(old, true) }.singleOrNull()
    }

    /** 这次存（或删）有什么问题；没问题返回 null。 */
    fun problem(s: Settings, name: String, desc: String, body: String, old: String): String? {
        val target = target(s, old)
        val mine = mine(s)
        if (old.isNotBlank() && target == null) {
            return if (s.allSkills().any { it.name == old }) "「$old」不是你写的技能，只能改、删你自己写的。"
            else "没有你写的「$old」。你写过的：" + mine.joinToString("、") { it.name }.ifEmpty { "（还没有）" }
        }
        if (body.isBlank()) return if (target == null) "instructions 是空的：写清步骤、要注意的坑和怎么检查" else null
        return when {
            name.isBlank() || desc.isBlank() -> "name 和 description 都要填"
            name.length > MAX_NAME -> "名字最多 $MAX_NAME 字"
            body.length < MIN_BODY -> "做法太短了：写清步骤、要注意的坑和怎么检查做对了"
            body.length > MAX_BODY -> "做法最多 $MAX_BODY 字，现在 ${body.length} 字，精简一下"
            s.allSkills().any { it.name.equals(name, true) && it.id != target?.id } -> "已经有叫「$name」的技能了。换个名字；要补充你自己写的，用 replace 改它"
            target == null && mine.size >= MAX_SKILLS -> "你写的技能已经有 $MAX_SKILLS 个了，先删掉或合并用不上的（replace 填名字、instructions 留空就是删）"
            else -> null
        }
    }

    /** 存好后的技能列表：新建、改，或者（body 为空）删掉。 */
    fun apply(s: Settings, name: String, desc: String, body: String, old: String): Pair<List<Skill>, Skill?> {
        val target = target(s, old)
        if (body.isBlank()) return s.customSkills.filterNot { it.id == target?.id } to null
        val skill = (target ?: Skill(name = name, description = desc, instructions = body, byKavi = true))
            .copy(name = name.trim(), description = desc.trim().take(MAX_DESC), instructions = body.trim(), byKavi = true)
        return (if (target == null) s.customSkills + skill else s.customSkills.map { if (it.id == target.id) skill else it }) to skill
    }

    const val NOTICE_PREFIX = "Kavi 存了技能"
}
