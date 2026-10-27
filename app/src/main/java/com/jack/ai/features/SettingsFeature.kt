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

/** 设置、其他模型服务、余额。 */
class SettingsFeature(core: AppCore, private val client: DeepSeekClient) : Feature(core) {
    fun updateSettings(transform: (Settings) -> Settings) {
        val before = state.value.settings
        state.update { it.copy(settings = transform(it.settings)) }
        val after = state.value.settings
        store.saveSettings(after)
        if (before.dreamEnabled != after.dreamEnabled) Dreamer.schedule(app, after.dreamEnabled)
        if (before.proactive != after.proactive) com.jack.ai.data.Proactive.schedule(app, after.proactive)
        if (before.appIcon != after.appIcon) com.jack.ai.ui.AppIcon.apply(app, after.appIcon)
        if (before.proxyHardened != after.proxyHardened) {
            com.jack.ai.ui.JackBrowser.protectionChanged()
            // 关掉：回到只给 Kavi 自己用（分流停掉，选的 App 留着）；再打开：之前开着分流就接着开
            if (!after.proxyHardened) com.jack.ai.vpn.KaviVpnService.stop(app)
            else if (after.proxyAppsOn && com.jack.ai.vpn.TProxyService.available && com.jack.ai.vpn.KaviVpnService.consent(app) == null) com.jack.ai.vpn.KaviVpnService.start(app)
        }
    }

    /** 获取某个服务的模型列表，返回 (可用的地址, [模型 id 和价格])。 */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Pair<String, List<Pair<String, com.jack.ai.data.Price?>>> =
        client.listModels(baseUrl, apiKey)

    /** 查其他服务的余额；这个服务不支持查询时返回 null。 */
    suspend fun providerBalance(p: Provider): String? = try {
        client.providerBalance(p.baseUrl, p.apiKey)
    } catch (e: ApiException) {
        e.message ?: "查询失败"
    } catch (e: IOException) {
        "网络连接失败"
    }

    fun guessModel(id: String, price: com.jack.ai.data.Price? = null) =
        ProviderModel(id, vision = guessVision(id), reasoning = guessReasoning(id), price = price)

    fun saveProvider(p: Provider) {
        updateSettings { s ->
            val list = if (s.providers.any { it.id == p.id }) s.providers.map { if (it.id == p.id) p else it } else s.providers + p
            fixModel(s.copy(providers = list))
        }
    }

    fun deleteProvider(id: String) = updateSettings { s -> fixModel(s.copy(providers = s.providers.filterNot { it.id == id })) }

    /** 当前选的模型没了（服务删了、模型取消勾选），换成第一个能用的。 */
    internal fun fixModel(s: Settings): Settings {
        val choices = s.modelChoices()
        return if (choices.any { it.key == s.model } || choices.isEmpty()) s else s.copy(model = choices.first().key)
    }

    /** 连上连接器拿工具列表，成功就存下来（新加的或刷新的）。返回给用户看的一句话。 */
    suspend fun saveConnector(server: McpServer): Result<McpServer> = try {
        val tools = Mcp.listTools(server)
        val saved = server.copy(tools = tools)
        updateSettings { s ->
            s.copy(mcpServers = if (s.mcpServers.any { it.id == saved.id }) s.mcpServers.map { if (it.id == saved.id) saved else it }
            else s.mcpServers + saved)
        }
        Result.success(saved)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun setConnector(server: McpServer) = updateSettings { s -> s.copy(mcpServers = s.mcpServers.map { if (it.id == server.id) server else it }) }

    fun deleteConnector(id: String) = updateSettings { s -> s.copy(mcpServers = s.mcpServers.filterNot { it.id == id }) }

    // ---------- Kavi 专用代理 ----------

    /** 拉订阅：先直连；连不上而代理正在跑，就借代理再试一次。不同的机场按客户端名字给不同格式，认不出就换个名字再要。 */
    private suspend fun fetchSub(url: String): String = withContext(Dispatchers.IO) {
        val direct = com.jack.ai.api.sharedHttp.newBuilder().proxySelector(com.jack.ai.api.Net.selectorFor("direct")).build()
        val viaProxy = com.jack.ai.api.sharedHttp.newBuilder().proxySelector(com.jack.ai.api.Net.selectorFor("proxy")).build()
        fun get(client: okhttp3.OkHttpClient, ua: String): String =
            client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("订阅地址返回 ${r.code}")
                r.body?.string().orEmpty()
            }
        var last: Exception? = null
        for (ua in listOf("v2rayN/6.42", "clash-verge/v2.0.0")) {
            for (client in listOf(direct, viaProxy)) {
                if (client === viaProxy && !ProxyCore.isRunning) continue
                try {
                    val text = get(client, ua)
                    if (ProxyNodes.parse(text).isNotEmpty()) return@withContext text
                    break
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        last?.let { throw it }
        throw IOException("订阅里没有认得出的节点")
    }

    /** 导入：订阅地址（https）、一个或几个节点链接、或者 base64 的一整段。返回节点数。 */
    suspend fun importProxy(input: String): Result<Int> = runCatching {
        val text = input.trim()
        val isUrl = text.startsWith("http://") || text.startsWith("https://")
        val nodes = ProxyNodes.parse(if (isUrl) fetchSub(text) else text)
        if (nodes.isEmpty()) throw IOException("没认出节点。支持订阅地址，以及 ss、vmess、vless、trojan、hysteria2、tuic 链接")
        updateSettings { s ->
            s.copy(proxySub = if (isUrl) text else "", proxyNodes = nodes, proxyEnabled = true,
                proxyNode = s.proxyNode.takeIf { n -> nodes.any { it.name == n } }.orEmpty(),
                proxyEntry = s.proxyEntry.takeIf { n -> nodes.any { it.name == n } }.orEmpty())
        }
        nodes.size
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }

    suspend fun refreshProxy(): Result<Int> {
        val sub = state.value.settings.proxySub
        if (sub.isBlank()) return Result.failure(IOException("这些节点不是从订阅导入的，重新扫码或粘贴就行"))
        return importProxy(sub)
    }

    /** 测试：拉起内核，经代理访问一个国外的小页面，返回耗时；失败给出原因。 */
    suspend fun testProxy(): String = withContext(Dispatchers.IO) {
        if (!com.jack.ai.api.Net.config.enabled) return@withContext "代理没开"
        if (!com.jack.ai.api.Net.ensureCore()) return@withContext "内核没起来：${ProxyCore.lastError}"
        val client = com.jack.ai.api.sharedHttp.newBuilder().proxySelector(com.jack.ai.api.Net.selectorFor("proxy"))
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        val start = System.currentTimeMillis()
        try {
            client.newCall(okhttp3.Request.Builder().url("https://www.gstatic.com/generate_204").build()).execute().use { r ->
                if (r.code == 204 || r.isSuccessful) "能用，${System.currentTimeMillis() - start} 毫秒" else "连上了但返回 ${r.code}"
            }
        } catch (e: Exception) {
            "连不通：${e.message ?: e.javaClass.simpleName}" + ProxyCore.logTail().takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        }
    }

    fun clearProxy() {
        ProxyCore.stop()
        updateSettings { it.copy(proxyEnabled = false, proxySub = "", proxyNodes = emptyList(), proxyNode = "", proxyEntry = "") }
    }

    /** 试着登录邮箱，返回给用户看的一句话。 */
    suspend fun testMail(acc: MailAccount): String = withContext(Dispatchers.IO) {
        try {
            MailClient.test(acc)
        } catch (e: MailException) {
            e.message ?: "连接失败"
        } catch (e: Exception) {
            "连接失败：${e.message}"
        }
    }

    suspend fun checkBalance(): String {
        val s = state.value.settings
        if (s.apiKey.isBlank()) return "请先填写 API Key"
        return try {
            "余额：" + client.balance(s.baseUrl, s.apiKey)
        } catch (e: ApiException) {
            e.message ?: "查询失败"
        } catch (e: IOException) {
            "网络连接失败"
        }
    }
}
