package com.jack.ai.ui

import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.cap.web.WebBarrier
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/** Kavi 浏览器适配：每个标签页分别记翻页节奏与当前网页的限流状态。 */
internal object BrowserPacing {
    private class State {
        val pacing = PagePacing()
        var url = ""
        var barrier: WebBarrier.Block? = null
    }
    private val tabs = ConcurrentHashMap<Int, State>()

    /** AI 第一次读这个标签页时把当前页算第一页；同页反复 read 不会重复计数。 */
    fun seen(tab: JackBrowser.Tab, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val s = tabs.getOrPut(tab.id) { State() }
        synchronized(s) {
            val normalized = url.substringBefore('#')
            if (s.url == normalized) return
            s.url = normalized
            s.barrier = null
            s.pacing.noteLoad(normalized, System.currentTimeMillis())
        }
    }

    /** 只跟踪已由 AI 操作过的标签页；用户自己浏览不引入额外延迟。 */
    fun navigated(tab: JackBrowser.Tab, url: String) {
        if (tabs.containsKey(tab.id)) seen(tab, url)
    }

    fun loadedMore(tab: JackBrowser.Tab) {
        val s = tabs[tab.id] ?: return
        synchronized(s) {
            if (s.url.isNotBlank()) s.pacing.noteLoad(s.url, System.currentTimeMillis())
        }
    }

    /** 新网站不等，最近的同站第二页起补足停留时间；模型思考期间已过去的时间不再补。 */
    suspend fun before(tab: JackBrowser.Tab, destination: String = tab.address) {
        val s = tabs[tab.id] ?: return
        val remaining = s.pacing.waitBefore(destination, System.currentTimeMillis())
        if (remaining <= 0) return
        tab.diag.add("同网站连续翻页，停留 " + remaining + "ms")
        delay(remaining)
        s.pacing.served(destination)
    }

    fun report(tab: JackBrowser.Tab, url: String, title: String, text: String,
               status: Int?, frames: List<String>, apiRisk: String?, longPage: Boolean = false): WebBarrier.Block? {
        seen(tab, url)
        val s = tabs[tab.id] ?: return null
        val result = WebBarrier.classify(url, title, text, status, frames, apiRisk, longPage)
        synchronized(s) {
            if (s.barrier != result && result != null) {
                tab.diag.add("网站需要人工接管：" + result.detail)
            }
            s.barrier = result
        }
        return result
    }

    /** 已经遇到拦截页时禁止继续操作同站；允许换站离开。 */
    fun blocked(tab: JackBrowser.Tab, target: String? = null): WebBarrier.Block? {
        val s = tabs[tab.id] ?: return null
        return synchronized(s) {
            val result = s.barrier ?: return@synchronized null
            if (target != null && PagePacing.site(target) != PagePacing.site(s.url)) null else result
        }
    }

    fun release(id: Int) { tabs.remove(id) }
}
