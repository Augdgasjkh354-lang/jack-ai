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

// 界面状态（从 ChatViewModel.kt 拆出来）。

/** 一次「执行前确认」：标题、要确认的内容、确认按钮的字。 */
/** [always]：这一类事能「以后都允许」时，给人看的那句（比如「在微信里点发送」）；null 就只有允许和取消。 */
data class ConfirmRequest(val id: String, val title: String, val body: String, val action: String, val always: com.jack.ai.data.AllowRule? = null)

data class UiState(
    val conversations: List<Conversation> = emptyList(),
    val projects: List<Project> = emptyList(),
    val currentId: String? = null,
    /** 还没发出去的新对话属于哪个项目。 */
    val draftProjectId: String? = null,
    val settings: Settings = Settings(),
    val pending: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    val toast: String? = null,
    /** 带「去设置」的提示：(说什么, 去哪一页——SettingsPage 的名字，USAGE 是统计页)。聊天页用底部提示条显示。 */
    val toastGo: Pair<String, String>? = null,
    val compressingId: String? = null,
    val memories: List<MemoryItem> = emptyList(),
    val dreams: List<DreamEntry> = emptyList(),
    val dreaming: Boolean = false,
    /** 从别的 App 分享进来、或语音识别出来的文字，等输入框取走。 */
    val incomingText: String? = null,
    /** 桌面小组件点了话筒，等输入框打开语音识别。 */
    val voiceRequest: Boolean = false,
    /** 要开始录音纪要（长按图标的「录音纪要」）。 */
    val recordRequest: Boolean = false,
    /** 从桌面快捷方式进来，要直接打开相机。 */
    val cameraRequest: Boolean = false,
    val routines: List<Routine> = emptyList(),
    val library: List<LibraryDoc> = emptyList(),
    /** 知识库（从 GitHub 装的、自己建的）。 */
    val kbs: List<com.jack.ai.data.KnowledgeBase> = emptyList(),
    /** 「下载知识库」里列出的仓库内容；null 是还没打开或在加载。 */
    val kbCatalog: Pair<com.jack.ai.data.KbRepo.Listing, List<com.jack.ai.data.KnowledgeBases.Entry>>? = null,
    /** 知识库正在做的事（加载目录、下载、同步），界面显示一行进度；null 是空闲。 */
    val kbBusy: String? = null,
    val cards: List<Flashcard> = emptyList(),
    /** 工具执行前等用户确认（比如发邮件），界面弹窗。 */
    val confirm: ConfirmRequest? = null,
    /** 正在回答的对话（可以好几个同时答）。切去看别的对话不会打断它们。 */
    val streamingIds: Set<String> = emptySet(),
) {
    /** 有回答正在进行（不管是哪个对话）。 */
    val streaming: Boolean get() = streamingIds.isNotEmpty()
    /** 当前看着的这个对话正在回答。 */
    val streamingHere: Boolean get() = currentId != null && currentId in streamingIds
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
    val currentProjectId: String? get() = current?.projectId ?: if (currentId == null) draftProjectId else null
    fun project(id: String?): Project? = id?.let { pid -> projects.firstOrNull { it.id == pid } }
}
