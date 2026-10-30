package com.jack.ai.ui

/**
 * 输入框里打「@」就地补全技能名、邮箱：光标前面是「@xx」就算在找。
 * 「@」前面紧挨着英文字母、数字这类（像 jack@gmail.com 里那样）不算，那是在写邮箱地址；中文后面直接打 @ 算。
 */
internal fun mentionQuery(text: String, cursor: Int): String? {
    val before = text.substring(0, cursor.coerceIn(0, text.length))
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    val query = before.substring(at + 1)
    if (query.any { it.isWhitespace() }) return null
    val prev = before.getOrNull(at - 1)
    if (prev != null && (prev in 'a'..'z' || prev in 'A'..'Z' || prev in '0'..'9' || prev in "._%+-")) return null
    return query
}

/** 正在找的候选：名字里包含已打的字（不分大小写），整段已经写进去的不再列。最多 [limit] 个。 */
internal fun mentionCandidates(names: List<String>, query: String, text: String, limit: Int = 8): List<String> =
    names.distinct()
        .filter { it.contains(query, ignoreCase = true) && "@$it " !in text }
        .sortedBy { if (it.startsWith(query, ignoreCase = true)) 0 else 1 }
        .take(limit)

/** 把光标前的「@xx」换成「@名字 」，返回新文字和新光标位置。 */
internal fun completeMention(text: String, cursor: Int, name: String): Pair<String, Int> {
    val c = cursor.coerceIn(0, text.length)
    val at = text.lastIndexOf('@', c - 1)
    if (at < 0) return text to c
    val insert = "@$name "
    // 光标后面本来就是空格的话，不再多补一个
    val after = text.substring(c).let { if (it.startsWith(" ")) it.substring(1) else it }
    return text.substring(0, at) + insert + after to at + insert.length
}
