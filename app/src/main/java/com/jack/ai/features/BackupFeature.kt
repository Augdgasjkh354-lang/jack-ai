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

/** 导出单个对话，备份和恢复全部数据。 */
class BackupFeature(core: AppCore, private val knowledge: KnowledgeFeature) : Feature(core) {
    val exporting = MutableStateFlow(false)

    /** 把当前对话导出成文件，准备好后交给界面分享。 */
    fun exportChat(format: ChatExport.Format, onReady: (File) -> Unit) {
        val conv = state.value.current?.takeIf { it.messages.isNotEmpty() } ?: return
        if (exporting.value) return
        exporting.value = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ChatExport.export(app, conv, format) }
                onReady(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 长图太大时可能内存不够
                state.update { it.copy(toast = if (e is OutOfMemoryError) "对话太长，生成长图时内存不够，换成 PDF 试试" else "导出失败：${e.message}") }
            } finally {
                exporting.value = false
            }
        }
    }


    fun exportBackup(uri: Uri) {
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use {
                        Backup.export(app, it)
                    } ?: throw IOException("没法写入这个位置")
                }
                "备份好了"
            } catch (e: Exception) {
                "备份失败：${e.message}"
            }
            state.update { it.copy(toast = msg) }
        }
    }

    fun importBackup(uri: Uri) {
        core.stop()
        scope.launch {
            val msg = try {
                withContext(ioSerial) {
                    app.contentResolver.openInputStream(uri)?.use {
                        Backup.import(app, it)
                    } ?: throw IOException("打不开这个文件")
                }
                val old = state.value.settings
                val loaded = store.loadSettings()
                // 备份里没有 Key，沿用手机上现有的
                val s = loaded.copy(apiKey = old.apiKey, tavilyKey = old.tavilyKey, providers = loaded.providers.map { p ->
                    p.copy(apiKey = old.providers.firstOrNull { it.id == p.id }?.apiKey.orEmpty())
                }, mail = loaded.mail?.copy(password = old.mail?.takeIf { it.address == loaded.mail.address }?.password.orEmpty()))
                store.saveSettings(s)
                state.value = UiState(
                    conversations = store.loadConversations().sortedByDescending { it.updatedAt },
                    projects = store.loadProjects().sortedByDescending { it.updatedAt },
                    settings = s,
                    memories = store.loadMemories(),
                    dreams = store.loadDreams(),
                    routines = store.loadRoutines(),
                    library = store.loadLibrary(),
                    cards = store.loadCards(),
                )
                knowledge.resetIndex()
                Routines.scheduleAll(app)
                "已恢复备份"
            } catch (e: Exception) {
                "恢复失败：${e.message}"
            }
            state.update { it.copy(toast = msg) }
        }
    }
}
