package com.jack.ai.data

import android.content.Context
import com.jack.ai.data.integrations.GitHub

/**
 * 知识库的装、同步、开关、删除（界面和 Kavi 的 knowledge_base 工具共用）。
 * 知识库只是资料（检索时读出来给 Kavi 参考），不是指令，所以装和更新不用逐条过目；检索结果里的疑似注入照样会被标出来。
 * 同步按文件的 sha 比：只下变了的、新加的，仓库里删掉的也跟着删。
 */
object KnowledgeBases {
    private val lock = Any()

    /** 一次装或同步的结果。 */
    data class Result(val kb: KnowledgeBase, val added: Int, val updated: Int, val removed: Int, val failed: List<String>, val skipped: Int) {
        val changed get() = added + updated + removed > 0
        fun line(): String = "「${kb.name}」" + when {
            !changed && failed.isEmpty() -> "已是最新"
            else -> listOfNotNull("新增 $added 份".takeIf { added > 0 }, "更新 $updated 份".takeIf { updated > 0 }, "删掉 $removed 份".takeIf { removed > 0 })
                .joinToString("，").ifEmpty { "没有变化" }
        } + (if (failed.isNotEmpty()) "；${failed.size} 份没读成（${failed.take(3).joinToString("、")}${if (failed.size > 3) "…" else ""}）" else "") +
            (if (skipped > 0) "；文件太多，后面 $skipped 份没收" else "")
    }

    /** 仓库里一个知识库，加上叫什么、装没装（列给用户挑）。 */
    data class Entry(val found: KbRepo.Found, val name: String, val description: String, val installed: KnowledgeBase?) {
        val state get() = when { installed == null -> "没装"; installed.hash == found.hash -> "已装（最新）"; else -> "已装，有更新" }
    }

    fun installedFrom(store: Store, l: KbRepo.Listing, f: KbRepo.Found) = store.loadKbs().firstOrNull { it.source == KbRepo.source(l.repo, l.branch, f.dir) }

    /** 列出仓库里的知识库（要联网，在后台线程调）。 */
    fun catalog(store: Store, token: String, repo: String, ref: String = ""): Pair<KbRepo.Listing, List<Entry>> {
        val l = KbRepo.list(token, repo, ref)
        return l to l.bases.map { f ->
            val m = KbRepo.meta(token, l, f)
            Entry(f, m.name, m.description, installedFrom(store, l, f))
        }
    }

    /**
     * 装一个知识库，或者把装过的更新到仓库里的样子（在后台线程调）。
     * 先把要的文件全下完再一起写进去：中途断网不会留下半个知识库。
     */
    fun install(ctx: Context, store: Store, token: String, l: KbRepo.Listing, f: KbRepo.Found, name: String = "", description: String = ""): Result {
        val old = installedFrom(store, l, f)
        val meta = if (name.isBlank()) KbRepo.meta(token, l, f) else SkillRepo.Parsed(name, description, "")
        val kbId = old?.id ?: newId()
        val have = store.loadLibrary().filter { it.kb == kbId }
        val byPath = have.associateBy { it.path }
        val want = f.files.map { it.path }.toSet()
        val failed = ArrayList<String>()
        // 新的、sha 变了的、正文丢了的（备份恢复时知识库的正文也在 library 文件夹里，一般都在）
        val fetched = f.files.filter { file ->
            val d = byPath[file.path]
            d == null || d.sha != file.sha || store.libraryText(d.id).isEmpty()
        }.mapNotNull { file ->
            runCatching {
                val text = Attachments.textOf(file.path, KbRepo.bytes(token, l.repo, l.branch, file.path))
                if (text.isBlank()) throw IllegalArgumentException("读不出文字")
                file to text
            }.onFailure { failed += KbRepo.docName(f, file.path) }.getOrNull()
        }
        if (old == null && fetched.isEmpty()) throw IllegalArgumentException(
            if (f.files.isEmpty()) "这里没有能当资料的文件（md、txt、pdf、docx 这类）" else "文件都没下载成，检查一下网络或 GitHub 令牌")
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            var added = 0; var updated = 0
            val docs = store.loadLibrary().toMutableList()
            fetched.forEach { (file, text) ->
                val prev = docs.indexOfFirst { it.kb == kbId && it.path == file.path }
                val doc = LibraryDoc(id = if (prev >= 0) docs[prev].id else newId(), name = KbRepo.docName(f, file.path), chars = text.length,
                    addedAt = if (prev >= 0) docs[prev].addedAt else now, kb = kbId, path = file.path, sha = file.sha)
                store.saveLibraryText(doc.id, text)
                if (prev >= 0) { docs[prev] = doc; updated++ } else { docs += doc; added++ }
            }
            // 仓库里删掉的（下载失败的不算删）
            val gone = docs.filter { it.kb == kbId && it.path !in want }
            gone.forEach { store.deleteLibraryText(it.id) }
            docs.removeAll(gone.toSet())
            store.saveLibrary(docs)
            val kb = (old ?: KnowledgeBase(id = kbId, name = meta.name, addedAt = now)).copy(
                name = meta.name.ifBlank { old?.name ?: "知识库" }, description = meta.description.take(300),
                source = KbRepo.source(l.repo, l.branch, f.dir),
                // 有没下成的就先不记新指纹，下次同步还会再试
                hash = if (failed.isEmpty()) f.hash else old?.hash.orEmpty(), updatedAt = now,
            )
            store.saveKbs(store.loadKbs().filter { it.id != kbId } + kb)
            Result(kb, added, updated, gone.size, failed, f.skipped)
        }
    }

    /** 同步从 GitHub 装的知识库（only 不空就只同步这几个）。返回每个的结果和没查成的。 */
    fun sync(ctx: Context, store: Store, token: String, only: Set<String> = emptySet()): Pair<List<Result>, List<String>> {
        val mine = store.loadKbs().filter { it.source.isNotEmpty() && (only.isEmpty() || it.id in only) }
        val results = ArrayList<Result>()
        val failed = ArrayList<String>()
        mine.groupBy { KbRepo.repoOf(it.source) + "@" + KbRepo.branchOf(it.source) }.forEach { (key, group) ->
            val l = runCatching { KbRepo.list(token, key.substringBefore('@'), key.substringAfter('@')) }
                .getOrElse { e -> failed += "${key.substringBefore('@')}：${e.message?.take(80)}"; return@forEach }
            group.forEach { kb ->
                val f = l.bases.firstOrNull { it.dir == KbRepo.dirOf(kb.source) }
                if (f == null) { failed += "「${kb.name}」在仓库里已经没有了（没删，要删在资料库里删）"; return@forEach }
                val missing = store.loadLibrary().any { it.kb == kb.id && store.libraryText(it.id).isEmpty() }
                if (f.hash == kb.hash && !missing) { results += Result(kb, 0, 0, 0, emptyList(), 0); return@forEach }
                runCatching { install(ctx, store, token, l, f, kb.name, kb.description) }
                    .onSuccess { results += it }.onFailure { e -> failed += "「${kb.name}」：${e.message?.take(80)}" }
            }
        }
        markChecked(ctx)
        return results to failed
    }

    /** 删掉一个知识库和它的全部文档。 */
    fun remove(store: Store, kbId: String) = synchronized(lock) {
        val docs = store.loadLibrary()
        docs.filter { it.kb == kbId }.forEach { store.deleteLibraryText(it.id) }
        store.saveLibrary(docs.filterNot { it.kb == kbId })
        store.saveKbs(store.loadKbs().filterNot { it.id == kbId })
    }

    fun setEnabled(store: Store, kbId: String, on: Boolean) = synchronized(lock) {
        store.saveKbs(store.loadKbs().map { if (it.id == kbId) it.copy(enabled = on) else it })
    }

    /**
     * Kavi 往自己建的知识库里写一份文档：同名的就是改（content 为空就是删）。从 GitHub 装的不能写（同步时会被盖掉）。
     * 返回给 Kavi 看的一句话；出错抛异常。
     */
    fun write(store: Store, kbId: String, title: String, content: String): String = synchronized(lock) {
        val kb = store.loadKbs().firstOrNull { it.id == kbId } ?: throw IllegalArgumentException("没有这个知识库")
        if (kb.source.isNotEmpty()) throw IllegalArgumentException("「${kb.name}」是从 GitHub 装的，内容跟着仓库走，不能在这里改。可以新建一个自己的知识库")
        val name = title.trim().take(80).let { if ('.' in it.substringAfterLast('/')) it else "$it.md" }
        val docs = store.loadLibrary().toMutableList()
        val i = docs.indexOfFirst { it.kb == kbId && (it.name == name || it.name == title.trim()) }
        val now = System.currentTimeMillis()
        val msg = when {
            content.isBlank() && i < 0 -> throw IllegalArgumentException("「${kb.name}」里没有「$title」")
            content.isBlank() -> { store.deleteLibraryText(docs[i].id); docs.removeAt(i); "已从「${kb.name}」删掉「${title.trim()}」" }
            i >= 0 -> {
                store.saveLibraryText(docs[i].id, content.trim())
                docs[i] = docs[i].copy(chars = content.trim().length); "已改好「${kb.name}」里的「${docs[i].name}」"
            }
            else -> {
                val d = LibraryDoc(name = name, chars = content.trim().length, kb = kbId)
                store.saveLibraryText(d.id, content.trim()); docs += d; "已存进「${kb.name}」：「$name」"
            }
        }
        store.saveLibrary(docs)
        store.saveKbs(store.loadKbs().map { if (it.id == kbId) it.copy(updatedAt = now) else it })
        msg
    }

    /** 新建一个空的本地知识库（自己往里导入文件）。 */
    fun create(store: Store, name: String, description: String = ""): KnowledgeBase = synchronized(lock) {
        val kb = KnowledgeBase(name = name.trim().take(40), description = description.trim().take(300))
        store.saveKbs(store.loadKbs() + kb)
        kb
    }

    /** 检索时要搜的文档：「我的资料」，加上开着的知识库里的。名字前面带上知识库名。 */
    fun searchable(store: Store): List<Pair<String, String>> {
        val kbs = store.loadKbs().associateBy { it.id }
        return store.loadLibrary().filter { it.kb.isEmpty() || kbs[it.kb]?.enabled == true }.map { d ->
            displayName(d, kbs) to store.libraryText(d.id)
        }
    }

    /** 所有资料的正文（含关掉的知识库，算向量、清旧向量用）。 */
    fun allTexts(store: Store): List<String> = store.loadLibrary().map { store.libraryText(it.id) }

    fun displayName(d: LibraryDoc, kbs: Map<String, KnowledgeBase>) = kbs[d.kb]?.let { "${it.name}/${d.name}" } ?: d.name

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("knowledge_bases", Context.MODE_PRIVATE)
    fun checkedAt(ctx: Context) = prefs(ctx).getLong("checkedAt", 0L)
    private fun markChecked(ctx: Context) = prefs(ctx).edit().putLong("checkedAt", System.currentTimeMillis()).apply()

    /** 到点了没有：从 GitHub 装的知识库一天自动查一次更新。 */
    fun syncDue(checkedAt: Long, kbs: List<KnowledgeBase>, now: Long = System.currentTimeMillis()) =
        kbs.any { it.source.isNotEmpty() } && now - checkedAt > 24 * 3600_000L
}
