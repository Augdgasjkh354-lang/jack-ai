package com.jack.ai.bench

import com.jack.ai.ui.BrowserNetworkRows
import com.wangzan101.mika.core.cap.web.NetworkLedger
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpStream
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 随机端口的本地网站，不访问互联网。 */
internal class BenchServer : Closeable {
    // Android 测试编译器的 bootclasspath 没有 jdk.httpserver；JVM 运行时仍使用 JDK 自带服务。
    private val http = Class.forName("com.sun.net.httpserver.HttpServer")
    private val exchangeType = Class.forName("com.sun.net.httpserver.HttpExchange")
    private val handlerType = Class.forName("com.sun.net.httpserver.HttpHandler")
    private val server = http.getMethod("create", InetSocketAddress::class.java, Int::class.javaPrimitiveType)
        .invoke(null, InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    val port: Int get() = (http.getMethod("getAddress").invoke(server) as InetSocketAddress).port

    init {
        http.getMethod("setExecutor", java.util.concurrent.Executor::class.java).invoke(server, executor)
        val handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType),
            InvocationHandler { _, method, args ->
                if (method.name == "handle") handle(requireNotNull(args?.firstOrNull()))
                null
            })
        http.getMethod("createContext", String::class.java, handlerType).invoke(server, "/", handler)
        http.getMethod("start").invoke(server)
    }

    private fun handle(exchange: Any) {
        val path = (exchangeType.getMethod("getRequestURI").invoke(exchange) as java.net.URI)
            .path.removePrefix("/")
        val status = if (path == "rate-limited.html") 429 else 200
        val data: String
        val mime: String
        when (path) {
            "api/items" -> {
                data = "{\"items\":[" + (1..12).joinToString(",") { i ->
                    "{\"name\":\"商品" + i + "\",\"price\":\"" + i + "9.00\",\"shop\":\"店铺" + i +
                        "\",\"url\":\"https://example.com/item/" + i + "\",\"sales\":" + (100 + i) + "}"
                } + "]}"
                mime = "application/json; charset=utf-8"
            }
            "api/recommend" -> {
                data = """{"items":[{"name":"推荐商品","price":"5.00","shop":"推荐店铺"}]}"""
                mime = "application/json; charset=utf-8"
            }
            else -> {
                val stream = javaClass.classLoader.getResourceAsStream("bench/" + path)
                data = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?.replace("%PORT%", port.toString()) ?: "<h1>Not Found</h1>"
                mime = "text/html; charset=utf-8"
            }
        }
        val bytes = data.toByteArray(Charsets.UTF_8)
        val headers = exchangeType.getMethod("getResponseHeaders").invoke(exchange)
        headers.javaClass.getMethod("add", String::class.java, String::class.java)
            .invoke(headers, "Content-Type", mime)
        headers.javaClass.getMethod("add", String::class.java, String::class.java)
            .invoke(headers, "Cache-Control", "no-store")
        exchangeType.getMethod("sendResponseHeaders", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(exchange, status, bytes.size.toLong())
        (exchangeType.getMethod("getResponseBody").invoke(exchange) as java.io.OutputStream).use { it.write(bytes) }
    }

    fun url(page: String, host: String = "a.localhost") = "http://" + host + ":" + port + "/" + page

    override fun close() {
        http.getMethod("stop", Int::class.javaPrimitiveType).invoke(server, 0)
        executor.shutdownNow()
    }
}

/** 用 TCP 字节流接入产品代码里的 CdpDiscovery / CdpSession。 */
internal class BenchChrome private constructor(
    private val process: Process,
    private val profile: File,
    val port: Int,
) : Closeable {
    val transport = CdpTransport {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 30_000
        object : CdpStream {
            override val input = socket.getInputStream()
            override val output = socket.getOutputStream()
            override fun close() = socket.close()
        }
    }

    private var browser: CdpSession? = null

    private suspend fun browserSession(): CdpSession {
        browser?.let { return it }
        val version = CdpDiscovery.version(transport, port.toString(), timeoutMs = 3_000)
        val session = CdpSession.connect(transport, port.toString(),
            CdpDiscovery.webSocketPath(requireNotNull(version.webSocketDebuggerUrl)),
            handshakeTimeoutMs = 3_000)
        browser = session
        return session
    }

    suspend fun open(url: String, watchNetwork: Boolean = false): BenchTab {
        val root = browserSession()
        val id = root.send("Target.createTarget", buildJsonObject { put("url", "about:blank") },
            timeoutMs = 3_000)["targetId"]!!.jsonPrimitive.content
        try {
            var targetUrl: String? = null
            for (i in 0 until 25) {
                targetUrl = CdpDiscovery.list(transport, port.toString(), timeoutMs = 2_000)
                    .firstOrNull { it.id == id }?.webSocketDebuggerUrl
                if (targetUrl != null) break
                delay(40)
            }
            val session = CdpSession.connect(transport, port.toString(),
                CdpDiscovery.webSocketPath(requireNotNull(targetUrl) { "找不到 target: " + id }),
                handshakeTimeoutMs = 3_000)
            val tab = BenchTab(id, session, root)
            try {
                tab.navigate(url, watchNetwork)
                return tab
            } catch (e: Exception) {
                tab.close()
                throw e
            }
        } catch (e: Exception) {
            runCatching { root.send("Target.closeTarget", buildJsonObject { put("targetId", id) }) }
            throw e
        }
    }

    override fun close() {
        browser?.close()
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        profile.deleteRecursively()
    }

    companion object {
        private fun browserPath(): String? {
            val candidates = listOfNotNull(
                System.getenv("CHROME_PATH")?.takeIf { it.isNotBlank() },
                "/opt/pw-browsers/chromium",
                "/opt/pw-browsers/chromium/chrome",
                "/opt/pw-browsers/chromium/chrome-linux/chrome",
                "/opt/pw-browsers/chromium/chrome-linux64/chrome",
            )
            candidates.firstOrNull { File(it).isFile && File(it).canExecute() }?.let { return it }
            return listOf("google-chrome", "chromium", "chromium-browser").firstNotNullOfOrNull { name ->
                val p = ProcessBuilder("sh", "-c", "command -v " + name).start()
                val path = p.inputStream.bufferedReader().readLine()
                if (p.waitFor() == 0 && !path.isNullOrBlank()) path else null
            }
        }

        /** 没装 Chrome 的本地环境由测试的 Assume 跳过。启动不起来时再试一次，还不行就把 Chrome 自己的输出带进报错。 */
        fun startOrNull(): BenchChrome? {
            val binary = browserPath() ?: return null
            var lastOutput = emptyList<String>()
            repeat(2) { attempt ->
                launch(binary, if (attempt == 0) 30L else 45L)?.let { (chrome, out) ->
                    if (chrome != null) return chrome
                    lastOutput = out
                }
            }
            error("Chrome 启动失败：没有得到 DevTools 端口（$binary）。Chrome 输出：\n" + lastOutput.takeLast(30).joinToString("\n"))
        }

        private fun launch(binary: String, waitSeconds: Long): Pair<BenchChrome?, List<String>>? {
            val profile = Files.createTempDirectory("jack-browser-bench").toFile()
            val process = ProcessBuilder(binary, "--headless=new", "--remote-debugging-port=0",
                "--user-data-dir=" + profile.absolutePath, "--no-sandbox", "--disable-gpu",
                "--disable-dev-shm-usage", "--no-first-run", "--no-proxy-server",
                "--window-size=1200,900")
                .redirectErrorStream(true).start()
            val port = AtomicInteger(0)
            val ready = CountDownLatch(1)
            val output = java.util.Collections.synchronizedList(mutableListOf<String>())
            thread(name = "browser-bench-stderr", isDaemon = true) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.size < 200) output += line
                        val found = Regex("DevTools listening on ws://[^:]+:(\\d+)/")
                            .find(line)?.groupValues?.get(1)?.toIntOrNull()
                        if (found != null) {
                            port.set(found)
                            ready.countDown()
                        }
                    }
                }
            }
            if (!ready.await(waitSeconds, TimeUnit.SECONDS)) {
                val alive = process.isAlive
                process.destroyForcibly()
                profile.deleteRecursively()
                return null to (output.toList() + "（等了 ${waitSeconds} 秒，进程" + (if (alive) "还在" else "已退出，退出码 ${runCatching { process.exitValue() }.getOrNull()}") + "）")
            }
            return BenchChrome(process, profile, port.get()) to emptyList()
        }
    }
}

internal class BenchTab(
    private val id: String,
    val session: CdpSession,
    private val browser: CdpSession,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ledger = NetworkLedger()
    @Volatile var mainStatus: Int? = null
        private set

    suspend fun navigate(url: String, watchNetwork: Boolean) {
        if (watchNetwork) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { event ->
                    if (!event.method.startsWith("Network.")) return@collect
                    synchronized(ledger) {
                        ledger.onEvent(event.method, event.params, System.currentTimeMillis(), event.sessionId)
                    }
                    if (event.method == "Network.responseReceived" &&
                        event.params["type"]?.jsonPrimitive?.contentOrNull == "Document") {
                        mainStatus = (event.params["response"] as? JsonObject)
                            ?.get("status")?.jsonPrimitive?.doubleOrNull?.toInt()
                    }
                }
            }
            session.send("Network.enable", timeoutMs = 2_000)
        }
        session.send("Page.enable", timeoutMs = 2_000)
        session.send("Page.navigate", buildJsonObject { put("url", url) }, timeoutMs = 3_000)
        waitUntil(4_000) { eval("document.readyState")?.jsonPrimitive?.contentOrNull == "complete" }
    }

    suspend fun eval(expression: String): JsonElement? =
        session.evaluate(expression, timeoutMs = 2_000)["value"]

    suspend fun string(expression: String): String = eval(expression)?.jsonPrimitive?.contentOrNull.orEmpty()

    suspend fun json(expression: String): JsonObject =
        Json.parseToJsonElement(string(expression)).jsonObject

    suspend fun waitUntil(ms: Long = 2_000, condition: suspend () -> Boolean): Boolean {
        return try {
            withTimeout(ms) {
                while (true) {
                    if (runCatching { condition() }.getOrDefault(false)) return@withTimeout true
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        }
    }

    suspend fun flushNetwork() {
        val captured = synchronized(ledger) {
            ledger.list().mapNotNull { r -> ledger.bodyToFetch(r.id)?.let { r.id to it } }
        }
        for ((id, req) in captured) {
            val response = runCatching {
                session.send("Network.getResponseBody", buildJsonObject {
                    put("requestId", req.requestId)
                }, req.sessionId, timeoutMs = 1_500)
            }.getOrNull() ?: continue
            val body = response["body"]?.jsonPrimitive?.contentOrNull ?: continue
            val base64 = response["base64Encoded"]?.jsonPrimitive?.contentOrNull == "true"
            synchronized(ledger) { ledger.putBody(id, body, base64) }
        }
    }

    fun rows(pageUrl: String): JsonObject? =
        BrowserNetworkRows.table(synchronized(ledger) { ledger.list() }, pageUrl)

    override fun close() {
        scope.cancel()
        session.close()
        kotlinx.coroutines.runBlocking {
            runCatching {
                browser.send("Target.closeTarget", buildJsonObject { put("targetId", id) }, timeoutMs = 1_000)
            }
        }
    }
}
