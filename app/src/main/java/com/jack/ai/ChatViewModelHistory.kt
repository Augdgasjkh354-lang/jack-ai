package com.jack.ai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.ai.api.ApiException
import com.jack.ai.chat.Agent
import com.jack.ai.chat.ContextSlim
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.chat.StopReport
import com.jack.ai.tools.ToolHost
import com.jack.ai.tools.ToolSession
import com.jack.ai.tools.ToolUi
import com.jack.ai.tools.search
import com.jack.ai.features.AppCore
import com.jack.ai.features.BackupFeature
import com.jack.ai.features.CompressFeature
import com.jack.ai.features.FilesFeature
import com.jack.ai.features.KnowledgeFeature
import com.jack.ai.features.ProjectsFeature
import com.jack.ai.features.RoutinesFeature
import com.jack.ai.features.SettingsFeature
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.api.TavilyClient
import com.jack.ai.data.Attachment
import com.jack.ai.data.globalAllowed
import com.jack.ai.data.workMemoryAllowed
import com.jack.ai.data.ChatExport
import com.jack.ai.data.Workspace
import com.jack.ai.data.Flashcard
import com.jack.ai.data.LibraryDoc
import com.jack.ai.data.Routine
import com.jack.ai.data.Routines
import com.jack.ai.data.UsageDay
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.DreamEntry
import com.jack.ai.data.Dreamer
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.activeSkills
import com.jack.ai.data.readyMails
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Project
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.MODEL_FLASH
import com.jack.ai.data.endpoint
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import com.jack.ai.api.StreamEvent
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import com.jack.ai.ChatViewModel.Deleted

// 改写对话历史：重新回答、改消息重发、切版本、删消息和撤销、接着写（从 ChatViewModel.kt 拆出来，是 ChatViewModel 的扩展函数）。

/**
 * 重新生成最后一条回答。旧回答像样的话留成一个版本（[Branches]），能切回去看；
 * 对话文件夹先退回这一轮开始前的样子（[Checkpoints]），旧版本做完时的文件也存一份，切回去时跟着回来。
 */
internal fun ChatViewModel.regenerate() {
    val conv = _state.value.current ?: return
    if (busy(conv.id) || tooMany(conv.id)) return
    val msgs = conv.messages.dropLastWhile { it.role == "assistant" }
    if (msgs.isEmpty()) return
    if (!preparing.add(conv.id)) return
    viewModelScope.launch {
        try {
            val user = msgs.last().takeIf { it.role == "user" }
            val note = user?.let { rewindFiles(conv, it.id, com.jack.ai.data.Checkpoints.startName(it.id)) }
            val cur = _state.value.conversations.firstOrNull { it.id == conv.id } ?: return@launch
            val at = msgs.size - 1
            if (cur.messages.getOrNull(at)?.id != msgs.last().id) return@launch
            val answers = cur.messages.drop(at + 1)
            // 从哪条起变了：留成版本时这一轮的用户消息也换了新的
            var cut = at + 1
            val next = if (user != null && com.jack.ai.data.Branches.worthKeeping(answers)) {
                val fork = com.jack.ai.data.Branches.fork(cur.messages, at,
                    user.copy(id = com.jack.ai.data.newId(), createdAt = System.currentTimeMillis()), keepOld = true)
                files.cleanup(fork.dropped.flatten(), keep = fork.messages)
                cut = at
                fork.messages
            } else {
                files.cleanup(answers, keep = msgs)
                cur.messages.take(at + 1)
            }
            val boundary = cur.summaryUntilId?.let { id -> cur.messages.indexOfFirst { it.id == id } } ?: -1
            updateConv(conv.id) {
                it.copy(
                    messages = next,
                    summary = if (boundary >= cut) "" else it.summary,
                    summaryUntilId = if (boundary >= cut) null else it.summaryUntilId,
                )
            }
            note?.let { t -> _state.update { it.copy(toast = t) } }
            startReply(conv.id)
        } finally {
            preparing.remove(conv.id)
        }
    }
}

/**
 * 换下从 [leavingId] 那条起的这段对话：先存下对话文件夹现在的样子（这段做完时的文件），再把文件夹退回存档 [restoreName]。
 * 返回要提示的一句话；没有存档、文件没变时返回 null。
 */
internal suspend fun ChatViewModel.rewindFiles(conv: Conversation, leavingId: String, restoreName: String, done: String = "对话文件夹已退回到这条消息之前"): String? =
    withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val key = Workspace.key(conv)
        val ws = Workspace.dir(app, key)
        val saved = com.jack.ai.data.Checkpoints.store(app, key)
        if (!com.jack.ai.data.Checkpoints.has(saved, restoreName)) return@withContext null
        // 项目里的对话共用一个文件夹：退回会把别的对话（或用户自己）后来放进去、改过的文件一起删掉、改回去，所以不退
        if (conv.projectId != null) return@withContext null
        if (!com.jack.ai.data.Checkpoints.save(ws, saved, com.jack.ai.data.Checkpoints.endName(leavingId)))
            return@withContext "对话文件夹太大，这次文件没有退回去"
        val r = com.jack.ai.data.Checkpoints.restore(ws, saved, restoreName) ?: return@withContext "对话文件夹太大，这次文件没有退回去"
        if (r.total == 0) null else "$done（${r.total} 个文件）"
    }

/**
 * 切到某处的第 [k] 个版本（[messageId] 是那处眼前这段的第一条）。对话文件夹也换成那个版本做完时的样子（有存档的话）。
 */
internal fun ChatViewModel.switchBranch(messageId: String, k: Int) {
    val conv = _state.value.current ?: return
    if (busy(conv.id)) { _state.update { it.copy(toast = "正在回答，等写完再切换版本") }; return }
    val at = conv.messages.indexOfFirst { it.id == messageId }
    if (at < 0) return
    val target = com.jack.ai.data.Branches.versions(conv.messages, at).getOrNull(k)?.firstOrNull() ?: return
    if (!preparing.add(conv.id)) return
    viewModelScope.launch {
        try {
            val note = rewindFiles(conv, messageId, com.jack.ai.data.Checkpoints.endName(target.id), done = "对话文件夹也换成了这个版本的")
            val cur = _state.value.conversations.firstOrNull { it.id == conv.id } ?: return@launch
            val i = cur.messages.indexOfFirst { it.id == messageId }
            val next = com.jack.ai.data.Branches.switch(cur.messages, i, k) ?: return@launch
            val boundary = cur.summaryUntilId?.let { id -> cur.messages.indexOfFirst { it.id == id } } ?: -1
            updateConv(conv.id) {
                it.copy(
                    messages = next,
                    summary = if (boundary >= i) "" else it.summary,
                    summaryUntilId = if (boundary >= i) null else it.summaryUntilId,
                )
            }
            persist()
            note?.let { t -> _state.update { it.copy(toast = t) } }
        } finally {
            preparing.remove(conv.id)
        }
    }
}

/** 删除某条消息（以及它之后的所有消息）。 */
/**
 * 回答写到长度上限被截断时，从断的地方接着写，接在同一条消息后面。
 * DeepSeek 用“对话前缀续写”（beta 接口，模型从已有内容直接往下写）；其他服务让它“接着写，不要重复”。
 */
internal fun ChatViewModel.continueWriting() {
    val conv = _state.value.current ?: return
    if (busy(conv.id) || tooMany(conv.id)) return
    val m = conv.messages.lastOrNull()?.takeIf { it.role == "assistant" && it.truncated && it.content.isNotBlank() } ?: return
    val settings = _state.value.settings
    val ep0 = settings.endpoint(m.model.ifBlank { settings.model })?.takeIf { it.apiKey.isNotBlank() }
        ?: settings.endpoint()?.takeIf { it.apiKey.isNotBlank() }
    if (ep0 == null) { _state.update { it.copy(toast = "这个模型用不了了，请先选一个能用的模型") }; return }
    val convId = conv.id
    patchMessage(convId, m.id) { it.copy(truncated = false) }
    launchReply(convId) { done ->
        WorkService.start(getApplication(), convId, conv.title, false)
        try {
            val (chatMemory, projectMemory) = knowledge.memoryTexts(conv)
            val input = PromptBuilder.Input(settings, conv, _state.value.project(conv.projectId),
                if (settings.memoryEnabled) store.loadMemories() else emptyList(), settings.activeSkills(),
                vision = false, sendReasoning = false, chatMemory = chatMemory, projectMemory = projectMemory)
            val hist = withContext(Dispatchers.Default) { PromptBuilder.history(input, null, flat = true) }
            val ep = if (ep0.deepseek) {
                // 最后一条是要接着写的回答，标成前缀
                val i = hist.indexOfLast { it["role"]?.let { r -> (r as? kotlinx.serialization.json.JsonPrimitive)?.content } == "assistant" }
                if (i >= 0) hist[i] = buildJsonObject { put("role", "assistant"); put("content", m.content); put("prefix", true) }
                while (hist.size > i + 1 && i >= 0) hist.removeAt(hist.lastIndex)
                ep0.copy(baseUrl = ep0.baseUrl.trimEnd('/').removeSuffix("/v1") + "/beta")
            } else {
                hist += msg("user", "你上一条回答写到长度上限被截断了。从中断的地方直接接着写，不要重复已经写过的内容，不要加开场白。")
                ep0
            }
            var cut = false
            client.stream(ep, hist, ThinkLevel.OFF, settings.temperature, null).collect { ev ->
                when (ev) {
                    is StreamEvent.Text -> if (ev.content.isNotEmpty()) patchMessage(convId, m.id) { it.copy(content = it.content + ev.content) }
                    is StreamEvent.Usage -> core.recordSpend(convId, ev.endpoint ?: ep0, ev.promptTokens, ev.cacheHit, ev.completionTokens, ev.prefix, exact = ev.exactCost)
                    is StreamEvent.Finish -> cut = ev.reason == "length"
                    else -> {}
                }
            }
            if (cut) patchMessage(convId, m.id) { it.copy(truncated = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            patchMessage(convId, m.id) { it.copy(truncated = true) }
            _state.update { it.copy(toast = "接着写失败：${e.message ?: "网络问题"}") }
        } finally {
            done()
            persist()
            WorkService.finish(getApplication(), convId, conv.title, "接着写完了", false)
        }
    }
}

/** 删除某条消息以及它之后的所有消息，返回删了几条（0 是没删）。[UNDO_MS] 内可以 [undoDelete]。 */
internal fun ChatViewModel.deleteFrom(messageId: String): Int {
    val conv = _state.value.current ?: return 0
    if (busy(conv.id)) stop(conv.id)
    val idx = conv.messages.indexOfFirst { it.id == messageId }
    if (idx < 0) return 0
    val removed = conv.messages.drop(idx)
    // 上一次删的不等了，现在就清掉
    deleted?.let { d -> d.cleanup?.cancel(); files.cleanup(d.removed) }
    deleted = Deleted(conv.id, conv.messages.getOrNull(idx - 1)?.id, removed, conv.summary, conv.summaryUntilId).also { d ->
        d.cleanup = viewModelScope.launch {
            kotlinx.coroutines.delay(UNDO_MS)
            if (deleted === d) deleted = null
            files.cleanup(d.removed)
        }
    }
    val boundary = conv.summaryUntilId?.let { id -> conv.messages.indexOfFirst { it.id == id } } ?: -1
    updateConv(conv.id) {
        it.copy(
            messages = it.messages.take(idx),
            summary = if (boundary >= idx) "" else it.summary,
            summaryUntilId = if (boundary >= idx) null else it.summaryUntilId,
        )
    }
    persist()
    return removed.size
}

/** 把刚删的消息放回去。对话在那之后又有了新消息、或者正在回答，就放不回去了。 */
internal fun ChatViewModel.undoDelete(): Boolean {
    val d = deleted ?: return false
    val cur = _state.value.conversations.firstOrNull { it.id == d.convId }
    if (cur == null || busy(cur.id) || cur.messages.lastOrNull()?.id != d.keptLastId) return false
    deleted = null
    d.cleanup?.cancel()
    updateConv(d.convId) { it.copy(messages = it.messages + d.removed, summary = d.summary, summaryUntilId = d.summaryUntilId) }
    persist()
    return true
}

/** 改写一条自己发的消息：它之后的对话全部丢掉，用新内容重新回答。 */
internal fun ChatViewModel.editMessage(messageId: String, text: String) {
    val conv = _state.value.current ?: return
    val idx = conv.messages.indexOfFirst { it.id == messageId }
    if (idx < 0 || conv.messages[idx].role != "user") return
    val old = conv.messages[idx]
    val trimmed = text.trim()
    if (trimmed.isEmpty() && old.attachments.isEmpty()) return
    if (tooMany(conv.id)) return
    // 这个对话正在回答时先停下，等旧的收尾（它会撤掉「正在回答」、保存）再开始；别的对话的回答不动
    val running = jobs[conv.id]
    stop(conv.id)
    viewModelScope.launch {
        running?.join()
        val cur = _state.value.conversations.firstOrNull { it.id == conv.id } ?: return@launch
        val i = cur.messages.indexOfFirst { it.id == messageId }
        if (i < 0) return@launch
        // 文件夹先退回这条消息发出前的样子；旧回答像样的话留成一个版本，能切回去
        val note = rewindFiles(cur, old.id, com.jack.ai.data.Checkpoints.startName(old.id))
        val c0 = _state.value.conversations.firstOrNull { it.id == conv.id } ?: return@launch
        if (c0.messages.getOrNull(i)?.id != messageId) return@launch
        val answers = c0.messages.drop(i + 1)
        val keepOld = com.jack.ai.data.Branches.worthKeeping(answers)
        val fork = com.jack.ai.data.Branches.fork(c0.messages, i,
            old.copy(id = com.jack.ai.data.newId(), content = trimmed, createdAt = System.currentTimeMillis(), contextPrefix = null),
            keepOld = keepOld)
        // 扔掉的版本、没留成版本的旧回答清理掉；新消息还用着的附件留着
        files.cleanup(fork.dropped.flatten() + (if (keepOld) emptyList() else answers), keep = fork.messages)
        note?.let { t -> _state.update { it.copy(toast = t) } }
        val boundary = c0.summaryUntilId?.let { id -> c0.messages.indexOfFirst { it.id == id } } ?: -1
        updateConv(conv.id) { c ->
            c.copy(
                title = if (i == 0) trimmed.ifEmpty { c.title }.take(20).replace('\n', ' ') else c.title,
                messages = fork.messages,
                summary = if (boundary >= i) "" else c.summary,
                summaryUntilId = if (boundary >= i) null else c.summaryUntilId,
            )
        }
        startReply(conv.id)
    }
}
