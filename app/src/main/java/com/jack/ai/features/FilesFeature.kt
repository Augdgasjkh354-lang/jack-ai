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

/** 附件（待发的图片和文件）和对话文件夹。 */
class FilesFeature(core: AppCore) : Feature(core) {
    fun addImages(uris: List<Uri>) = importAll(uris) { Attachments.importImage(app, it) }
    fun addFiles(uris: List<Uri>) = importAll(uris) { Attachments.importFile(app, it, keepOriginal = true) }

    internal fun importAll(uris: List<Uri>, import: suspend (Uri) -> Attachment) {
        if (uris.isEmpty()) return
        state.update { it.copy(importing = true) }
        scope.launch {
            for (uri in uris) {
                try {
                    val att = import(uri)
                    state.update { it.copy(pending = it.pending + att) }
                } catch (e: Exception) {
                    state.update { it.copy(toast = e.message ?: "读取失败") }
                }
            }
            state.update { it.copy(importing = false) }
        }
    }

    fun removePending(id: String) {
        val att = state.value.pending.firstOrNull { it.id == id }
        state.update { it.copy(pending = it.pending.filterNot { a -> a.id == id }) }
        att?.let { deleteImages(listOf(it)); deleteStaged(listOf(it)) }
    }

    internal fun deleteStaged(list: List<Attachment>) {
        val dirs = list.filter { it.kind == "file" && it.path.contains("/staging/") }.mapNotNull { File(it.path).parentFile }
        if (dirs.isNotEmpty()) scope.launch(Dispatchers.IO) { dirs.forEach { runCatching { it.deleteRecursively() } } }
    }

    /** 发送时把附件放进对话文件夹：文件挪过去，图片复制一份。 */
    internal fun moveToWorkspace(conv: Conversation, atts: List<Attachment>): List<Attachment> {
        val app = app
        val dir = Workspace.dir(app, Workspace.key(conv))
        return atts.map { a ->
            runCatching {
                when {
                    a.kind == "file" && a.path.contains("/staging/") -> {
                        val src = File(a.path)
                        val dst = Workspace.uniqueFile(dir, a.name)
                        if (!src.renameTo(dst)) { src.copyTo(dst); src.delete() }
                        src.parentFile?.delete()
                        a.copy(path = dst.absolutePath)
                    }
                    a.kind == "image" && a.path.isNotEmpty() -> {
                        val name = a.name.substringBeforeLast('.').ifBlank { "图片" } + ".jpg"
                        File(a.path).copyTo(Workspace.uniqueFile(dir, name))
                        a
                    }
                    else -> a
                }
            }.getOrDefault(a)
        }
    }

    /** 删除消息时，顺带删掉它们的图片和生成的文件。 */
    internal fun cleanup(msgs: List<ChatMessage>) {
        deleteImages(msgs.flatMap { it.attachments })
        val app = app
        // 对话文件夹里的文件跟着对话走，不随单条消息删除
        val paths = msgs.flatMap { m -> m.files.map { it.path } }.filterNot { Workspace.isInside(app, it) }
        if (paths.isNotEmpty()) scope.launch(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
    }

    internal fun deleteWorkspace(key: String) {
        val dir = Workspace.dir(app, key)
        scope.launch(Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
    }

    fun workspaceFiles(conv: Conversation): List<File> = Workspace.files(Workspace.dir(app, Workspace.key(conv)))

    fun addWorkspaceFiles(conv: Conversation, uris: List<Uri>, onDone: () -> Unit) {
        val app = app
        scope.launch {
            withContext(Dispatchers.IO) {
                val dir = Workspace.dir(app, Workspace.key(conv))
                uris.forEach { uri ->
                    runCatching {
                        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "文件"
                        val f = Workspace.uniqueFile(dir, name)
                        app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    }.onFailure { e -> state.update { it.copy(toast = "添加失败：${e.message}") } }
                }
            }
            onDone()
        }
    }

    fun deleteWorkspaceFile(file: File, onDone: () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { Workspace.delete(file) }; onDone() }
    }

    internal fun deleteImages(list: List<Attachment>) {
        val paths = list.filter { it.kind == "image" && it.path.isNotEmpty() }.map { it.path }
        if (paths.isEmpty()) return
        scope.launch(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
    }
}
