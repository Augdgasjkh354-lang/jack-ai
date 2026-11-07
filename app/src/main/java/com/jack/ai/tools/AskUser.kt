package com.jack.ai.tools

import com.jack.ai.data.AskCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 问题卡片：用户说得模糊、Kavi 拿不准要什么时，不瞎猜也不写一长串问题，给 1 到 3 个问题，每个带几个能点的选项
 * （界面上总会多一个「其他（我自己说）」）。写完文案给修改方向也用它。卡片挂上以后这一轮就停：模型只再说一句引子，等用户点。
 */
internal fun ToolSession.askUser(args: JsonObject): String {
    val cards = parseAskCards(args).getOrElse { return it.message.orEmpty() }
    if (!ui.ask(cards)) return "这里显示不了问题卡片。直接用文字问，一次一个问题，把几个选项列出来让对方挑。"
    succeeded.incrementAndGet()
    maxRounds = 0
    stopNote = "问题卡片已经给对方看了，等对方回答，不要再调用工具。"
    return "卡片已经显示在你这条回答下面，对方点一下就能答（每张卡片还有「其他」可以自己写）。" +
        "现在写这条回答的正文：要是先给了草稿，正文就是草稿；只是在问问题，就用一句大白话说明为什么问（别把问题和选项再写一遍）。然后停下，等对方选。"
}

/** 拆出卡片并检查：1 到 3 个问题，每个 2 到 6 个选项，选项去重、去掉模型自己写的「其他」。 */
internal fun parseAskCards(args: JsonObject): Result<List<AskCard>> = runCatching {
    val raw = (args["questions"] as? JsonArray).orEmpty()
    require(raw.isNotEmpty()) { "questions 要有 1 到 3 个问题，每个问题带 options（2 到 6 个短选项）。" }
    require(raw.size <= 3) { "一次最多问 3 个问题，挑最要紧的。" }
    raw.mapIndexed { i, q ->
        val o = q as? JsonObject ?: throw IllegalArgumentException("第 ${i + 1} 个问题格式不对：要有 question 和 options。")
        val question = o["question"].text().trim().take(80)
        require(question.isNotEmpty()) { "第 ${i + 1} 个问题没写 question。" }
        val options = (o["options"] as? JsonArray).orEmpty().map { it.text().trim().take(40) }
            .filter { it.isNotEmpty() && it !in OTHER_WORDS }.distinct()
        require(options.size >= 2) { "「$question」至少要 2 个选项（「其他」界面会自己加，不用写）。" }
        require(options.size <= 6) { "「$question」的选项太多了，最多 6 个，挑最常见的。" }
        AskCard(question, options, multi = o["multi"].text() == "true")
    }
}

private val OTHER_WORDS = setOf("其他", "其它", "其他（我自己说）", "我自己说", "都不是", "其他：", "Other", "other")

/** 用户在卡片上选好的答案拼成一条消息：一个问题就「问题：答案」，几个问题一行一个。 */
internal fun askAnswer(cards: List<AskCard>, picks: List<List<String>>): String =
    cards.indices.mapNotNull { i ->
        val p = picks.getOrNull(i).orEmpty().filter { it.isNotBlank() }
        if (p.isEmpty()) null else cards[i].question.trimEnd('？', '?', '：', ':') + "：" + p.joinToString("、")
    }.joinToString("\n")
