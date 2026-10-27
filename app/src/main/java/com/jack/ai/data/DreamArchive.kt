package com.jack.ai.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 合并和清理之前保留原篇，已有的合并篇也保留其标记。 */
object DreamArchive {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "dream_originals.json"))
    fun load(ctx: Context): List<DreamEntry> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(DreamEntry.serializer()), String(file(ctx).readFully())) }.getOrDefault(emptyList())
    }
    private fun save(ctx: Context, entries: List<DreamEntry>) {
        val f = file(ctx); val out = f.startWrite()
        try { out.write(json.encodeToString(ListSerializer(DreamEntry.serializer()), entries).toByteArray()); f.finishWrite(out) }
        catch (e: Exception) { f.failWrite(out); throw e }
    }
    fun preserve(ctx: Context, entries: List<DreamEntry>) = synchronized(lock) {
        val all = load(ctx).associateBy { it.id }.toMutableMap()
        entries.forEach { all[it.id] = it }
        save(ctx, all.values.toList())
    }
    /** 用户删除一篇合并日记时，同时删除它对应的原篇。 */
    fun delete(ctx: Context, entry: DreamEntry) = synchronized(lock) {
        save(ctx, load(ctx).filterNot { it.id == entry.id || (entry.merged > 0 && it.createdAt in entry.from..entry.createdAt) })
    }
}
