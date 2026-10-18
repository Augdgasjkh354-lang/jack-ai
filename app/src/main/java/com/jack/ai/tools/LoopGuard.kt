package com.jack.ai.tools

/**
 * 防原地打转（1017 版偶尔会反复调同一个工具却不往前走）：同样的工具、同样的参数连着调 3 次，
 * 或者一次回答里一共调 5 次（读文件除外，改完再读很正常），结果不会变，就不执行，让模型停下换方法。
 */
class LoopGuard {
    private var lastCall = ""
    private var streak = 0
    private val counts = HashMap<String, Int>()

    /** 该拦就返回给模型看的话，否则 null。 */
    @Synchronized
    fun check(name: String, argsText: String): String? {
        val sig = name + "|" + argsText.trim()
        streak = if (sig == lastCall) streak + 1 else 1
        lastCall = sig
        val total = (counts[sig] ?: 0) + 1
        counts[sig] = total
        if (streak < 3 && (total < 5 || name == "read_file")) return null
        return "你已经用完全相同的参数调用 $name ${maxOf(streak, total)} 次了，结果不会变。不要再重复：换个方法，或者直接告诉用户卡在哪里、需要什么。"
    }
}
