package com.jack.ai.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Kavi 自己用的代理：App 里带一个 sing-box 内核，作为后台小进程跑，只在本机开一个代理端口给 Kavi 的请求用。
 * 不是系统 VPN，手机上别的 App 不受影响。端口要用户名密码，别的 App 蹭不了。
 */
object ProxyCore {
    /** 正在跑的配置（不含端口和口令时的内容，用来判断要不要重启）。 */
    private var running: Process? = null
    private var runningKey = ""
    private val lock = Any()
    private val log = ArrayDeque<String>()

    @Volatile var port = 0
        private set
    @Volatile var user = ""
        private set
    @Volatile var pass = ""
        private set
    /** 上次启动失败的原因，给报错信息用。 */
    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = running?.isAlive == true

    /** 双跳的第一跳在节点里的位置；没开、或者节点已经不在了是 -1。 */
    fun entryIndex(nodes: List<ProxyNode>, entry: String): Int =
        if (entry.isBlank() || nodes.size < 2) -1 else nodes.indexOfFirst { it.name == entry }

    /**
     * 出口的 tag：用户选的节点；空的就自动选最快的。开着双跳时出口用 c 开头的那份（先过第一跳）；
     * 出口选的就是第一跳本身时，等于没有双跳，直接用它。
     */
    fun tagFor(nodes: List<ProxyNode>, selected: String, entry: String = ""): String {
        val hop = entryIndex(nodes, entry)
        val i = nodes.indexOfFirst { it.name == selected }
        return when {
            i >= 0 -> if (hop >= 0 && i != hop) "c$i" else "n$i"
            nodes.size > 1 -> "auto"
            else -> "n0"
        }
    }

    /**
     * 生成 sing-box 配置：本机一个带口令的 mixed 入口（HTTP 和 SOCKS 都认），出口是用户的节点；
     * 多个节点时加一个「自动选最快」（只挑加密强的）。节点的服务器域名用阿里的加密 DNS 解析（安卓上内核读不到系统 DNS）。
     *
     * 双跳（entry 是第一跳的节点名）：每个出口节点多一份 c 开头的配置，detour 指向第一跳——
     * Kavi → 第一跳 → 出口 → 网站。第一跳只看到你连了出口节点，看不到你访问的网站；出口和网站只看到第一跳的地址，看不到你的。
     * 「自动选最快」也在这些双跳线路里挑（测的是整条线路的速度）。
     */
    /**
     * OpenAI / ChatGPT 的流量走哪个出口：用户指定的节点；没指定就在 OpenAI 支持的地区里自动挑（见 [NodeRegion]）。
     * 返回 null 表示不另走（和别的流量一样）：没有能挑的，或者挑出来的就是平常的出口。
     */
    fun openAiTag(nodes: List<ProxyNode>, selected: String, entry: String, openAi: String, hardened: Boolean): String? {
        val hop = entryIndex(nodes, entry)
        fun exitTag(i: Int) = if (hop >= 0 && i != hop) "c$i" else "n$i"
        val chosen = nodes.indexOfFirst { it.name == openAi }
        if (chosen >= 0) return exitTag(chosen).takeIf { it != tagFor(nodes, selected, entry) }
        val main = nodes.indexOfFirst { it.name == selected }
        // 平常的出口本身就在支持的地区：不用另走
        if (main >= 0 && !NodeRegion.blockedByOpenAi(nodes[main].name)) return null
        val pool = (if (hardened) NodeSecurity.autoPool(nodes) else nodes.indices.toList()).filter { it != hop && !NodeRegion.blockedByOpenAi(nodes[it].name) }
            .ifEmpty { nodes.indices.filter { it != hop && !NodeRegion.blockedByOpenAi(nodes[it].name) } }
        return when {
            pool.isEmpty() -> null
            pool.size == 1 -> exitTag(pool[0])
            else -> "openai"
        }
    }

    fun config(nodes: List<ProxyNode>, selected: String, port: Int, user: String, pass: String, hardened: Boolean = true, entry: String = "", openAi: String = ""): String = buildJsonObject {
        val hop = entryIndex(nodes, entry)
        val aiTag = openAiTag(nodes, selected, entry, openAi, hardened)
        fun exitTag(i: Int) = if (hop >= 0) "c$i" else "n$i"
        put("log", buildJsonObject { put("level", "warn") })
        put("dns", buildJsonObject {
            put("servers", buildJsonArray {
                // 解析节点的域名用加密 DNS（阿里 DoH）：公共 Wi-Fi、运营商看不到连的是哪个节点
                if (hardened) add(buildJsonObject {
                    put("type", "https"); put("tag", "dns"); put("server", "223.5.5.5")
                    put("tls", buildJsonObject { put("enabled", true); put("server_name", "dns.alidns.com") })
                })
                else add(buildJsonObject { put("type", "udp"); put("tag", "dns"); put("server", "223.5.5.5") })
            })
        })
        put("inbounds", buildJsonArray {
            add(buildJsonObject {
                put("type", "mixed"); put("tag", "in"); put("listen", "127.0.0.1"); put("listen_port", port)
                put("users", buildJsonArray { add(buildJsonObject { put("username", user); put("password", pass) }) })
            })
        })
        put("outbounds", buildJsonArray {
            if (nodes.size > 1) add(buildJsonObject {
                put("type", "urltest"); put("tag", "auto")
                // 只在加密够强的节点里挑（不加密、弱加密的手动选才用）；双跳时第一跳本身不当出口
                val pool = (if (hardened) NodeSecurity.autoPool(nodes) else nodes.indices.toList()).filter { it != hop }
                    .ifEmpty { nodes.indices.filter { it != hop } }
                put("outbounds", JsonArray(pool.map { JsonPrimitive(exitTag(it)) }))
                put("url", "https://www.gstatic.com/generate_204"); put("interval", "10m")
            })
            // OpenAI 专用的「自动选最快」：只在 OpenAI 支持的地区里挑（自动选最快的往往是香港，OpenAI 不对香港开放）
            if (aiTag == "openai") add(buildJsonObject {
                put("type", "urltest"); put("tag", "openai")
                val pool = (if (hardened) NodeSecurity.autoPool(nodes) else nodes.indices.toList()).filter { it != hop && !NodeRegion.blockedByOpenAi(nodes[it].name) }
                    .ifEmpty { nodes.indices.filter { it != hop && !NodeRegion.blockedByOpenAi(nodes[it].name) } }
                put("outbounds", JsonArray(pool.map { JsonPrimitive(exitTag(it)) }))
                put("url", "https://www.gstatic.com/generate_204"); put("interval", "10m")
            })
            nodes.forEachIndexed { i, n -> add(ProxyNodes.outbound(n, "n$i")) }
            if (hop >= 0) nodes.forEachIndexed { i, n ->
                if (i != hop) add(kotlinx.serialization.json.JsonObject(ProxyNodes.outbound(n, "c$i") + ("detour" to JsonPrimitive("n$hop"))))
            }
            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
        })
        put("route", buildJsonObject {
            if (aiTag != null) put("rules", buildJsonArray {
                add(buildJsonObject {
                    put("domain_suffix", JsonArray(com.jack.ai.api.Net.ALWAYS_PROXY.map { JsonPrimitive(it) }))
                    put("outbound", aiTag)
                })
            })
            put("final", tagFor(nodes, selected, entry))
            put("default_domain_resolver", "dns")
        })
    }.toString()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun token(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 最近的内核输出，出错时给用户看原因。 */
    fun logTail(): String = synchronized(log) { log.takeLast(6).joinToString("\n") }

    /**
     * 确保内核在跑、而且用的是现在的节点设置；没在跑或设置变了就（重新）启动。成功返回 true。
     * binary 是内核程序，workDir 放配置文件。会阻塞最多几秒，不要在主线程调。
     */
    fun ensure(binary: File, workDir: File, nodes: List<ProxyNode>, selected: String, hardened: Boolean = true, entry: String = "", openAi: String = ""): Boolean = synchronized(lock) {
        if (nodes.isEmpty()) { lastError = "还没有导入节点"; return false }
        val key = nodes.joinToString("|") { it.outbound } + "#" + selected + "#" + hardened + "#" + entry + "#" + openAi
        if (running?.isAlive == true && key == runningKey) return true
        stopLocked()
        if (!binary.exists()) { lastError = "这个安装包里没有代理内核"; return false }
        workDir.mkdirs()
        val p = freePort()
        val u = token()
        val pw = token()
        val cfg = File(workDir, "config.json").apply { writeText(config(nodes, selected, p, u, pw, hardened, entry, openAi)) }
        synchronized(log) { log.clear() }
        val proc = try {
            ProcessBuilder(binary.absolutePath, "run", "-c", cfg.absolutePath, "-D", workDir.absolutePath, "--disable-color")
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            lastError = "代理内核启动不了：${e.message}"
            return false
        }
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.addLast(line.take(300)); while (log.size > 40) log.removeFirst() }
                }
            }
        }.apply { isDaemon = true }.start()
        // 等端口开起来；进程提前退出说明配置或节点有问题
        repeat(60) {
            if (!proc.isAlive) {
                lastError = "代理内核退出了：" + logTail().ifBlank { "没有输出" }
                return false
            }
            val up = runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", p), 100) }; true }.getOrDefault(false)
            if (up) {
                running = proc; runningKey = key; port = p; user = u; pass = pw; lastError = null
                // 配置里有节点密码，内核读完就删掉
                cfg.delete()
                return true
            }
            Thread.sleep(100)
        }
        proc.destroy()
        lastError = "代理内核没能在 6 秒内启动：" + logTail()
        false
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        running?.let { p -> runCatching { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } }
        running = null
        runningKey = ""
    }
}

/**
 * 按节点名猜地区。OpenAI 不对中国大陆、香港、澳门、俄罗斯、伊朗等地开放，从这些地区的出口连 chatgpt.com / auth.openai.com
 * 会被拦（「Unable to load site … If you are using a VPN, try turning it off」）。节点名里一般写着地区，按名字认。
 */
object NodeRegion {
    private val BLOCKED_WORDS = listOf("香港", "港", "澳门", "中国", "回国", "大陆", "俄罗斯", "伊朗", "朝鲜", "叙利亚", "古巴",
        "hong kong", "hongkong", "macau", "macao", "china", "russia", "iran", "🇭🇰", "🇲🇴", "🇨🇳", "🇷🇺", "🇮🇷")
    /** 两三个字母的地区缩写：前后不能连着别的字母（免得 RU 认成 TRUE 里的一段）。 */
    private val BLOCKED_CODES = Regex("(?i)(^|[^a-z])(hk|hkg|mo|cn|chn|ru|rus|ir)([^a-z]|$)")

    fun blockedByOpenAi(name: String): Boolean {
        val n = name.lowercase()
        return BLOCKED_WORDS.any { it in n } || BLOCKED_CODES.containsMatchIn(name)
    }
}

