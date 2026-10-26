package com.jack.ai.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 新对话开场时记下「最近几场对话」：标题、时间、最后聊到哪（用户最后说了什么、你最后答了什么）。
 * 不调模型、不花钱；隐私项目里的、关了全局记忆的对话不放进来，这个对话自己关了全局记忆也不带。
 */
object Recap {
    const val MAX = 5
    /** 只看最近几天的。 */
    const val DAYS = 7

    fun build(current: Conversation, all: List<Conversation>, projects: List<Project>, now: Long = System.currentTimeMillis()): String {
        val here = projects.firstOrNull { it.id == current.projectId }
        if (!current.globalAllowed(here)) return ""
        val f = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        return all.asSequence()
            .filter { it.id != current.id && it.messages.any { m -> m.role == "user" } }
            .filter { now - it.updatedAt < DAYS * 86_400_000L }
            .filter { c -> c.globalAllowed(projects.firstOrNull { it.id == c.projectId }) }
            .sortedByDescending { it.updatedAt }
            .take(MAX)
            .map { c ->
                val user = c.messages.lastOrNull { it.role == "user" && it.content.isNotBlank() }?.content
                val reply = c.messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content
                val project = projects.firstOrNull { it.id == c.projectId }?.name?.let { "，项目「$it」" }.orEmpty()
                "- 「${c.title}」（${f.format(Date(c.updatedAt))}$project）" +
                    (user?.let { "：用户最后说「${clip(it, 60)}」" } ?: "") +
                    (reply?.let { "；你最后答「${clip(it, 80)}」" } ?: "")
            }.joinToString("\n")
    }

    /** 一行装得下：去掉换行和 Markdown 记号，截短。 */
    fun clip(s: String, n: Int): String {
        val flat = s.replace(Regex("""[#*`>|]+"""), "").replace(Regex("""\s+"""), " ").trim()
        return if (flat.length > n) flat.take(n) + "…" else flat
    }
}
