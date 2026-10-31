package com.jack.ai.chat

import com.jack.ai.data.MemoryItem

/**
 * 系统提示词里的记忆（长期记忆、对话和项目记忆、日记、自传、近况、工作记忆）按对话拍一张快照，聊天中途不变：
 * 系统提示词在最前面，中途改一个字，后面整段的前缀缓存就全失效（借鉴 Hermes Agent 的冻结快照）。
 * 中途存的记忆照样马上写进文件，Kavi 存的时候在工具结果里已经看到了；下一张快照才放进提示词。
 *
 * 什么时候重拍：对话被压缩过（缓存反正断了，正好把记忆补全）、隔了 [STALE_MS] 没聊（缓存多半已经过期，
 * 顺便带上夜里做梦整理过的新记忆）、用户自己在界面上改了记忆（[clear]）。
 */
class MemorySnapshots {
    data class Snap(
        val memories: List<MemoryItem>,
        val chatMemory: String,
        val projectMemory: String,
        val journal: String,
        val story: String,
        val lately: String,
        val workMemory: String,
        /** 拍的时候对话压缩到哪条。 */
        val summaryUntilId: String?,
        val usedAt: Long,
    )

    private val snaps = java.util.concurrent.ConcurrentHashMap<String, Snap>()

    /** 这个对话现在该用的快照；该重拍就调 [fresh] 拍一张新的。 */
    fun get(convId: String, summaryUntilId: String?, now: Long, fresh: () -> Snap): Snap {
        val old = snaps[convId]
        val snap = if (old != null && !stale(old, summaryUntilId, now)) old.copy(usedAt = now) else fresh().copy(summaryUntilId = summaryUntilId, usedAt = now)
        snaps[convId] = snap
        return snap
    }

    /** 用户在界面上改了记忆：所有对话下一轮重拍，改的马上生效。 */
    fun clear() = snaps.clear()

    fun drop(convId: String) { snaps.remove(convId) }

    companion object {
        /** 隔这么久没聊就重拍。 */
        const val STALE_MS = 2 * 60 * 60 * 1000L

        internal fun stale(old: Snap, summaryUntilId: String?, now: Long): Boolean =
            old.summaryUntilId != summaryUntilId || now - old.usedAt >= STALE_MS
    }
}
