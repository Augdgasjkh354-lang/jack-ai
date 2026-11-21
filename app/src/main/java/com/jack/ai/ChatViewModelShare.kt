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

// 分享进来、问这一屏、录音纪要：开新对话把内容带进去（从 ChatViewModel.kt 拆出来，是 ChatViewModel 的扩展函数）。

internal fun ChatViewModel.receiveText(text: String) = _state.update { it.copy(incomingText = text) }

internal fun ChatViewModel.consumeIncomingText() = _state.update { it.copy(incomingText = null) }

internal fun ChatViewModel.requestVoice() = _state.update { it.copy(voiceRequest = true) }

internal fun ChatViewModel.consumeVoiceRequest() = _state.update { it.copy(voiceRequest = false) }

internal fun ChatViewModel.requestRecord() = _state.update { it.copy(recordRequest = true) }

internal fun ChatViewModel.consumeRecordRequest() = _state.update { it.copy(recordRequest = false) }

/** 录音纪要录完：录音挂在当前对话上发出去，Kavi 转文字、出纪要。这个对话正在回答的话等它答完（别的对话不用等）。 */
internal fun ChatViewModel.recordingDone(file: java.io.File, seconds: Long) {
    val here = _state.value.currentId
    viewModelScope.launch {
        here?.let { jobs[it] }?.join()
        val att = Attachment(kind = "file", name = file.name, path = file.absolutePath)
        _state.update { it.copy(pending = it.pending + att) }
        send(com.jack.ai.data.Minutes.recording(file.name, seconds))
    }
}

/** 分享菜单的「用 Kavi 总结」：链接或视频、录音文件，开个新对话直接发出去（正在进行的回答不停，各答各的）。 */
internal fun ChatViewModel.summarizeShare(text: String?, uris: List<Uri>) {
    viewModelScope.launch {
        newChat()
        val app = getApplication<Application>()
        val atts = uris.mapNotNull { uri ->
            try {
                com.jack.ai.data.Attachments.importFile(app, uri, keepOriginal = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.toast("文件读不了：${e.message ?: "读取失败"}")
                null
            }
        }
        if (atts.isEmpty() && text.isNullOrBlank()) return@launch
        _state.update { it.copy(pending = atts) }
        send(com.jack.ai.data.Minutes.summary(text, atts.isNotEmpty()))
    }
}

internal fun ChatViewModel.requestCamera() = _state.update { it.copy(cameraRequest = true) }

internal fun ChatViewModel.consumeCameraRequest() = _state.update { it.copy(cameraRequest = false) }

/** 别的 App 分享过来的内容：开一个新对话，文字放进输入框，图片和文件作为附件。 */
/** 问这一屏：新对话带上屏幕截图和屏幕上的字，用户接着问。 */
internal fun ChatViewModel.askScreen(app: String, textPath: String?, shotPath: String?) {
    newChat()
    shotPath?.let { addImages(listOf(Uri.fromFile(java.io.File(it)))) }
    textPath?.let { addFiles(listOf(Uri.fromFile(java.io.File(it)))) }
    core.toast("带上了「$app」这一屏，问吧")
}

internal fun ChatViewModel.handleShare(text: String?, uris: List<Uri>, mime: String?) {
    // 只是填进新对话的输入框、不发出去：正在写的回答不用停，让它在后台写完
    newChat()
    if (!text.isNullOrBlank()) receiveText(text)
    if (uris.isNotEmpty()) {
        if (mime?.startsWith("image/") == true) addImages(uris) else addFiles(uris)
    }
}

/**
 * 分享菜单的「用 Kavi 下载」：开个新对话直接发出去，Kavi 认出链接，把视频或文件存到手机「下载/Kavi」。
 * 分享的文字常带着一大段口令（「复制打开抖音……」），原样交给它，它自己找链接。
 */
internal fun ChatViewModel.downloadShare(text: String) {
    viewModelScope.launch {
        // 新开对话发出去，正在进行的回答不停，各答各的
        newChat()
        send(com.jack.ai.data.Downloads.shareRequest(text))
    }
}

/**
 * 万能收件箱：分享进来的东西直接发到「收件箱」对话，AI 自动判断类型、提炼重点、归档。
 * 和「问 Kavi」不同，不等用户再按发送。
 */
internal fun ChatViewModel.inboxShare(text: String?, uris: List<Uri>, mime: String?) {
    viewModelScope.launch {
        val app = getApplication<Application>()
        val atts = uris.mapNotNull { uri ->
            try {
                val image = mime?.startsWith("image/") == true ||
                    app.contentResolver.getType(uri)?.startsWith("image/") == true
                if (image) com.jack.ai.data.Attachments.importImage(app, uri)
                else com.jack.ai.data.Attachments.importFile(app, uri, keepOriginal = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.toast("有个文件读不了：${e.message ?: "读取失败"}")
                null
            }
        }
        if (text.isNullOrBlank() && atts.isEmpty()) return@launch
        val existing = _state.value.conversations.firstOrNull { it.id == com.jack.ai.data.INBOX_ID }
        if (existing == null) {
            val c = Conversation(id = com.jack.ai.data.INBOX_ID, title = "📥 收件箱", systemPrompt = com.jack.ai.data.INBOX_PROMPT, presetName = "收件箱")
            _state.update { it.copy(conversations = listOf(c) + it.conversations) }
        } else if (existing.systemPrompt != com.jack.ai.data.INBOX_PROMPT) {
            // 收件箱的说明更新过，跟着换成最新的
            updateConvQuiet(existing.id) { it.copy(systemPrompt = com.jack.ai.data.INBOX_PROMPT) }
        }
        select(com.jack.ai.data.INBOX_ID)
        // 收件箱自己正在回答的先收尾，再发（别的对话的回答不用等）
        jobs[com.jack.ai.data.INBOX_ID]?.join()
        _state.update { it.copy(pending = atts) }
        send(text.orEmpty())
    }
}
