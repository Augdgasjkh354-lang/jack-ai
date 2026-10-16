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

    internal fun index(): LibraryIndex = libraryIndex ?: LibraryIndex(
        store.loadLibrary().map { it.name to store.libraryText(it.id) },
    ).also { libraryIndex = it }

    fun addLibraryFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        state.update { it.copy(importing = true) }
        scope.launch {
            var added = 0
            for (uri in uris) {
                try {
                    val att = Attachments.importFile(app, uri)
                    val doc = LibraryDoc(name = att.name, chars = att.text.length)
                    withContext(ioSerial) {
                        store.saveLibraryText(doc.id, att.text)
                        store.saveLibrary(store.loadLibrary() + doc)
                    }
                    added++
                } catch (e: Exception) {
                    state.update { it.copy(toast = e.message ?: "导入失败") }
                }
            }
            libraryIndex = null
            state.update { it.copy(importing = false, library = store.loadLibrary(), toast = if (added > 0) "已加入资料库：$added 份" else it.toast) }
        }
    }

    fun deleteLibraryDoc(id: String) {
        scope.launch(ioSerial) {
            store.deleteLibraryText(id)
            val list = store.loadLibrary().filterNot { it.id == id }
            store.saveLibrary(list)
            libraryIndex = null
            state.update { it.copy(library = list) }
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
    fun resetIndex() { libraryIndex = null }

    fun reloadMemory() {
        scope.launch(Dispatchers.IO) {
            val m = store.loadMemories()
            val d = store.loadDreams()
            state.update { it.copy(memories = m, dreams = d) }
        }
    }

    internal fun editMemories(transform: (List<MemoryItem>) -> List<MemoryItem>) {
        scope.launch(ioSerial) {
            val list = transform(store.loadMemories())
            store.saveMemories(list)
            state.update { it.copy(memories = list) }
        }
    }

    fun addMemory(text: String) = editMemories { it + MemoryItem(text = text.trim()) }
    fun updateMemory(id: String, text: String) = editMemories { l -> l.map { if (it.id == id) it.copy(text = text.trim()) else it } }
    fun deleteMemory(id: String) = editMemories { l -> l.filterNot { it.id == id } }
    fun clearMemories() = editMemories { emptyList() }

    fun markDreamsSeen() {
        if (state.value.dreams.none { !it.seen }) return
        scope.launch(ioSerial) {
            val list = store.loadDreams().map { it.copy(seen = true) }
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
                if (entry == null) state.update { it.copy(toast = "上次做梦之后还没有新的聊天") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(toast = "做梦失败：${e.message ?: "网络问题"}") }
            } finally {
                state.update { it.copy(dreaming = false) }
                reloadMemory()
            }
        }
    }
}
