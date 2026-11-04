package com.jack.ai.ui

import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.NetworkLedger
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.cap.web.WebBarrier
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** 只在 Kavi 操作网页期间订阅 CDP Network；会话关闭后暂存有限的文字响应供下次 extract 使用。 */
internal object BrowserNetwork {
    private const val CALL_MS = 1_500L
    private const val FLUSH_MS = 3_000L
    private const val MAX_BODIES_PER_FLUSH = 24
    private fun ledger() = NetworkLedger(maxCaptured = 100, maxBodyBytes = 256 * 1024, maxTotalBodyBytes = 1024 * 1024)

    private class TabData {
        val mutex = Mutex()
        var ledger = ledger()
        var session: CdpSession? = null
        var scope: CoroutineScope? = null
        var enabled = false

        fun detach() {
            enabled = false
            session = null
            scope?.cancel()
            scope = null
        }
    }

    private val tabs = ConcurrentHashMap<Int, TabData>()

    /** 先订阅再启用 Network；重连保留当前文档已抓到的响应正文。 */
    suspend fun start(tab: JackBrowser.Tab, s: CdpSession) {
        val data = tabs.getOrPut(tab.id) { TabData() }
        data.mutex.withLock {
            if (data.session === s && data.enabled && !s.closed.value) return@withLock
            data.detach()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            data.scope = scope
            data.session = s
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                s.events.collect { e ->
                    if (e.method.startsWith("Network.")) {
                        synchronized(data) {
                            data.ledger.onEvent(e.method, e.params, System.currentTimeMillis(), e.sessionId)
                        }
                    }
                }
            }
            data.enabled = try {
                s.send("Network.enable", buildJsonObject {
                    put("maxResourceBufferSize", 256 * 1024)
                    put("maxTotalBufferSize", 2 * 1024 * 1024)
                }, timeoutMs = CALL_MS)
                true
            } catch (e: CancellationException) {
                data.detach()
                throw e
            } catch (e: Exception) {
                tab.diag.add("CDP Network 无法启动：" + e.javaClass.simpleName + "，继续用页面脚本")
                data.detach()
                false
            }
        }
    }

    /** 页面真正切换时丢弃上一个文档的接口数据，防止把旧价格当成新价格。 */
    fun navigated(id: Int) {
        tabs[id]?.let { synchronized(it) { it.ledger = ledger() } }
    }

    /** 浏览器工具结束、调试接口关闭前取回完成的 JSON/文本响应，随后可跨工具调用读取。 */
    suspend fun flush(tabId: Int? = null) {
        withTimeoutOrNull(FLUSH_MS) {
            coroutineScope {
                val current = if (tabId == null) tabs.values.toList() else listOfNotNull(tabs[tabId])
                current.map { data ->
                    async(Dispatchers.IO) {
                        val s = data.session?.takeIf { data.enabled && !it.closed.value } ?: return@async
                        val candidates = synchronized(data) {
                            data.ledger.list().asReversed().mapNotNull {
                                data.ledger.bodyToFetch(it.id)?.let { req -> it.id to req }
                            }.take(MAX_BODIES_PER_FLUSH)
                        }
                        val gate = Semaphore(4)
                        candidates.map { (id, req) ->
                            async {
                                gate.withPermit {
                                    try {
                                        val r = s.send("Network.getResponseBody", buildJsonObject {
                                            put("requestId", req.requestId)
                                        }, req.sessionId, CALL_MS)
                                        val body = (r["body"] as? JsonPrimitive)?.contentOrNull
                                        val encoded = (r["base64Encoded"] as? JsonPrimitive)?.booleanOrNull == true
                                        if (body != null) synchronized(data) { data.ledger.putBody(id, body, encoded) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // 导航后浏览器可能不再保留响应正文；留给页面脚本兜底。
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }.awaitAll()
            }
        }
    }

    fun table(id: Int, pageUrl: String): JsonObject? {
        val data = tabs[id] ?: return null
        val responses: List<CapturedResponse> = synchronized(data) { data.ledger.list() }
        return BrowserNetworkRows.table(responses, pageUrl)
    }

    /** 限流只看同网站的接口：429 或者响应顶层明确写了风控；不把第三方广告 403 算成被封。 */
    fun risk(id: Int, pageUrl: String): String? {
        val data = tabs[id] ?: return null
        val site = PagePacing.site(pageUrl) ?: return null
        return synchronized(data) {
            // 只看同网站最近完成的一个请求：用户在页面上过了验证、新请求正常了，就不再算受限
            val last = data.ledger.list().lastOrNull {
                PagePacing.site(it.url) == site && it.state == CapturedResponse.State.DONE
            } ?: return@synchronized null
            if (last.status == 429) "请求过于频繁" else WebBarrier.apiRisk(last.body)
        }
    }

    /** 滚动之前的请求编号；滚动后有新的、大于 2KB 的列表响应才算又加载了一页。 */
    fun mark(id: Int): Set<String>? = tabs[id]?.let { data ->
        synchronized(data) { data.ledger.list().mapTo(HashSet()) { it.id } }
    }

    fun loadedMore(id: Int, before: Set<String>?): Boolean {
        before ?: return false
        val data = tabs[id] ?: return false
        return synchronized(data) {
            data.ledger.list().any { r ->
                r.id !in before && r.state == CapturedResponse.State.DONE &&
                    (r.status ?: 0) in 200..299 && (r.size ?: 0) >= 2_048 &&
                    NetworkLedger.isText(r.mime)
            }
        }
    }

    /** 会话与 USB 调试权限一同关闭，数据仍仅在内存里保留。 */
    fun detachAll() {
        tabs.values.forEach { it.detach() }
    }

    /** 关标签页后立刻释放其缓存。 */
    fun release(id: Int) { tabs.remove(id)?.detach() }
}
