package com.jack.ai.data

import android.content.Context
import androidx.compose.runtime.*
import com.jack.ai.BrowserDownloadService
import com.jack.ai.api.BrowserDownload
import com.jack.ai.api.BrowserQueue
import com.jack.ai.api.BrowserQueue.State
import com.jack.ai.ui.JackBrowser
import kotlinx.coroutines.*

/** 浏览器和 Kavi 共用的下载队列，页面离开后继续工作。 */
object BrowserDownloads {
    class Task(val incognito: Boolean, val cookie: String?, val route: String, val request: BrowserDownload.Pending, val target: (String, String) -> Downloads.Target, val progress: (String) -> Unit) {
        val id = java.util.UUID.randomUUID().toString()
        var state by mutableStateOf(State.QUEUED)
        var got by mutableLongStateOf(0)
        var total by mutableLongStateOf(request.size)
        var speed by mutableLongStateOf(0)
        var message by mutableStateOf("")
        var saved by mutableStateOf<Downloads.Saved?>(null)
        val result = CompletableDeferred<Downloads.Saved>()
        val control = BrowserDownload.Control()
        var destination: Downloads.Target? = null
        var job: Job? = null
        var pauseRequested = false
        var cancelled = false
        val pausable = request.url.startsWith("http", true)
    }
    val tasks = mutableStateListOf<Task>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    val active get() = tasks.count { it.state == State.RUNNING || it.state == State.QUEUED }

    fun enqueue(ctx: Context, p: BrowserDownload.Pending, target: (String, String) -> Downloads.Target, progress: (String) -> Unit = {}): Task {
        app = ctx.applicationContext
        val tab = JackBrowser.tabs.find { it.id == p.tab } ?: JackBrowser.tab
        val cookie = if (p.url.startsWith("http", true)) runCatching { JackBrowser.cookiesOf(tab).getCookie(p.url) }.getOrNull() else null
        val task = Task(tab.incognito, cookie, JackBrowser.routeFor(ctx), p.copy(tab = tab.id), target, progress)
        task.control.restart = { scope.launch { task.message = "网站不支持续传，已从头下载" } }
        tasks.add(task)
        pump()
        return task
    }
    private fun pump() {
        BrowserQueue.starts(tasks.map { it.state }).forEach { index ->
            val t = tasks[index]
            t.state = State.RUNNING
            t.control.interrupted = false
            t.pauseRequested = false
            BrowserDownloadService.start(app)
            t.job = scope.launch {
                var lastTime = System.currentTimeMillis()
                var lastBytes = t.got
                try {
                    val saved = JackBrowser.storeDirect(t.request, { name, mime ->
                        t.destination ?: t.target(name, mime).also { t.destination = it }
                    }, { text -> t.progress(text) }, t.control, t.incognito, t.cookie, t.route) { got, total ->
                        val now = System.currentTimeMillis()
                        val speed = (got - lastBytes).coerceAtLeast(0) * 1000 / (now - lastTime).coerceAtLeast(1)
                        lastTime = now; lastBytes = got
                        scope.launch { t.got = got; t.total = total; t.speed = speed }
                    }
                    t.saved = saved; t.state = State.COMPLETE; t.result.complete(saved)
                    if (JackBrowser.userViewing && t.request.byUser) JackBrowser.saved = saved
                } catch (e: Exception) {
                    when {
                        t.cancelled -> {
                            withContext(NonCancellable + Dispatchers.IO) { t.destination?.sink?.discard() }
                            t.state = State.CANCELLED
                            t.result.completeExceptionally(BrowserDownload.DownloadException("下载已取消"))
                        }
                        t.pauseRequested -> t.state = State.PAUSED
                        else -> {
                            t.state = State.FAILED; t.message = e.message ?: "下载失败"
                            t.result.completeExceptionally(e)
                            t.destination = null; t.control.offset = 0; t.control.validator = null
                        }
                    }
                } finally {
                    t.job = null
                    pump()
                }
            }
        }
        JackBrowser.downloading = active.takeIf { it > 0 }?.let { "正在下载 $it 个 · 点这里看" }
        if (active == 0) BrowserDownloadService.stop(app)
    }
    fun pause(t: Task) {
        if (!t.pausable) return
        if (t.state == State.RUNNING) { t.pauseRequested = true; t.control.interrupt() }
        else { t.state = BrowserQueue.pause(t.state); pump() }
    }
    fun resume(t: Task) {
        if (t.job != null || t.state != State.PAUSED) return
        t.state = BrowserQueue.resume(t.state); pump()
    }
    fun cancel(t: Task) {
        if (t.state == State.COMPLETE || t.state == State.CANCELLED) return
        t.cancelled = true
        if (t.job != null) { t.control.interrupt(); if (!t.pausable) t.job?.cancel() }
        else {
            scope.launch(Dispatchers.IO) { t.destination?.sink?.discard() }
            t.state = State.CANCELLED
            t.result.completeExceptionally(BrowserDownload.DownloadException("下载已取消"))
            pump()
        }
    }
}
