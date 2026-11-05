/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: use small phone-tree inputs instead of Mika's ScreenSnapshot/UiTree.
 */
package com.wangzan101.mika.core.uistate

/** 这一屏无障碍树能提供多少可用信息。 */
internal enum class TreeQuality {
    Adequate, Partial, Unusable, Protected
}

internal data class TreeCheck(val quality: TreeQuality, val reason: String? = null) {
    val poor: Boolean get() = quality == TreeQuality.Partial || quality == TreeQuality.Unusable
}

/** Jack AI modification: Kavi 无障碍树的最小纯逻辑快照。 */
internal data class TreeNode(
    val bounds: List<Int>,
    val text: String = "",
    val description: String = "",
    val hint: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val children: List<TreeNode> = emptyList(),
) {
    val named: Boolean get() = !password && (text.isNotBlank() || description.isNotBlank() || hint.isNotBlank())
}

/**
 * 移植 Mika 的 TreeAdequacy：控件无名字、文字过少或大块无文字区域时补截图。
 * 没有 OCR 时保守地判 Partial，不让少量文字掩盖一大块自绘区域。
 */
internal object TreeAdequacy {
    private const val ENOUGH_NAMED = 6
    private const val BLIND_AREA = 0.20
    private const val NAMED_INSIDE = 3

    /** 图像全黑时覆盖树质量：需要由用户接管。 */
    fun withFrame(tree: TreeCheck, black: Boolean): TreeCheck =
        if (black) TreeCheck(TreeQuality.Protected, "black_frame") else tree

    fun ofTree(root: TreeNode, width: Int, height: Int): TreeCheck {
        val nodes = mutableListOf<TreeNode>()
        fun walk(n: TreeNode) {
            if (area(n.bounds) > 0) nodes += n
            n.children.forEach(::walk)
        }
        walk(root)
        val named = nodes.filter { it.named }
        val controls = nodes.filter { it.clickable || it.editable }
        val namedControls = controls.count { c ->
            c.named || c.children.any { containsName(it) }
        }
        if (controls.isNotEmpty() && (namedControls == 0 || controls.size >= 4 && namedControls * 3 <= controls.size))
            return TreeCheck(if (named.isEmpty()) TreeQuality.Unusable else TreeQuality.Partial, "unlabeled_controls")
        if (named.isEmpty()) return TreeCheck(TreeQuality.Unusable, "no_text")
        if (blindArea(root, named, width, height)) return TreeCheck(TreeQuality.Partial, "blind_area")
        // 简单但完整的页面可以只有几个按钮；控件都有名字时不平白附图。
        if (named.size < ENOUGH_NAMED && named.size < 3 && (controls.isEmpty() || namedControls < 2))
            return TreeCheck(TreeQuality.Partial, "sparse_tree")
        return TreeCheck(TreeQuality.Adequate)
    }

    private fun containsName(n: TreeNode): Boolean = n.named || n.children.any(::containsName)

    private fun blindArea(root: TreeNode, named: List<TreeNode>, width: Int, height: Int): Boolean {
        val screenArea = width.toLong() * height
        if (screenArea <= 0) return false
        var found = false
        fun visit(node: TreeNode): Boolean {
            var text = node.named
            for (child in node.children) if (visit(child)) text = true
            if (!text && !found && area(node.bounds) >= screenArea * BLIND_AREA &&
                named.count { inside(it.bounds, node.bounds) } < NAMED_INSIDE) found = true
            return text
        }
        visit(root)
        return found
    }

    private fun area(b: List<Int>): Long =
        if (b.size != 4) 0 else (b[2] - b[0]).coerceAtLeast(0).toLong() * (b[3] - b[1]).coerceAtLeast(0)

    private fun inside(inner: List<Int>, outer: List<Int>): Boolean {
        if (inner.size != 4 || outer.size != 4) return false
        val x = (inner[0] + inner[2]) / 2
        val y = (inner[1] + inner[3]) / 2
        return x in outer[0]..outer[2] && y in outer[1]..outer[3]
    }
}
