package com.jack.ai.data

import android.content.Context
import com.jack.ai.data.memory.DeepRecall

/**
 * 按意思给这句话挑技能：技能清单一长，模型容易挑错或想不起来用。每轮拿用户这句话和每个技能的「名字：简介」比一比，
 * 最像的一两个写进这一轮的附加说明（【Matching skills】），模型自己判断用不用。
 * 只挑「技能」（做法），不挑插件（插件靠工具说明就选得对）。向量来源和记忆检索共用（硅基流动 bge-m3 或手机上的本地模型），
 * 都没有就不推荐；技能的向量另存一份，只在技能改了时重算。不花模型调用。
 */
object SkillMatcher {
    /** 最多推荐几个。 */
    private const val TOP = 2
    /** 第二名和第一名差太多就只给第一名。 */
    private const val GAP = 0.05f
    /** 比「想起来」的门槛低一点：技能简介比记忆长，和一句口语的相似度普遍偏低。 */
    private const val SLACK = 0.08f

    private fun key(s: Skill) = s.id + ":" + (s.name + s.description).hashCode()
    private fun doc(s: Skill) = s.name + "：" + s.description

    suspend fun note(ctx: Context, settings: Settings, text: String, skills: List<Skill>): String {
        val q = text.trim()
        val pool = skills.filter { it.id !in SkillIds.PLUGINS && "@${it.name}" !in q }
        if (q.length < 4 || pool.isEmpty()) return ""
        val (sims, recall) = DeepRecall.similarities(ctx, settings, q, pool.map { key(it) to doc(it) }, space = "skill")
        if (sims.isEmpty()) return ""
        val picked = pick(pool.map { it to (sims[key(it)] ?: 0f) }, recall - SLACK)
        if (picked.isEmpty()) return ""
        return Persona.fill("【Matching skills】By meaning, {user}'s message may call for: " + picked.joinToString("、") { "「${it.name}」" } +
            ". If it fits, read it with use_skill before starting; if not, ignore this.\n", settings)
    }

    /** 过门槛的前两名；第二名要和第一名差不多才带上。 */
    internal fun pick(scored: List<Pair<Skill, Float>>, min: Float): List<Skill> {
        val top = scored.filter { it.second >= min }.sortedByDescending { it.second }.take(TOP)
        if (top.isEmpty()) return emptyList()
        return top.filter { it.second >= top.first().second - GAP }.map { it.first }
    }
}
