package com.jack.ai.features

import android.app.Application
import android.net.Uri
import com.jack.ai.AskWidget
import com.jack.ai.UiState
import com.jack.ai.api.ApiException
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 资料库、复习卡片、记忆和梦。 */
class KnowledgeFeature(core: AppCore) : Feature(core) {
    @Volatile private var libraryIndex: LibraryIndex? = null

    internal fun index(): LibraryIndex = libraryIndex ?: LibraryIndex(KnowledgeBases.searchable(store)).also { libraryIndex = it }

    /** 资料库或知识库变了：重建索引、刷新界面，后台把新段落的向量补上（顺便清掉删了的）。 */
    fun libraryChanged() {
        libraryIndex = null
        state.update { it.copy(library = store.loadLibrary(), kbs = store.loadKbs()) }
        kickVectors()
    }

    private fun kickVectors() =
        LibraryVectors.kick(app, state.value.settings, LibraryVectors.LIBRARY, { KnowledgeBases.allTexts(store).flatMap { LibraryIndex.split(it) } })

    /** 开 App 时：补算向量；从 GitHub 装的知识库一天查一次更新（没变化不打扰）。 */
    fun startup() {
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(8_000)
            kickVectors()
            // 项目资料的向量：删了的项目、改了的文件，旧向量清掉
            runCatching {
                val live = store.loadProjects().flatMap { p -> p.files.filter { it.text.length > PromptBuilder.PROJECT_INLINE_CHARS } }
                    .flatMap { LibraryIndex.split(it.text) }.mapTo(HashSet()) { LibraryVectors.key(it) }
                LibraryVectors.prune(app, LibraryVectors.PROJECTS, live)
            }
            if (!KnowledgeBases.syncDue(KnowledgeBases.checkedAt(app), store.loadKbs())) return@launch
            val (results, _) = runCatching { KnowledgeBases.sync(app, store, state.value.settings.githubToken) }.getOrNull() ?: return@launch
            val changed = results.filter { it.changed }
            if (changed.isNotEmpty()) {
                withContext(Dispatchers.Main) { libraryChanged() }
                state.update { it.copy(toast = "知识库有更新：" + changed.joinToString("；") { r -> r.line() }) }
            }
        }
    }

    fun addLibraryFiles(uris: List<Uri>, kb: String = "") {
        if (uris.isEmpty()) return
        state.update { it.copy(importing = true) }
        scope.launch {
            var added = 0
            for (uri in uris) {
                try {
                    val att = Attachments.importFile(app, uri)
                    val doc = LibraryDoc(name = att.name, chars = att.text.length, kb = kb)
                    withContext(ioSerial) {
                        store.saveLibraryText(doc.id, att.text)
                        store.saveLibrary(store.loadLibrary() + doc)
                    }
                    added++
                } catch (e: Exception) {
                    state.update { it.copy(toast = e.message ?: "导入失败") }
                }
            }
            libraryChanged()
            state.update { it.copy(importing = false, toast = if (added > 0) "已加入资料库：$added 份" else it.toast) }
        }
    }

    /** 收件箱存进来的笔记：当场写进资料库（调用方马上要读回来核实）。 */
    fun addNote(title: String, content: String) {
        val doc = LibraryDoc(name = "$title.md", chars = content.length)
        store.saveLibraryText(doc.id, content)
        store.saveLibrary(store.loadLibrary() + doc)
        libraryChanged()
    }

    /** 别处（浏览器「存进资料库」）往资料库里加了东西：回到 App 时重新读一遍。 */
    fun reloadLibrary() {
        scope.launch(ioSerial) {
            val list = store.loadLibrary()
            if (list.map { it.id } != state.value.library.map { it.id }) withContext(Dispatchers.Main) { libraryChanged() }
        }
    }

    fun deleteLibraryDoc(id: String) {
        scope.launch(ioSerial) {
            store.deleteLibraryText(id)
            store.saveLibrary(store.loadLibrary().filterNot { it.id == id })
            withContext(Dispatchers.Main) { libraryChanged() }
        }
    }

    fun coverage(): Pair<Int, Int>? = LibraryVectors.coverage(app, state.value.settings, LibraryVectors.LIBRARY,
        KnowledgeBases.searchable(store).flatMap { LibraryIndex.split(it.second) })

    // ---------- 知识库 ----------

    private fun busy(what: String?) = state.update { it.copy(kbBusy = what) }

    /** 「下载知识库」：列出一个 GitHub 项目里的知识库。 */
    fun loadCatalog(input: String) {
        val repo = GitHub.repoOf(input)
        if (repo == null) { state.update { it.copy(toast = "看不懂这个地址，填「作者/项目名」或 GitHub 项目网址") }; return }
        val ref = Regex("""github\.com/[\w.-]+/[\w.-]+/tree/([^/?#]+)""").find(input)?.groupValues?.get(1).orEmpty()
        busy("正在读取 $repo…")
        state.update { it.copy(kbCatalog = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val c = KnowledgeBases.catalog(store, state.value.settings.githubToken, repo, ref)
                state.update { it.copy(kbCatalog = c) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.update { it.copy(toast = "读不到 $repo：" + (e.message ?: "网络问题")) }
            } finally { busy(null) }
        }
    }

    fun closeCatalog() = state.update { it.copy(kbCatalog = null) }

    /** 装目录里的一个知识库（dir 是它在仓库里的文件夹），装过的就是更新。 */
    fun installKb(dir: String) {
        val (l, entries) = state.value.kbCatalog ?: return
        val e = entries.firstOrNull { it.found.dir == dir } ?: return
        if (state.value.kbBusy != null) return
        busy("正在下载「${e.name}」（${e.found.files.size} 份）…")
        scope.launch(Dispatchers.IO) {
            try {
                val r = KnowledgeBases.install(app, store, state.value.settings.githubToken, l, e.found, e.name, e.description)
                withContext(Dispatchers.Main) { libraryChanged() }
                state.update { st ->
                    st.copy(toast = "已装好：" + r.line(),
                        kbCatalog = st.kbCatalog?.let { (cl, list) -> cl to list.map { x -> if (x.found.dir == dir) x.copy(installed = r.kb) else x } })
                }
            } catch (e: CancellationException) { throw e } catch (ex: Exception) {
                state.update { it.copy(toast = "没装成：" + (ex.message ?: "网络问题")) }
            } finally { busy(null) }
        }
    }

    fun syncKbs() {
        if (state.value.kbBusy != null) return
        busy("正在检查知识库更新…")
        scope.launch(Dispatchers.IO) {
            try {
                val (results, failed) = KnowledgeBases.sync(app, store, state.value.settings.githubToken)
                withContext(Dispatchers.Main) { libraryChanged() }
                val msg = when {
                    results.isEmpty() && failed.isEmpty() -> "还没有从 GitHub 装的知识库"
                    results.none { it.changed } && failed.isEmpty() -> "知识库都是最新的"
                    else -> (results.filter { it.changed }.map { it.line() } + failed).joinToString("；")
                }
                state.update { it.copy(toast = msg) }
            } catch (e: CancellationException) { throw e } catch (ex: Exception) {
                state.update { it.copy(toast = "检查更新失败：" + (ex.message ?: "网络问题")) }
            } finally { busy(null) }
        }
    }

    fun removeKb(id: String) {
        scope.launch(ioSerial) {
            KnowledgeBases.remove(store, id)
            withContext(Dispatchers.Main) { libraryChanged() }
        }
    }

    fun toggleKb(id: String, on: Boolean) {
        scope.launch(ioSerial) {
            KnowledgeBases.setEnabled(store, id, on)
            withContext(Dispatchers.Main) { libraryChanged() }
        }
    }

    fun createKb(name: String) {
        if (name.isBlank()) return
        scope.launch(ioSerial) {
            KnowledgeBases.create(store, name)
            withContext(Dispatchers.Main) { libraryChanged() }
        }
    }

    internal fun editCards(transform: (List<Flashcard>) -> List<Flashcard>) {
        scope.launch(ioSerial) {
            val list = transform(store.loadCards())
            store.saveCards(list)
            state.update { it.copy(cards = list) }
            AskWidget.refresh(app)
        }
    }

    fun reviewCard(id: String, grade: Int) = editCards { l -> l.map { if (it.id == id) Recall.review(it, grade) else it } }
    fun deleteCard(id: String) = editCards { l -> l.filterNot { it.id == id } }
    fun deleteDeck(deck: String) = editCards { l -> l.filterNot { it.deck == deck } }


    /** 资料库变了（导入、删除、恢复备份）之后要重建索引。 */
    fun resetIndex() { libraryIndex = null; state.update { it.copy(kbs = store.loadKbs()) } }

    fun reloadMemory() {
        scope.launch(Dispatchers.IO) {
            val m = store.loadMemories()
            val d = store.loadDreams()
            state.update { it.copy(memories = m, dreams = d) }
        }
    }

    internal fun editMemories(transform: (List<MemoryItem>) -> List<MemoryItem>) {
        scope.launch(ioSerial) {
            val list = store.updateMemories(transform)
            state.update { it.copy(memories = list) }
        }
    }

    fun addMemory(text: String) = editMemories { it + MemoryItem(text = text.trim()) }
    fun updateMemory(id: String, text: String) = editMemories { l -> l.map { if (it.id == id) it.copy(text = text.trim()) else it } }
    fun deleteMemory(id: String) = editMemories { l -> l.filterNot { it.id == id } }
    fun clearMemories() = editMemories { emptyList() }

    /** 重置记忆（见 [com.jack.ai.data.MemoryReset]）：清完重新读，界面跟着变空。 */
    fun resetMemory(withChatFiles: Boolean) {
        scope.launch(ioSerial) {
            com.jack.ai.data.MemoryReset.reset(app, store, withChatFiles)
            state.update { it.copy(memories = store.loadMemories(), dreams = store.loadDreams(), toast = "记忆已重置。${com.jack.ai.data.MemoryReset.KEEP_DAYS} 天内可以在这里恢复") }
        }
    }

    /** 恢复重置前的那份。 */
    fun restoreMemory() {
        scope.launch(ioSerial) {
            val b = com.jack.ai.data.MemoryReset.latest(app)
            if (b == null) { state.update { it.copy(toast = "没有能恢复的记忆了（超过 ${com.jack.ai.data.MemoryReset.KEEP_DAYS} 天会自动删掉）") }; return@launch }
            com.jack.ai.data.MemoryReset.restore(app, store, b)
            state.update { it.copy(memories = store.loadMemories(), dreams = store.loadDreams(), toast = "已恢复重置前的记忆") }
        }
    }

    /** 深处的一条拿回常记。 */
    fun promoteMemory(id: String) = editMemories { l ->
        val deep = MemoryOps.deep(app)
        val m = deep.firstOrNull { it.id == id } ?: return@editMemories l
        MemoryOps.saveDeep(app, deep.filterNot { it.id == id })
        l + m.copy(lastUsedAt = System.currentTimeMillis())
    }

    /** 常记的一条放到深处（还记得，只是不每次都带着）。 */
    fun demoteMemory(id: String) = editMemories { l ->
        val m = l.firstOrNull { it.id == id } ?: return@editMemories l
        MemoryOps.saveDeep(app, MemoryOps.deep(app) + m)
        l.filterNot { it.id == id }
    }


    fun markDreamsSeen() {
        if (state.value.dreams.none { !it.seen }) return
        scope.launch(ioSerial) {
            val list = store.loadDreams().map { it.copy(seen = true) }
            store.saveDreams(list)
            state.update { it.copy(dreams = list) }
        }
    }

    fun deleteDream(id: String) = changeDreams { list -> list.filterNot { it.id == id } }

    private fun changeDreams(transform: (List<com.jack.ai.data.DreamEntry>) -> List<com.jack.ai.data.DreamEntry>) {
        scope.launch(ioSerial) {
            val list = transform(store.loadDreams())
            store.saveDreams(list)
            state.update { it.copy(dreams = list) }
        }
    }

    fun dreamNow() {
        if (state.value.dreaming) return
        state.update { it.copy(dreaming = true) }
        scope.launch {
            try {
                val entry = Dreamer.dream(app, force = true)
                if (entry == null) state.update { it.copy(toast = "上次整理之后还没有新的聊天") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(toast = "整理失败：${e.message ?: "网络问题"}") }
            } finally {
                state.update { it.copy(dreaming = false) }
                reloadMemory()
            }
        }
    }

    // ---------- 对话记忆、项目记忆 ----------

    /** 回答时带上的两份记忆文件内容：(本对话记忆, 项目记忆)。 */
    fun memoryTexts(conv: Conversation): Pair<String, String> =
        ChatMemory.read(ChatMemory.chatFile(app, conv)) to
            (conv.projectId?.let { ChatMemory.read(ChatMemory.projectFile(app, it)) } ?: "")

    fun workMemoryText(): String = ChatMemory.read(ChatMemory.workFile(app))

    fun workMemory(): List<String> = ChatMemory.items(workMemoryText())

    fun setWorkMemory(items: List<String>) = ChatMemory.update(ChatMemory.workFile(app)) { items }

    fun chatMemory(conv: Conversation): List<String> = ChatMemory.items(ChatMemory.read(ChatMemory.chatFile(app, conv)))

    fun setChatMemory(conv: Conversation, items: List<String>) = ChatMemory.update(ChatMemory.chatFile(app, conv)) { items }

    fun projectMemory(projectId: String): List<String> = ChatMemory.items(ChatMemory.read(ChatMemory.projectFile(app, projectId)))

    fun setProjectMemory(projectId: String, items: List<String>) = ChatMemory.update(ChatMemory.projectFile(app, projectId)) { items }

    /** 把一条本对话记忆挪到全局记忆。 */
    fun moveToGlobal(conv: Conversation, item: String) {
        setChatMemory(conv, chatMemory(conv) - item)
        addMemory(item)
    }

    fun deleteChatMemory(conv: Conversation) {
        scope.launch(Dispatchers.IO) { runCatching { ChatMemory.chatFile(app, conv).delete() } }
    }
}
