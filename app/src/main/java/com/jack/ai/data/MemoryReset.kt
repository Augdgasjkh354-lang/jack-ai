package com.jack.ai.data

import android.content.Context
import java.io.File

/**
 * 重置记忆：清掉关于用户的一切（常记、深处、作废存档、历史版本、心里挂着的事、经历流、整理记录），
 * 做事的经验都留着：浏览器流程和手机流程、网站和 App 备忘、技能、事实库、做事经验、聊天记录本身。
 * 要的话连各对话、项目里的记忆文件一起清。
 *
 * 清之前整份挪进 files/memory_reset/<时间>/，留 [KEEP_DAYS] 天，期间能一键恢复，过期自动删。
 * 「上次整理到哪」不动：重置后夜间整理不会把以前的聊天重新翻一遍记回来。
 */
object MemoryReset {
    const val KEEP_DAYS = 7
    private const val DIR = "memory_reset"

    /** 关于用户的那些文件（相对 filesDir）；Store 的 JSON 连 .prev 副本一起挪走，免得坏了时又被读回来。 */
    private val FILES = listOf(
        "memories.json", "memories.json.prev", "dreams.json", "dreams.json.prev",
        "deep_memories.json", "memories_archive.json", "memories_versions",
        "threads.json", "timeline.jsonl", "dream_held.txt",
    )

    data class Backup(val at: Long, val dir: File) {
        val expiresAt get() = at + KEEP_DAYS * 86_400_000L
    }

    private fun root(ctx: Context) = File(ctx.filesDir, DIR)

    /** 各对话、项目里的记忆文件（相对 filesDir）。 */
    private fun chatFiles(ctx: Context, store: Store): List<String> {
        val base = ctx.filesDir.canonicalPath + File.separator
        val files = store.loadConversations().map { ChatMemory.chatFile(ctx, it) } +
            store.loadProjects().map { ChatMemory.projectFile(ctx, it.id) }
        return files.filter { it.exists() }.map { it.canonicalPath }.filter { it.startsWith(base) }.map { it.removePrefix(base) }.distinct()
    }

    /** 重置。返回挪走了几样东西。调用方要放在和其他记忆读写同一条串行队列上。 */
    fun reset(ctx: Context, store: Store, withChatFiles: Boolean, now: Long = System.currentTimeMillis()): Int {
        expire(ctx, now)
        val dir = File(root(ctx), now.toString())
        val names = FILES + if (withChatFiles) chatFiles(ctx, store) else emptyList()
        var moved = 0
        // 挪的时候不让顺手记、夜间整理同时写记忆
        store.locked { for (name in names) {
            val src = File(ctx.filesDir, name)
            if (!src.exists()) continue
            val dst = File(dir, name).also { it.parentFile?.mkdirs() }
            // 同一个分区里改名是原子的；改名不成（极少）就复制后删掉
            if (!src.renameTo(dst)) { src.copyRecursively(dst, overwrite = true); src.deleteRecursively() }
            moved++
        } }
        return moved
    }

    /** 还能恢复的那份（最新的）；没有或过期了是 null。 */
    fun latest(ctx: Context, now: Long = System.currentTimeMillis()): Backup? =
        root(ctx).listFiles()?.mapNotNull { d -> d.name.toLongOrNull()?.let { Backup(it, d) } }
            ?.filter { it.expiresAt > now }?.maxByOrNull { it.at }

    /** 恢复：把那份挪回原处（重置后新记下的会被换掉），然后删掉这份。 */
    fun restore(ctx: Context, store: Store, backup: Backup): Int {
        var moved = 0
        store.locked { backup.dir.walkTopDown().filter { it.isFile }.toList().forEach { f ->
            val rel = f.relativeTo(backup.dir).path
            val dst = File(ctx.filesDir, rel).also { it.parentFile?.mkdirs() }
            if (!f.renameTo(dst.also { it.delete() })) f.copyTo(dst, overwrite = true)
            moved++
        } }
        backup.dir.deleteRecursively()
        return moved
    }

    /** 过了 [KEEP_DAYS] 天的删掉。 */
    fun expire(ctx: Context, now: Long = System.currentTimeMillis()) {
        root(ctx).listFiles()?.forEach { d ->
            val at = d.name.toLongOrNull()
            if (at == null || at + KEEP_DAYS * 86_400_000L <= now) d.deleteRecursively()
        }
    }
}
