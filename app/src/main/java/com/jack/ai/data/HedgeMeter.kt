package com.jack.ai.data

import android.content.Context

/**
 * 量 Kavi 说话绕不绕：回答里每千字有几处转折、限定（「但」「不过」「不意味着」「不是……而是」这类）。
 * 用来看改提示词前后有没有变化；只看 Chat 的回答，代码块不算。
 */
object HedgeMeter {
    // 长的放前面：「但是」算一处，不再拆出一个「但」
    private val TURNS = Regex(
        "并不意味着|不意味着|不代表|不足以|并不是|不仅仅是|不只是|而是|但是|但|不过|然而|可是|只是说|当然也|" +
            "\\b(?:but|however|though|that doesn't mean|not necessarily|not just|rather than)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CODE = Regex("```[\\s\\S]*?```")

    /** 一段文字里有几处。 */
    fun count(text: String): Int = TURNS.findAll(CODE.replace(text, "")).count()

    /** 一组回答的结果：几条、共多少字、每千字几处。 */
    data class Reading(val answers: Int, val chars: Int, val turns: Int) {
        val perThousand: Double get() = if (chars == 0) 0.0 else turns * 1000.0 / chars
    }

    fun read(texts: List<String>): Reading {
        val clean = texts.map { CODE.replace(it, "") }
        return Reading(clean.size, clean.sumOf { it.length }, clean.sumOf { TURNS.findAll(it).count() })
    }

    /** 改前、改后各取最近 [n] 条 Chat 回答。分界是这一版第一次打开统计页的时间（存在手机上）。 */
    fun beforeAfter(ctx: Context, convs: List<Conversation>, n: Int = 40): Pair<Reading, Reading> {
        val since = since(ctx)
        val answers = convs.filter { !it.work }.flatMap { it.messages }
            .filter { it.role == "assistant" && it.content.isNotBlank() && it.error == null }
            .sortedByDescending { it.createdAt }
        val after = answers.filter { it.createdAt >= since }.take(n).map { it.content }
        val before = answers.filter { it.createdAt < since }.take(n).map { it.content }
        return read(before) to read(after)
    }

    private fun since(ctx: Context): Long {
        val p = ctx.getSharedPreferences("hedge", Context.MODE_PRIVATE)
        return p.getLong(KEY, 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { p.edit().putLong(KEY, it).apply() }
    }

    // 提示词改成「先下判断」的那一版
    private const val KEY = "directPromptSince"
}
