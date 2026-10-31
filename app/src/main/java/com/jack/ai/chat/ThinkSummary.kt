package com.jack.ai.chat

/**
 * 思考摘要（像 ChatGPT 思考时的一行行小标题）：模型的原始思考又长又乱，每攒够一段就请后台模型压成一行，
 * 显示在回答上面。原始思考照样在「深度思考」里。
 */
object ThinkSummary {
    /** 攒够这么多字的新思考才总结一次。 */
    const val CHUNK = 300
    /** 回答开始以后，剩下的不到一段也总结掉，少于这么多字就算了。 */
    const val TAIL = 80
    /** 一次回答最多几条摘要。 */
    const val MAX = 12
    /** 多久看一次有没有新思考。 */
    const val POLL_MS = 2_500L
    /** 一段思考最多给后台模型看多少字。 */
    private const val INPUT = 3_000

    /** 该不该总结这一段：够长，或者回答已经开始、剩下的也值得说一句。 */
    fun due(newChars: Int, answering: Boolean): Boolean = newChars >= CHUNK || (answering && newChars >= TAIL)

    const val SYSTEM = """You write the progress headings shown while an assistant is thinking, like "Comparing the two plans" or "Checking where the numbers came from".
Given the headings so far and a new stretch of the assistant's private thinking, write ONE new heading for the new stretch:
- at most 18 Chinese characters (or 8 English words), in the language of the thinking
- say what it is doing or found, concretely ("核对两家的报价", "发现路径写错了"), not "思考中" or "分析问题"
- no punctuation at the end, no quotes, no numbering, don't repeat an earlier heading
Reply with the heading only."""

    fun input(previous: List<String>, chunk: String): String =
        (if (previous.isEmpty()) "" else "Headings so far:\n" + previous.joinToString("\n") { "- $it" } + "\n\n") +
            "New thinking:\n" + chunk.takeLast(INPUT)

    /** 模型回的那一行：去掉引号、编号、末尾标点，太长截断；空的或和上一条一样就不要。 */
    fun clean(raw: String, previous: List<String>): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val t = line.removePrefix("-").removePrefix("•").trim()
            .replace(Regex("^\\d+[.、)]\\s*"), "")
            .trim('"', '“', '”', '「', '」', '\'', '*', ' ')
            .trimEnd('。', '.', '，', ',', '；', ';', '：', ':', '！', '!')
            .take(40)
        return t.takeIf { it.isNotEmpty() && it != previous.lastOrNull() }
    }
}
