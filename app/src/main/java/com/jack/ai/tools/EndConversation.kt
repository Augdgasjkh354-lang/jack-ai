package com.jack.ai.tools

import kotlinx.serialization.json.JsonObject

/**
 * 结束当前对话（极端辱骂时的最后一招）。代码这边只拦最明显的误用：对话里至少要有过几句话（先劝过一次才轮得到它），
 * 什么时候算极端交给提示词里的规矩。结束后不再给工具，模型平静地说一声就收尾。
 */
internal fun ToolSession.endConversation(args: JsonObject): String {
    val reason = args["reason"].text().trim().take(120)
    if (reason.length < 2) return "要写一句为什么结束（会显示给对方看）。"
    if (conv.messages.count { it.role == "user" } < 3) return "这个对话才刚开始，不能就这样结束。先明确请对方停下；对方坚持极端辱骂再说。"
    succeeded.incrementAndGet()
    ui.endConversation(reason)
    maxRounds = 0
    stopNote = "这个对话已经结束了，不要再调用工具。"
    return "这个对话已经结束（之后不能再发消息）。最后平静地说一两句：你结束了这个对话、为什么，对方可以开一个新对话。不指责，不说教。"
}
