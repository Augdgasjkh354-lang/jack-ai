package com.jack.ai.chat

import com.jack.ai.data.ChatMessage

/**
 * 用户按了停止：在回答后面补几行交代（不再调模型）：做了哪些步、清单做到哪、还差什么，说「继续」能接着做。
 * 下一轮模型也看得到这段，知道从哪接着。思路来自 Mika 停下后的小结，Apache-2.0。
 */
object StopReport {
    fun build(m: ChatMessage): String {
        val steps = m.searches.filter { !it.pending && it.error == null }.map { it.query }
        val failed = m.searches.count { it.error != null }
        val done = m.plan.filter { it.status == "done" }
        val left = m.plan.filter { it.status != "done" }
        if (steps.isEmpty() && m.plan.isEmpty()) return ""
        return buildString {
            append("\n\n——\n⏹ 已按你的要求停下。")
            if (steps.isNotEmpty()) {
                append("做了 ${steps.size} 步")
                if (failed > 0) append("（另有 $failed 步没成）")
                append("，最后几步：").append(steps.takeLast(3).joinToString("；") { it.take(40) }).append("。")
            }
            if (m.plan.isNotEmpty()) {
                append("清单完成 ${done.size}/${m.plan.size}")
                if (left.isNotEmpty()) append("，还没做：").append(left.take(4).joinToString("；") { it.text.take(40) })
                append("。")
            }
            append("说「继续」就从这里接着做。")
        }
    }
}
