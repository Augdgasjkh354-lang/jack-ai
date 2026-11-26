package com.jack.ai.a11y

import android.content.Context
import com.wangzan101.mika.core.screen.map.ScreenGraph
import com.wangzan101.mika.core.screen.map.ScreenGraphCodec
import com.wangzan101.mika.core.screen.map.ScreenMapBuilder
import com.wangzan101.mika.core.screen.map.ScreenMapGrower
import com.wangzan101.mika.core.screen.map.ScreenMapRender
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import com.wangzan101.mika.core.screen.map.ScreenMove
import com.wangzan101.mika.core.screen.map.ScreenRecognizer
import java.io.File

/**
 * 屏图（移植自 Mika 的 screen map，Apache-2.0，见 third-party/mika/README.md）：每个 App 一张「页面结构图」，
 * 节点是页面（按固定骨架文字认：标题栏、标签、按钮，不认列表内容和输入框里的字），边是「在这页点了什么 → 到了哪页」。
 * 操作手机时记下每一步的前后两屏，这一轮结束时并进图里；以后第一次读到这个 App 时，随 App 备忘一起把地图给模型，
 * 重放手机流程时每一步核对落到的页面对不对。读写失败等于没有图，任务照旧。
 */
object PhoneScreenMaps {
    /** 骨架文字最多取几个。 */
    private const val MAX_TEXTS = 12
    private const val MAX_LEN = 12
    /** 数字、时间、金额每次都可能变，不当骨架。 */
    private val VOLATILE = Regex("[0-9０-９¥￥$€£%]")
    /** 一个 App 最多留多少页、每页最多留多少条路（按来过的次数、走过的次数留多的）。 */
    private const val MAX_NODES = 150
    private const val MAX_EDGES = 30

    fun stable(text: String): Boolean {
        val t = text.trim()
        return t.length in 1..MAX_LEN && !VOLATILE.containsMatchIn(t)
    }

    /**
     * 一屏的骨架文字：按屏上顺序去重。列表里的条目是内容（联系人、消息、商品），输入框里是用户写的字，密码框不碰，
     * 都不算——只取页面的固定框架。思路同 Mika 的 RouteAnchors.texts。
     */
    fun skeleton(s: Screen): List<String> {
        val lists = s.elements.filter { it.role == "列表" }
        fun inside(e: Element, c: Element) = e !== c && e.bounds[0] >= c.bounds[0] && e.bounds[1] >= c.bounds[1] &&
            e.bounds[2] <= c.bounds[2] && e.bounds[3] <= c.bounds[3]
        return s.elements.asSequence()
            .filter { e -> e.role != "列表" && !e.editable && !e.password && lists.none { inside(e, it) } && stable(e.label) }
            .map { it.label.trim() }.distinct().take(MAX_TEXTS).toList()
    }

    /** 屏图存在 files/screenmaps/<包名>.json（可写；App 自带的使用知识在资源里，只读）。 */
    class FileStore(dir: File) : ScreenMapStore {
        private val root = File(dir, "screenmaps")
        private fun file(app: String) = File(root, app.replace(Regex("[^\\w.-]"), "_") + ".json")
        @Synchronized override fun load(app: String): ScreenGraph? =
            runCatching { file(app).takeIf { it.exists() }?.readText()?.let(ScreenGraphCodec::decode) }.getOrNull()?.takeIf { it.app == app }
        @Synchronized override fun save(app: String, graph: ScreenGraph) {
            root.mkdirs()
            val f = file(app)
            val tmp = File(root, f.name + ".tmp")
            tmp.writeText(ScreenGraphCodec.encode(trim(graph)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        override fun apps(): List<String> = root.listFiles().orEmpty().filter { it.extension == "json" }.map { it.nameWithoutExtension }
    }

    /** 图长得太大时只留常去的页、常走的路；指向被删的页的路一起去掉。 */
    internal fun trim(g: ScreenGraph): ScreenGraph {
        val keep = g.nodes.values.sortedWith(compareByDescending<com.wangzan101.mika.core.screen.map.ScreenNode> { it.visits }.thenBy { it.id })
            .take(MAX_NODES).map { it.id }.toSet()
        return ScreenGraph(g.app, g.nodes.filterKeys { it in keep }.mapValues { (_, n) ->
            n.copy(transitions = n.transitions.filterValues { it.to == null || it.to in keep }.entries
                .sortedWith(compareByDescending<Map.Entry<String, com.wangzan101.mika.core.screen.map.Transition>> { it.value.count }.thenBy { it.key })
                .take(MAX_EDGES).associate { it.key to it.value })
        })
    }

    @Volatile private var store: FileStore? = null
    fun store(ctx: Context): FileStore = store ?: synchronized(this) { store ?: FileStore(ctx.applicationContext.filesDir).also { store = it } }

    /** 这一轮记下的动作并进图里（回答结束时调；出错只吞掉，不影响任务）。 */
    fun grow(ctx: Context, moves: List<ScreenMove>) {
        if (moves.isEmpty()) return
        runCatching {
            val s = store(ctx)
            val apps = moves.flatMap { listOfNotNull(it.beforeApp, it.afterApp) }.distinct()
            val base = apps.mapNotNull { a -> s.load(a)?.let { a to it } }.toMap()
            ScreenMapGrower.grow(s, ScreenMapBuilder().apply(moves, base))
        }
    }

    /** 进入 App 时给模型的地图（没图、太小就是空的）：走过的页面 + 现在在哪 + 从这一页走过哪些路。 */
    fun hint(ctx: Context, s: Screen): String {
        val g = runCatching { store(ctx).load(s.pkg) }.getOrNull() ?: return ""
        if (g.nodes.size < 2) return ""
        val map = ScreenMapRender.renderMapFor(g, skeleton(s).toSet()) ?: return ""
        return "【这个 App 的页面地图（Kavi 以前在这里走过的路，按页面上固定的字认，界面改版了可能对不上）】\n$map\n"
    }

    /** 认这一屏是图里哪一页（认不出 null）。 */
    fun identify(ctx: Context, s: Screen): String? {
        val g = runCatching { store(ctx).load(s.pkg) }.getOrNull() ?: return null
        return ScreenRecognizer.identify(skeleton(s).toSet(), g.nodes())?.id
    }

    /**
     * 重放时核对：这一步录下时落到的页面（骨架文字 [expected]）和现在落到的 [now] 是不是同一页。
     * 图里认不出录下的那页时不判（返回 null，照旧往下走）；认得出却不是同一页，返回说明和从当前页出发的地图。
     */
    fun mismatch(ctx: Context, expected: List<String>, now: Screen): String? {
        if (expected.isEmpty()) return null
        val g = runCatching { store(ctx).load(now.pkg) }.getOrNull() ?: return null
        val want = ScreenRecognizer.identify(expected.toSet(), g.nodes()) ?: return null
        val texts = skeleton(now).toSet()
        val landed = ScreenRecognizer.identify(texts, g.nodes())?.id
        if (landed == want.id) return null
        // 认不出落到的是哪页（可能是那页多了个弹窗、横幅）：录下的字一半以上还在就当走对了，宁可往下走也不误停
        if (landed == null && expected.count { it in texts } * 2 >= expected.size) return null
        val wantLabel = g.nodes[want.id]?.let(ScreenMapRender::label).orEmpty()
        val where = ScreenMapRender.renderPosition(g, landed).ifEmpty { "落到的这一页图里没见过" }
        return "落到的页面不对：本该到「$wantLabel」，$where。\n" + ScreenMapRender.renderMap(g, landed)
    }
}
