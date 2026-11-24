package com.jack.ai.data.packs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kavi 写好工具包要用户装时，在界面上弹出权限确认页（和用户自己装时同一页）。
 * 界面在前台时由它显示、把用户的选择交回来；没有界面在看（App 在后台、后台任务）时 [ask] 返回 null，调用方改用普通确认框。
 */
object PackInstallPrompt {
    class Request(val staged: Packs.Staged, val old: InstalledPack?, internal val answer: CompletableDeferred<Boolean>) {
        fun reply(ok: Boolean) { answer.complete(ok) }
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** 有界面在听才问。 */
    suspend fun ask(staged: Packs.Staged, old: InstalledPack?): Boolean? {
        if (_pending.subscriptionCount.value == 0) return null
        val r = Request(staged, old, CompletableDeferred())
        _pending.value = r
        return try { r.answer.await() } finally { _pending.compareAndSet(r, null) }
    }
}
