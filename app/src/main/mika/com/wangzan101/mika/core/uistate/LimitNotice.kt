/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: compare Kavi's non-editable screen text instead of ScreenSnapshot.
 */
package com.wangzan101.mika.core.uistate

/** 只抓动作后新出现的短限额提示，不把原本在页面里的说明误判成拦截。 */
object LimitNotice {
    private val words = listOf(
        "次数已用完", "次数用完", "机会已用完", "额度已用完", "已达上限", "已达到上限",
        "达到上限", "超过上限", "超出上限", "明天再来", "明日再来", "明天再试", "明日再试",
        "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁", "访问过于频繁",
        "limitreached", "reachedthelimit", "reachedyourlimit", "dailylimit",
        "tryagaintomorrow", "comebacktomorrow", "toomanyrequests", "doingthattoooften",
    )
    private val reached = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")
    private fun compact(value: String) = value.filterNot(Char::isWhitespace).lowercase()

    fun find(before: List<String>?, after: List<String>?): String? {
        val earlier = before.orEmpty().map(::compact).toSet()
        return after.orEmpty().firstOrNull { value ->
            val text = compact(value)
            text.isNotBlank() && text.length <= 120 && text !in earlier &&
                (words.any { it in text } || reached.containsMatchIn(text))
        }?.trim()
    }
}
