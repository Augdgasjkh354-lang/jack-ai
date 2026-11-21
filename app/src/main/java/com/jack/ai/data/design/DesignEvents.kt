package com.jack.ai.data.design

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Kavi 在聊天里改了某份底稿（edit_file、write_file、design render）：开着的编辑器听到后载入新版。 */
object DesignEvents {
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val sourceChanged = changes.asSharedFlow()

    fun changed(source: java.io.File) {
        changes.tryEmit(runCatching { source.canonicalPath }.getOrDefault(source.path))
    }
}
