package com.jack.ai.bench

import com.jack.ai.ui.CdpInputScript
import com.wangzan101.mika.core.cap.web.ElementFusion
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.core.cap.web.WebBarrier
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 12 个真实页面：只断言 CDP 新做法，旧脚本的结果用于对比。 */
class BrowserBenchTest {
    private data class Check(val ok: Boolean?, val why: String = "") {
        val status get() = when (ok) {
            true -> "成"
            false -> "不成"
            null -> "不适用"
        }
    }
    private data class Result(val page: String, val old: Check, val modern: Check)
    private val list = listOf(
        "trusted-click.html", "controlled-input.html", "enter-submit.html",
        "cross-origin-frame.html", "closed-shadow.html", "listener-text.html",
        "covered-button.html", "canvas-list.html", "dom-list.html",
        "rate-limited.html", "captcha-frame.html", "article.html",
    )

    @Test(timeout = 120_000L)
    fun browserRegression() = runBlocking {
        val results = mutableListOf<Result>()
        val started = System.nanoTime()
        for (page in list) {
            val old = if (page in setOf("rate-limited.html", "captcha-frame.html", "article.html"))
                Check(null, "旧脚本无拦截分类") else guarded(page, false)
            val modern = guarded(page, true)
            results += Result(page, old, modern)
        }
        val duration = (System.nanoTime() - started) / 1_000_000
        val report = buildString {
            appendLine("# 浏览器测试台报告")
            appendLine()
            appendLine("Chrome 无头测试；同一份 CDP 会话、元素融合、网络账本及输入事件顺序。")
            appendLine()
            appendLine("| 页面 | 旧做法 | 新做法 | 失败原因 |")
            appendLine("|---|---|---|---|")
            for (r in results) {
                val why = listOfNotNull(
                    r.old.why.takeIf { r.old.ok == false }?.let { "旧：" + it },
                    r.modern.why.takeIf { r.modern.ok == false }?.let { "新：" + it },
                ).joinToString("；").replace("|", "｜").replace("\n", " ")
                appendLine("| " + r.page + " | " + r.old.status + " | " + r.modern.status +
                    " | " + (why.ifBlank { "—" }) + " |")
            }
            appendLine()
            appendLine("总耗时：" + duration + " ms")
        }
        val workDir = File(System.getProperty("user.dir"))
        val appDir = if (File(workDir, "src/main").isDirectory) workDir else File(workDir, "app")
        val out = File(appDir, "build/reports/browser-bench.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        println(report)
        System.err.println(report)
        val failed = results.filter { it.modern.ok != true }
        assertTrue("CDP 新做法失败：" + failed.joinToString { it.page + "：" + it.modern.why },
            failed.isEmpty())
    }

    private suspend fun guarded(page: String, modern: Boolean): Check =
        try {
            withTimeout(20_000) { examine(page, modern) }
        } catch (e: Exception) {
            Check(false, e.javaClass.simpleName + "：" + (e.message ?: "未知错误").take(140))
        }

    private suspend fun examine(page: String, modern: Boolean): Check {
        val url = server!!.url(page)
        val tab = chrome!!.open(url, watchNetwork = modern)
        try {
            if (page == "canvas-list.html" || page == "dom-list.html") {
                if (!tab.waitUntil(2_500) {
                        tab.eval("window.__benchReady === true")?.jsonPrimitive?.booleanOrNull == true
                    }) return Check(false, "本地商品接口没有加载完")
                val web = snapshot(tab)
                val output = tab.json("window.__jack.extract('table', 0, 200)")
                val oldCount = rows(output)
                val effective = if (modern && oldCount == 0) {
                    tab.flushNetwork()
                    tab.rows(url)
                } else output
                val count = effective?.let(::rows) ?: 0
                return if (page == "canvas-list.html") {
                    Check(count >= 10, if (count < 10) "Canvas 无 DOM 商品；接口提取只有 " + count + " 行" else "")
                } else {
                    val tables = effective?.get("tables") as? JsonArray
                    val sales = tables.orEmpty().any {
                        (it.jsonObject["headers"] as? JsonArray).orEmpty().any { h ->
                            h.jsonPrimitive.content == "销量"
                        }
                    }
                    Check(count == 12 && sales, if (count != 12 || !sales) "DOM 表格未保持 12 行和销量列" else "")
                }
            }
            if (page == "rate-limited.html" || page == "captcha-frame.html" || page == "article.html") {
                val s = snapshot(tab)
                val urls = (s["frameUrls"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                val block = WebBarrier.classify(url, text(s, "title"), text(s, "text"),
                    tab.mainStatus, urls, longPage = s.getValue("textLength").jsonPrimitive.int > 800)
                val expected = when (page) {
                    "rate-limited.html" -> WebBarrier.Kind.LIMITED
                    "captcha-frame.html" -> WebBarrier.Kind.CAPTCHA
                    else -> null
                }
                return Check(block?.kind == expected, if (block?.kind != expected) {
                    "拦截判断 " + (block?.kind?.name ?: "无") +
                        "，预期 " + (expected?.name ?: "无") +
                        "，HTTP " + (tab.mainStatus?.toString() ?: "未知")
                } else "")
            }
            if (page == "cross-origin-frame.html" || page == "captcha-frame.html") {
                tab.waitUntil(1_500) {
                    tab.eval("window.__benchReady === true")?.jsonPrimitive?.booleanOrNull == true
                }
            }
            val s = snapshot(tab)
            val label = when (page) {
                "trusted-click.html" -> "可信点击"
                "controlled-input.html" -> "输入关键词"
                "enter-submit.html" -> "搜索内容"
                "cross-origin-frame.html" -> "跨站点击按钮"
                "closed-shadow.html" -> "Shadow 按钮"
                "listener-text.html" -> "监听器文字"
                else -> "被挡住的按钮"
            }
            val items = s.getValue("items").jsonArray.map { it.jsonObject }
            if (page == "covered-button.html") {
                if (!modern) {
                    val present = items.any { text(it, "label").contains(label) }
                    return Check(!present, if (present) "脚本把被遮挡按钮仍列为可点" else "")
                }
                val merged = fused(tab, s) ?: return Check(false, "ElementProbe.read 返回空")
                val present = merged.first.any { it.label.contains(label) }
                return Check(!present, if (present) "融合清单仍保留被透明遮罩挡住的按钮" else "")
            }

            if (!modern || page in setOf("trusted-click.html", "controlled-input.html", "enter-submit.html")) {
                val item = items.firstOrNull { text(it, "label").contains(label) }
                    ?: return Check(false, "脚本编号清单没有 " + label)
                val n = item.getValue("n").jsonPrimitive.int
                val typed = "测试中文 abc"
                val query = "北京机票"
                if (!modern) {
                    val op = if (page == "trusted-click.html") "click" else "type"
                    actOld(tab, n, op, if (page == "controlled-input.html") typed else query, true)
                    if (page == "enter-submit.html") actOld(tab, n, "key", "enter", false)
                } else {
                    val center = tab.json("window.__jack.center(" + n + ")")
                    val x = center.getValue("x").jsonPrimitive.double
                    val y = center.getValue("y").jsonPrimitive.double
                    val input = sender(tab)
                    input.act(x, y, if (page == "trusted-click.html") "click" else "type",
                        if (page == "controlled-input.html") typed else query, true)
                    if (page == "enter-submit.html") input.act(x, y, "key", "enter")
                }
                val expected = when (page) {
                    "trusted-click.html" -> "clicked"
                    "controlled-input.html" -> typed
                    else -> query
                }
                val ok = tab.waitUntil(500) { tab.string("window.__benchResult") == expected }
                return Check(ok, if (ok) "" else "输入或点击后未达到预期状态 " + expected)
            }

            if (!modern) {
                val item = items.firstOrNull { text(it, "label").contains(label) }
                    ?: return Check(false, "旧脚本看不到 " + label)
                actOld(tab, item.getValue("n").jsonPrimitive.int, "click", "", false)
                val ok = tab.waitUntil(500) { tab.string("window.__benchResult") == "clicked" }
                return Check(ok, if (ok) "" else "旧脚本点击未触发目标")
            }
            val merged = fused(tab, s) ?: return Check(false, "ElementProbe.read 返回空")
            val item = merged.first.firstOrNull { it.oldN == null && it.label.contains(label) }
                ?: return Check(false, "融合后没有新增 " + label +
                    "；" + merged.second.stats + "；frames=" + merged.second.frames.size +
                    "；相关AX=" + merged.second.ax.filter { it.name.contains("跨站") }.map { it.name } +
                    "；iframeReady=" + tab.string("String(window.__benchReady)") +
                    "；targets=" + runCatching {
                        val infos = tab.session.send("Target.getTargets", timeoutMs = 2_000)["targetInfos"] as? JsonArray
                        infos.orEmpty().map { it.jsonObject }.filter {
                            text(it, "url").contains("localhost") || text(it, "type") == "iframe"
                        }.joinToString(",") {
                            text(it, "type") + "@" + text(it, "url").take(70) + "[" + text(it, "targetId") + "]"
                        }.take(700)
                    }.getOrDefault("无法读取"))
            val extra = merged.second.extras.firstOrNull { it.backendId == item.backendId }
                ?: return Check(false, "新增节点缺少 backendNodeId")
            val viewport = viewport(s)
            val placed = ElementProbe(tab.session).locate(extra.backendId, extra.ownerId, viewport, extra.frameId, extra.hits)
                ?: return Check(false, "ElementProbe.locate 无法定位")
            if (placed.hit !in extra.hits && placed.hit != extra.ownerId)
                return Check(false, "定位点被其他节点挡住")
            sender(tab).act(placed.x, placed.y, "click")
            // 点击要跨进程送进 iframe，再由 postMessage 传回来；机器忙的时候（CI）700 毫秒不够，会误报「未触发」
            val ok = tab.waitUntil(4_000) { tab.string("window.__benchResult") == "clicked" }
            return Check(ok, if (ok) "" else "融合节点定位后点击未触发事件")
        } finally {
            tab.close()
        }
    }

    private suspend fun snapshot(tab: BenchTab): JsonObject =
        tab.json(script + "\nwindow.__jack.snapshot()")

    private suspend fun actOld(tab: BenchTab, n: Int, op: String, arg: String, clear: Boolean) {
        tab.eval("window.__jack.act(" + n + "," + JsonPrimitive(op) + "," +
            JsonPrimitive(arg) + "," + clear + ")")
    }

    private fun sender(tab: BenchTab) = CdpInputScript(send = { method, params ->
        tab.session.send(method, params, timeoutMs = 2_000)
    })

    private fun box(o: JsonObject): ElementFusion.Box {
        val x = o.getValue("x").jsonPrimitive.double
        val y = o.getValue("y").jsonPrimitive.double
        return ElementFusion.Box(x, y, x + o.getValue("w").jsonPrimitive.double,
            y + o.getValue("h").jsonPrimitive.double)
    }

    private fun viewport(s: JsonObject): ElementFusion.Box {
        val x = s.getValue("vx").jsonPrimitive.double
        val y = s.getValue("vy").jsonPrimitive.double
        return ElementFusion.Box(x, y, x + s.getValue("vw").jsonPrimitive.double,
            y + s.getValue("vh").jsonPrimitive.double)
    }

    private suspend fun fused(tab: BenchTab, s: JsonObject):
        Pair<List<ElementFusion.Merged>, ElementProbe.Snapshot>? {
        val page = s.getValue("items").jsonArray.map {
            val it = it.jsonObject
            ElementFusion.PageNode(it.getValue("n").jsonPrimitive.int, text(it, "kind"),
                text(it, "label"), box(it))
        }
        val hidden = (s["hidden"] as? JsonArray).orEmpty().map { box(it.jsonObject) }
        val listeners = (s["probes"] as? JsonArray).orEmpty().map {
            ElementProbe.Listener(text(it.jsonObject, "label"), box(it.jsonObject))
        }
        val view = viewport(s)
        val found = ElementProbe(tab.session).read(page, hidden, listeners, view) ?: return null
        val visible = page.withIndex().filter { it.value.n !in found.coveredPageNumbers }
        val merged = ElementFusion.fuse(visible.map { it.value },
            visible.map { found.pageIds[it.index] }, found.ax, found.extras, view)
        return merged.elements to found
    }

    private fun text(o: JsonObject, name: String) = o[name]?.jsonPrimitive?.content.orEmpty()

    private fun rows(table: JsonObject): Int {
        val tables = (table["tables"] as? JsonArray).orEmpty()
        val lists = (table["lists"] as? JsonArray).orEmpty()
        return tables.sumOf { (it.jsonObject["rows"] as? JsonArray)?.size ?: 0 } +
            lists.sumOf { (it.jsonObject["rows"] as? JsonArray)?.size ?: 0 }
    }

    companion object {
        private var chrome: BenchChrome? = null
        private var server: BenchServer? = null
        private val script: String by lazy {
            val local = File("src/main/assets/jack_browser.js")
            (if (local.isFile) local else File("app/src/main/assets/jack_browser.js")).readText()
        }

        @BeforeClass @JvmStatic fun start() {
            val launched = BenchChrome.startOrNull()
            Assume.assumeTrue("没有安装 Chromium，跳过真浏览器测试", launched != null)
            chrome = launched
            server = BenchServer()
        }

        @AfterClass @JvmStatic fun stop() {
            server?.close()
            chrome?.close()
        }
    }
}
