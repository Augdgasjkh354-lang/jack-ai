package com.jack.ai.data

/**
 * 消息分支：改了消息重发、重新回答时，旧的那段不丢，留成另一个版本，能左右切着看。
 *
 * 存法：分叉处那条消息（每段的第一条）挂着其他版本 [ChatMessage.alts]，每个版本是一整段「这条 + 后面的消息」；
 * [ChatMessage.altIndex] 是眼前这段排第几。版本里的消息自己也能再挂分支（在旧版本里又改过的），切回去原样都在。
 * 思路参考 AAswordman/Operit 的消息分支（只借思路，代码自己写）。
 */
object Branches {
    /** 一处最多留几个版本，再多把最早的扔掉。 */
    const val MAX = 8

    /** 这条消息所在的分叉处一共几个版本。 */
    fun count(m: ChatMessage) = m.alts.size + 1

    /** 存进版本列表的那段：第一条不带分支信息（分支信息只挂在眼前那段的第一条上）。 */
    private fun strip(tail: List<ChatMessage>) =
        if (tail.isEmpty()) tail else listOf(tail[0].copy(alts = emptyList(), altIndex = 0)) + tail.drop(1)

    /** 第 [at] 条起的所有版本，按先后排，眼前这段在 altIndex 的位置。 */
    fun versions(msgs: List<ChatMessage>, at: Int): List<List<ChatMessage>> {
        val first = msgs.getOrNull(at) ?: return emptyList()
        val list = first.alts.toMutableList()
        list.add(first.altIndex.coerceIn(0, list.size), strip(msgs.drop(at)))
        return list
    }

    class Forked(val messages: List<ChatMessage>, val dropped: List<List<ChatMessage>>)

    /**
     * 从第 [at] 条起换成新的一段（只有 [newFirst] 一条，回答随后接上），旧的那段 [keepOld] 时留成一个版本。
     * 新版本排在最后；超过 [MAX] 个时扔掉最早的，扔掉的在 [Forked.dropped] 里（调用方清理它们的图片和文件）。
     */
    fun fork(msgs: List<ChatMessage>, at: Int, newFirst: ChatMessage, keepOld: Boolean): Forked {
        val first = msgs.getOrNull(at) ?: return Forked(msgs + newFirst, emptyList())
        val list = (if (keepOld) versions(msgs, at) else first.alts).toMutableList()
        val dropped = mutableListOf<List<ChatMessage>>()
        while (list.size >= MAX) dropped += list.removeAt(0)
        val head = newFirst.copy(alts = list, altIndex = list.size)
        return Forked(msgs.take(at) + head, dropped)
    }

    /** 第 [at] 条起切到第 [k] 个版本；没有这个版本或就是眼前这个时返回 null。 */
    fun switch(msgs: List<ChatMessage>, at: Int, k: Int): List<ChatMessage>? {
        val first = msgs.getOrNull(at) ?: return null
        if (k == first.altIndex) return null
        val list = versions(msgs, at).toMutableList()
        if (k !in list.indices) return null
        val chosen = list.removeAt(k)
        if (chosen.isEmpty()) return null
        return msgs.take(at) + chosen[0].copy(alts = list, altIndex = k) + chosen.drop(1)
    }

    /** 这些消息加上它们所有版本里的消息（清理图片、文件时用）。 */
    fun all(msgs: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        fun walk(list: List<ChatMessage>) {
            list.forEach { m -> out += m; m.alts.forEach(::walk) }
        }
        walk(msgs)
        return out
    }

    /** 这段值不值得留成一个版本：有像样的回答（写了字或交了文件）才留，只有报错的不留。 */
    fun worthKeeping(tail: List<ChatMessage>) =
        tail.any { it.role == "assistant" && (it.content.isNotBlank() || it.files.isNotEmpty()) }
}
