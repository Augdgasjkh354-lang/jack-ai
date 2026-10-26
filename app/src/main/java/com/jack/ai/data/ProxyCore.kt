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

    /** 用户选的节点；空的就自动选最快的。 */
    fun tagFor(nodes: List<ProxyNode>, selected: String): String =
        nodes.indexOfFirst { it.name == selected }.takeIf { it >= 0 }?.let { "n$it" } ?: if (nodes.size > 1) "auto" else "n0"

    /**
     * 生成 sing-box 配置：本机一个带口令的 mixed 入口（HTTP 和 SOCKS 都认），出口是用户的节点；
     * 多个节点时加一个「自动选最快」。节点的服务器域名用阿里 DNS 解析（安卓上内核读不到系统 DNS）。
     */
    fun config(nodes: List<ProxyNode>, selected: String, port: Int, user: String, pass: String): String = buildJsonObject {
        put("log", buildJsonObject { put("level", "warn") })
        put("dns", buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject { put("type", "udp"); put("tag", "dns"); put("server", "223.5.5.5") })
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
                put("outbounds", JsonArray(nodes.indices.map { JsonPrimitive("n$it") }))
                put("url", "https://www.gstatic.com/generate_204"); put("interval", "10m")
            })
            nodes.forEachIndexed { i, n -> add(ProxyNodes.outbound(n, "n$i")) }
            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
        })
        put("route", buildJsonObject {
            put("final", tagFor(nodes, selected))
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
    fun ensure(binary: File, workDir: File, nodes: List<ProxyNode>, selected: String): Boolean = synchronized(lock) {
        if (nodes.isEmpty()) { lastError = "还没有导入节点"; return false }
        val key = nodes.joinToString("|") { it.outbound } + "#" + selected
        if (running?.isAlive == true && key == runningKey) return true
        stopLocked()
        if (!binary.exists()) { lastError = "这个安装包里没有代理内核"; return false }
        workDir.mkdirs()
        val p = freePort()
        val u = token()
        val pw = token()
        val cfg = File(workDir, "config.json").apply { writeText(config(nodes, selected, p, u, pw)) }
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
