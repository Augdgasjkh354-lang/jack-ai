package com.jack.ai.data

import com.wangzan101.mika.core.cap.mcp.McpOAuth
import com.wangzan101.mika.core.cap.mcp.McpOAuthTokens
import com.wangzan101.mika.core.oauth.LoopbackCallback
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 连接器用账号登录（MCP OAuth 2.1）：找到授权服务器、自动注册、在浏览器里点同意、换令牌，快过期了自动续。
 * 底层移植自 Mika 的 McpOAuth（Apache-2.0，见 third-party/mika）。令牌只存在连接器设置里（随设置加密）。
 */
object McpLogin {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 续过的令牌要存回设置：App 启动时接上（不接就只在这次用，下次再续）。 */
    @Volatile var save: ((McpServer) -> Unit)? = null

    fun tokens(server: McpServer): McpOAuthTokens? =
        server.oauth.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(McpOAuthTokens.serializer(), it) }.getOrNull() }

    private fun client(server: McpServer) = McpOAuth(com.jack.ai.api.sharedHttp.newBuilder()
        .proxySelector(com.jack.ai.api.Net.selectorFor(server.route)).build())

    /** 用之前看一眼：令牌快过期而且能续，就续好、存回去。续不成就照旧用（服务会回 401，用户重新登录）。 */
    suspend fun fresh(server: McpServer): McpServer {
        val t = tokens(server) ?: return server
        if (t.isFresh() || t.refreshToken.isNullOrBlank()) return server
        val next = runCatching { client(server).refresh(t) }.getOrNull() ?: return server
        return server.copy(oauth = json.encodeToString(McpOAuthTokens.serializer(), next)).also { s -> save?.invoke(s) }
    }

    /**
     * 登录一次：[open] 打开授权网页（系统浏览器），用户点同意后浏览器跳回本机的回调，这里换到令牌。
     * 最多等 5 分钟。返回带令牌的连接器（还没存）。
     */
    suspend fun login(server: McpServer, open: (String) -> Unit): McpServer {
        val url = server.url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("连接器地址不对")
        val oauth = client(server)
        val auth = oauth.discover(url)
        LoopbackCallback.open().use { cb ->
            val redirect = cb.redirectUri
            val reg = oauth.register(auth, redirect, "Kavi")
            val attempt = oauth.newAttempt(redirect)
            open(oauth.authorizeUrl(auth, reg, attempt, auth.resource))
            var tokens: McpOAuthTokens? = null
            var failure: Exception? = null
            cb.await(5 * 60_000L, accept = { it["state"] == attempt.state }) { params ->
                try {
                    oauth.readCallbackError(params, attempt)?.let { throw it }
                    tokens = oauth.exchange(auth, reg, oauth.readCode(params, attempt), attempt, auth.resource)
                    PAGE_OK
                } catch (e: Exception) { failure = e; PAGE_FAIL }
            } ?: throw IllegalStateException("等了 5 分钟没等到登录完成")
            failure?.let { throw it }
            return server.copy(oauth = json.encodeToString(McpOAuthTokens.serializer(), tokens!!), key = "")
        }
    }

    private const val PAGE_OK = "<html><meta charset=utf-8><body style='font-family:sans-serif;padding:40px'><h2>已登录</h2><p>回到 Kavi 就能用了，这个页面可以关掉。</p></body></html>"
    private const val PAGE_FAIL = "<html><meta charset=utf-8><body style='font-family:sans-serif;padding:40px'><h2>没登录成功</h2><p>回到 Kavi 看看原因，再试一次。</p></body></html>"
}
