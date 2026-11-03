// Copyright 2026 wangzan101. Licensed under Apache-2.0.
// Ported from Mika 0091d2d (ElementProbe.kt).
// Jack AI modification: read Kavi's node tables and expose only verified, visible controls.
package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.cap.web.ElementFusion.AxNode
import com.wangzan101.mika.core.cap.web.ElementFusion.Box
import com.wangzan101.mika.core.cap.web.ElementFusion.Extra
import com.wangzan101.mika.core.cap.web.ElementFusion.Frame
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 用 DOM.describeNode 取得脚本节点 backendNodeId，再按帧读无障碍树。
 * 候选元素必须经过框位置和命中测试；整次探测失败由宿主退回原页面清单。
 */
class ElementProbe(private val session: CdpSession) {
    data class Snapshot(
        val pageIds: List<Int?>, val ax: List<AxNode>, val frames: List<Frame>,
        val extras: List<Extra>, val stats: String,
    )
    data class Listener(val label: String, val box: Box)
    data class Placed(val box: Box, val x: Double, val y: Double, val hit: Int?)
    private data class Ref(val objectId: String?, val backendId: Int?, val frameId: String?)
    private class Skip(message: String) : Exception(message)

    suspend fun read(page: List<ElementFusion.PageNode>, frames: List<Box>,
                     listenerNodes: List<Listener>, viewport: Box): Snapshot? {
        return try {
            withTimeout(TOTAL_MS) { collect(page, frames, listenerNodes, viewport) }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(200) {
                    runCatching { session.send("Runtime.releaseObjectGroup",
                        buildJsonObject { put("objectGroup", GROUP) }, timeoutMs = 200) }
                }
            }
        }
    }

    private suspend fun collect(page: List<ElementFusion.PageNode>, hidden: List<Box>,
                                listenerNodes: List<Listener>, viewport: Box): Snapshot = coroutineScope {
        val pageRefs = async { refs("window.__jackRefs || []", page.size) }
        val hiddenRefs = async { refs("window.__jackFrames || []", hidden.size) }
        val probeRefs = async { refs("window.__jackListeners || []", listenerNodes.size) }
        val frames = async { frameTree() }
        val pageIds = pageRefs.await().map { it.backendId }
        val frameList = frames.await()
        val directOwners = hiddenRefs.await().mapNotNull { ref ->
            if (ref.backendId != null && ref.frameId != null) ref.backendId to ref.frameId else null
        }.toMap()
        val ownerIds = frameList.drop(1).take(12).map { frame ->
            async { frame.id to frameOwner(frame.id) }
        }.awaitAll().filter { it.second != null }.associate { it.second!! to it.first } + directOwners
        val hiddenIds = hiddenRefs.await().map { it.backendId }
        val hiddenBoxes = hiddenIds.mapIndexedNotNull { i, id ->
            id?.let { it to hidden[i] }
        }.toMap()
        val ax = frameList.take(13).map { f -> async { axTree(f.id) } }.awaitAll().flatten()
        val wanted = ElementFusion.candidates(page, pageIds, ax, frameList, hiddenBoxes, ownerIds)
        val axExtras = wanted.map { c ->
            async {
                val id = c.node.backendId ?: return@async null
                val placed = place(id, ElementFusion.subtreeIds(ax, c.node), c.clip, c.ownerId, viewport)
                    ?: return@async null
                val details = details(id)
                Extra(
                    id, c.node.frameId, c.node.order,
                    if (details.second == "password") "密码框" else ElementFusion.kindFor(c.node.role) ?: return@async null,
                    c.node.name.replace(Regex("\\s+"), " ").trim().take(80),
                    placed.box, c.clip, c.ownerId, placed.hit, ElementFusion.subtreeIds(ax, c.node),
                    details.second, details.first,
                )
            }
        }.awaitAll().filterNotNull()
        val listenerExtras = probeRefs.await().take(40).mapIndexed { i, ref ->
            async {
                val id = ref.backendId ?: return@async null
                val objectId = ref.objectId ?: return@async null
                if (!listens(objectId)) return@async null
                val src = listenerNodes.getOrNull(i) ?: return@async null
                val placed = place(id, setOf(id), null, null, viewport) ?: return@async null
                Extra(id, "", i, "可点", src.label.take(80),
                    placed.box, null, null, placed.hit, setOf(id))
            }
        }.awaitAll().filterNotNull()
        Snapshot(pageIds, ax, frameList, axExtras + listenerExtras,
            "script=${page.size}, matched=${pageIds.count { it != null }}, ax=${ax.size}, extras=${axExtras.size}, listeners=${listenerExtras.size}")
    }

    /** 同一次求值里的数组节点引用与页面快照对齐；页面跳转后长度不一致则放弃融合。 */
    private suspend fun refs(expression: String, expected: Int): List<Ref> = coroutineScope {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", expression)
            put("returnByValue", false)
            put("objectGroup", GROUP)
            put("silent", true)
        }, timeoutMs = CALL_MS)
        if (r["exceptionDetails"] != null) throw Skip("页面节点表失效")
        val array = (r["result"] as? JsonObject)?.str("objectId") ?: throw Skip("找不到节点表")
        val values = session.send("Runtime.getProperties", buildJsonObject {
            put("objectId", array); put("ownProperties", true)
        }, timeoutMs = CALL_MS)["result"] as? JsonArray ?: throw Skip("节点表不可读")
        val byIndex = values.mapNotNull { v ->
            val p = v as? JsonObject ?: return@mapNotNull null
            val i = p.str("name")?.toIntOrNull() ?: return@mapNotNull null
            i to ((p["value"] as? JsonObject)?.str("objectId"))
        }.toMap()
        if (byIndex.size != expected) throw Skip("页面节点表已经变化")
        (0 until expected).map { i ->
            async {
                val oid = byIndex[i] ?: return@async Ref(null, null, null)
                val node = runCatching {
                    session.send("DOM.describeNode", buildJsonObject { put("objectId", oid) }, timeoutMs = CALL_MS)["node"] as? JsonObject
                }.getOrNull()
                Ref(oid, node?.int("backendNodeId"), node?.str("frameId"))
            }
        }.awaitAll()
    }

    private suspend fun frameTree(): List<Frame> {
        val root = session.send("Page.getFrameTree", timeoutMs = CALL_MS)["frameTree"] as? JsonObject
            ?: throw Skip("框架树为空")
        return parseFrames(root)
    }

    private suspend fun frameOwner(frame: String): Int? = runCatching {
        session.send("DOM.getFrameOwner", buildJsonObject { put("frameId", frame) }, timeoutMs = CALL_MS).int("backendNodeId")
    }.getOrNull()

    private suspend fun axTree(frame: String): List<AxNode> = runCatching {
        val nodes = session.send("Accessibility.getFullAXTree",
            buildJsonObject { put("frameId", frame) }, timeoutMs = CALL_MS)["nodes"] as? JsonArray
        parseAx(nodes.orEmpty().mapNotNull { it as? JsonObject }, frame)
    }.getOrDefault(emptyList())

    private suspend fun listens(oid: String): Boolean = runCatching {
        val a = session.send("DOMDebugger.getEventListeners",
            buildJsonObject { put("objectId", oid) }, timeoutMs = CALL_MS)["listeners"] as? JsonArray
        a.orEmpty().any { (it as? JsonObject)?.str("type") in POINTER_EVENTS }
    }.getOrDefault(false)

    private suspend fun details(backend: Int): Pair<String, String> {
        val node = runCatching {
            session.send("DOM.describeNode", buildJsonObject { put("backendNodeId", backend) }, timeoutMs = CALL_MS)["node"] as? JsonObject
        }.getOrNull() ?: return "" to ""
        val attrs = (node["attributes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val attrMap = attrs.chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
        return node.str("nodeName").orEmpty().lowercase() to attrMap["type"].orEmpty().lowercase()
    }

    private suspend fun box(backend: Int, content: Boolean = false): Box? = runCatching {
        val model = session.send("DOM.getBoxModel", buildJsonObject {
            put("backendNodeId", backend)
        }, timeoutMs = CALL_MS)["model"] as? JsonObject
        quad(model?.get(if (content) "content" else "border") as? JsonArray)
    }.getOrNull()

    suspend fun locate(backend: Int, owner: Int?, viewport: Box): Placed? =
        withTimeoutOrNull(1_500) {
            runCatching { session.send("DOM.scrollIntoViewIfNeeded",
                buildJsonObject { put("backendNodeId", backend) }, timeoutMs = 1_000) }
            // 滚动后重新取所属 iframe 的可见区域，旧快照的框可能已移位。
            place(backend, setOf(backend), owner?.let { box(it, content = true) }, owner, viewport)
        }

    /** 尝试顶层坐标；部分 WebView 把子框架的 BoxModel 坐标算在框架内，再加上框架偏移重试。 */
    private suspend fun place(backend: Int, hits: Set<Int>, clip: Box?, owner: Int?, viewport: Box): Placed? {
        val raw = box(backend) ?: return null
        suspend fun trial(rect: Box): Placed? {
            val visible = rect.intersect(viewport)?.let { clip?.let(it::intersect) ?: it } ?: return null
            val hit = hitAt(visible.x, visible.y)
            return Placed(rect, visible.x, visible.y, hit)
        }
        val direct = trial(raw)
        if (direct?.hit in hits || (owner != null && direct?.hit == owner)) return direct
        val frame = owner?.let { box(it, content = true) }
        if (frame != null) {
            val shifted = Box(raw.l + frame.l, raw.t + frame.t, raw.r + frame.l, raw.b + frame.t)
            val other = trial(shifted)
            if (other?.hit in hits || (owner != null && other?.hit == owner)) return other
        }
        return direct
    }

    private suspend fun hitAt(x: Double, y: Double): Int? = runCatching {
        session.send("DOM.getNodeForLocation", buildJsonObject {
            put("x", x.roundToInt()); put("y", y.roundToInt())
            put("includeUserAgentShadowDOM", false)
        }, timeoutMs = CALL_MS).int("backendNodeId")
    }.getOrNull()

    companion object {
        const val TOTAL_MS = 4_000L
        private const val CALL_MS = 4_500L
        private const val GROUP = "jack-elements"
        private val POINTER_EVENTS = setOf("click", "mousedown", "mouseup", "pointerdown", "pointerup", "touchstart", "touchend")

        fun parseFrames(tree: JsonObject): List<Frame> {
            val out = ArrayList<Frame>()
            fun walk(t: JsonObject, parent: String?, depth: Int) {
                if (depth > 32) return
                val frame = t["frame"] as? JsonObject ?: return
                val id = frame.str("id") ?: return
                out += Frame(id, frame.str("parentId") ?: parent)
                (t["childFrames"] as? JsonArray).orEmpty().forEach { c ->
                    (c as? JsonObject)?.let { walk(it, id, depth + 1) }
                }
            }
            walk(tree, null, 0)
            return out
        }

        fun parseAx(nodes: List<JsonObject>, frameId: String): List<AxNode> {
            val byId = nodes.mapNotNull { it.str("nodeId")?.let { k -> k to it } }.toMap()
            val parents = HashMap<String, String>()
            for ((id, node) in byId) {
                node.str("parentId")?.let { parents[id] = it }
                (node["childIds"] as? JsonArray).orEmpty().forEach { child ->
                    (child as? JsonPrimitive)?.contentOrNull?.let { parents.putIfAbsent(it, id) }
                }
            }
            val order = LinkedHashSet<String>()
            for (root in byId.keys.filter { parents[it] !in byId }) {
                val stack = ArrayDeque(listOf(root))
                while (stack.isNotEmpty()) {
                    val id = stack.removeLast()
                    if (!order.add(id)) continue
                    val kids = (byId[id]?.get("childIds") as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    kids.asReversed().forEach(stack::addLast)
                }
            }
            return order.mapIndexedNotNull { i, id ->
                val node = byId[id] ?: return@mapIndexedNotNull null
                val props = (node["properties"] as? JsonArray).orEmpty()
                val states = props.mapNotNull { prop ->
                    val p = prop as? JsonObject ?: return@mapNotNull null
                    val name = p.str("name") ?: return@mapNotNull null
                    val value = (p["value"] as? JsonObject)?.get("value") as? JsonPrimitive ?: return@mapNotNull null
                    val enabled = value.booleanOrNull ?: (value.contentOrNull !in setOf(null, "", "false", "undefined"))
                    name.takeIf { enabled }
                }.toSet()
                AxNode(id, parents[id], node.str("frameId") ?: frameId, i, node.int("backendDOMNodeId"),
                    (node["role"] as? JsonObject)?.str("value").orEmpty(),
                    (node["name"] as? JsonObject)?.str("value").orEmpty(),
                    (node["ignored"] as? JsonPrimitive)?.booleanOrNull == true, states)
            }
        }

        fun quad(points: JsonArray?): Box? {
            val v = points?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
            if (v.size < 8) return null
            return Box(min(min(v[0], v[2]), min(v[4], v[6])),
                min(min(v[1], v[3]), min(v[5], v[7])),
                max(max(v[0], v[2]), max(v[4], v[6])),
                max(max(v[1], v[3]), max(v[5], v[7])))
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    }
}
