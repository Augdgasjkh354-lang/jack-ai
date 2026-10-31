package com.jack.ai.a11y

/**
 * 把一屏界面（无障碍给的控件树）变成给 Kavi 看的清单：[编号] 类型「文字」，和浏览器里操作网页一样按编号点。
 * 纯数据和纯函数，单测；从系统的控件树拷成 UiNode 在 KaviAccessibility 里做。
 */

/** 一个控件（从 AccessibilityNodeInfo 拷出来的，不留系统对象）。bounds 是屏幕上的 左、上、右、下。 */
data class UiNode(
    val cls: String = "",
    val text: String = "",
    val desc: String = "",
    val hint: String = "",
    val bounds: IntArray = IntArray(4),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val children: List<UiNode> = emptyList(),
)

/** 清单里的一项。path 是从根往下每层第几个孩子，操作时照着它在新的控件树里找回这个控件。 */
data class Element(
    val n: Int,
    val role: String,
    val label: String,
    val bounds: IntArray,
    val path: List<Int>,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val checked: Boolean?,
    val enabled: Boolean,
    /** 输入框里现在的字（密码框是空的）。 */
    val value: String = "",
) {
    val centerX get() = (bounds[0] + bounds[2]) / 2
    val centerY get() = (bounds[1] + bounds[3]) / 2
    private val area get() = (bounds[2] - bounds[0]).toLong() * (bounds[3] - bounds[1])
    fun contains(x: Int, y: Int) = x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3]
    internal fun areaOf() = area
}

/** 读到的一屏：哪个 App、清单、读的时间。 */
data class Screen(val pkg: String, val app: String, val elements: List<Element>, val at: Long = System.currentTimeMillis()) {
    /** 屏幕上所有的字（查付款页面用）。 */
    fun texts(): List<String> = elements.map { it.label }
    /** 这一点上最小的那个控件（按坐标点时看它是什么）。 */
    fun at(x: Int, y: Int): Element? = elements.filter { it.contains(x, y) && it.role != "列表" }.minByOrNull { it.areaOf() }
    fun byNumber(n: Int): Element? = elements.firstOrNull { it.n == n }
    /** 按文字找：先找完全一样的，再找包含的；能点的优先。 */
    fun byText(t: String): Element? {
        val q = t.trim()
        if (q.isEmpty()) return null
        val all = elements.filter { it.role != "列表" }
        return all.filter { it.label == q }.minByOrNull { if (it.clickable || it.editable) 0 else 1 }
            ?: all.filter { q in it.label }.minWithOrNull(compareBy<Element>({ if (it.clickable || it.editable) 0 else 1 }, { it.label.length }))
    }
}

object ScreenText {
    const val MAX_ELEMENTS = 160
    private const val MERGED_MAX = 60

    /** 控件树 → 清单。能点、能输入、能滑动的单独成项；能点但自己没字的，把里面的字合起来当它的名字（里面的字就不再单列）。 */
    fun elements(root: UiNode): List<Element> {
        val out = ArrayList<Element>()
        fun add(node: UiNode, path: List<Int>, role: String, label: String, value: String = "") {
            if (out.size >= MAX_ELEMENTS) return
            out += Element(out.size + 1, role, label, node.bounds, path, node.clickable || node.longClickable, node.editable, node.scrollable,
                node.password, if (node.checkable) node.checked else null, node.enabled, value)
        }
        fun visit(node: UiNode, path: List<Int>) {
            if (out.size >= MAX_ELEMENTS) return
            val b = node.bounds
            if (b[2] <= b[0] || b[3] <= b[1]) return
            val own = node.text.trim().ifEmpty { node.desc.trim() }
            when {
                node.editable -> {
                    // 密码框一律不读
                    val value = if (node.password) "" else node.text.trim()
                    val label = if (node.password) "密码框（不读）" else node.desc.trim().ifEmpty { node.hint.trim() }
                    add(node, path, "输入框", label, value)
                    return
                }
                node.scrollable -> add(node, path, "列表", own)
                node.checkable && (node.clickable || node.longClickable) -> add(node, path, "开关", own.ifEmpty { merged(node) })
                node.clickable || node.longClickable -> {
                    val label = own.ifEmpty { merged(node) }
                    add(node, path, if (node.cls.endsWith("ImageView") || node.cls.endsWith("ImageButton")) "图标" else "按钮", label)
                    // 自己没字、字在里面的：里面的字已经合进来了，不再单列（里面另有能点的照样列）
                    if (own.isEmpty()) {
                        node.children.forEachIndexed { i, c -> visitActionableOnly(c, path + i, ::add) }
                        return
                    }
                }
                own.isNotEmpty() -> add(node, path, "文字", own)
            }
            node.children.forEachIndexed { i, c -> visit(c, path + i) }
        }
        visit(root, emptyList())
        // 连着两项一模一样的字（同一个字被好几层控件报了两遍）只留一个
        return out.filterIndexed { i, e -> i == 0 || !(e.role == "文字" && out[i - 1].label == e.label) }
            .mapIndexed { i, e -> e.copy(n = i + 1) }
    }

    /** 合并进按钮名字的那部分里，还有能单独点、能输入的：照样列出来。 */
    private fun visitActionableOnly(node: UiNode, path: List<Int>, add: (UiNode, List<Int>, String, String, String) -> Unit) {
        val own = node.text.trim().ifEmpty { node.desc.trim() }
        when {
            node.editable -> add(node, path, "输入框", if (node.password) "密码框（不读）" else node.desc.trim().ifEmpty { node.hint.trim() }, if (node.password) "" else node.text.trim())
            node.clickable || node.longClickable -> add(node, path, if (node.checkable) "开关" else "按钮", own.ifEmpty { merged(node) }, "")
        }
        node.children.forEachIndexed { i, c -> visitActionableOnly(c, path + i, add) }
    }

    /** 一个控件里面所有的字，连成一句（太长截断）。密码框的字不算。 */
    fun merged(node: UiNode): String {
        val parts = ArrayList<String>()
        fun walk(n: UiNode) {
            if (n.password) return
            val t = n.text.trim().ifEmpty { n.desc.trim() }
            if (t.isNotEmpty() && parts.lastOrNull() != t) parts += t
            n.children.forEach(::walk)
        }
        node.children.forEach(::walk)
        val s = parts.joinToString(" ")
        return if (s.length > MERGED_MAX) s.take(MERGED_MAX) + "…" else s
    }

    /** 给 Kavi 看的一屏。 */
    fun format(s: Screen, maxChars: Int = 6000): String {
        val sb = StringBuilder("【手机屏幕】${s.app}（${s.pkg}）\n")
        var shown = 0
        for (e in s.elements) {
            val line = buildString {
                append("[${e.n}] ${e.role}")
                if (e.label.isNotEmpty()) append("「${e.label.replace('\n', ' ').take(80)}」")
                if (e.role == "输入框" && !e.password) append(if (e.value.isEmpty()) "（空）" else "：${e.value.replace('\n', ' ').take(120)}")
                e.checked?.let { append(if (it) " ·开" else " ·关") }
                if (!e.enabled) append(" ·点不了")
            }
            if (sb.length + line.length > maxChars) break
            sb.append(line).append('\n')
            shown++
        }
        if (shown < s.elements.size) sb.append("……还有 ${s.elements.size - shown} 项没列出：要的东西不在上面就 scroll 往下翻，或用 tap 的 text 按文字点。\n")
        if (s.elements.isEmpty()) sb.append("（这一屏读不到文字和按钮，可能是游戏、视频或自己画的界面；能看图的话用 screenshot 看，再按坐标 tap）\n")
        return sb.toString()
    }
}
