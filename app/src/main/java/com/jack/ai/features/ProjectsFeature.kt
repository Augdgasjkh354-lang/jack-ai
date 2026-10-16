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

/** 项目：增删改、项目资料，以及项目大份资料的检索索引。 */
class ProjectsFeature(core: AppCore, private val files: FilesFeature) : Feature(core) {
    fun saveProject(project: Project) {
        state.update { s ->
            val p = project.copy(updatedAt = System.currentTimeMillis())
            val exists = s.projects.any { it.id == p.id }
            s.copy(projects = (if (exists) s.projects.map { if (it.id == p.id) p else it } else listOf(p) + s.projects)
                .sortedByDescending { it.updatedAt })
        }
        persistProjects()
    }

    fun deleteProject(id: String) {
        val removedConvs = state.value.conversations.filter { it.projectId == id }
        if (removedConvs.any { it.id == state.value.currentId }) core.stop()
        state.update { s ->
            s.copy(
                projects = s.projects.filterNot { it.id == id },
                conversations = s.conversations.filterNot { it.projectId == id },
                currentId = if (removedConvs.any { it.id == s.currentId }) null else s.currentId,
                draftProjectId = if (s.draftProjectId == id) null else s.draftProjectId,
            )
        }
        files.cleanup(removedConvs.flatMap { it.messages })
        files.deleteWorkspace("p_$id")
        persist()
        persistProjects()
    }

    fun addProjectFile(projectId: String, uri: Uri) {
        state.update { it.copy(importing = true) }
        scope.launch {
            try {
                val att = Attachments.importFile(app, uri)
                val p = state.value.project(projectId) ?: return@launch
                saveProject(p.copy(files = p.files + att))
            } catch (e: Exception) {
                state.update { it.copy(toast = e.message ?: "读取文件失败") }
            } finally {
                state.update { it.copy(importing = false) }
            }
        }
    }


    /** 项目大份资料的检索索引，按项目缓存；项目内容变了（updatedAt 变了）就重建。 */
    private val projectIndexes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, LibraryIndex>>()

    internal fun projectIndex(p: Project): LibraryIndex {
        projectIndexes[p.id]?.let { (at, idx) -> if (at == p.updatedAt) return idx }
        val idx = LibraryIndex(p.files.filter { it.text.length > PromptBuilder.PROJECT_INLINE_CHARS }.map { it.name to it.text })
        projectIndexes[p.id] = p.updatedAt to idx
        return idx
    }
}
