package com.jack.ai.a11y

import com.wangzan101.mika.core.uistate.RefElement
import com.wangzan101.mika.core.uistate.RefSnapshot
import com.wangzan101.mika.core.uistate.RefStabilizer
import com.wangzan101.mika.core.uistate.TreeAdequacy
import com.wangzan101.mika.core.uistate.TreeCheck
import com.wangzan101.mika.core.uistate.TreeNode

/** Kavi 控件树到 Mika 纯逻辑模块的薄适配层。 */
internal object PhoneScreenAnalysis {
    fun quality(root: UiNode, size: Pair<Int, Int>): TreeCheck =
        TreeAdequacy.ofTree(root.asTreeNode(), size.first, size.second)

    fun withFrame(quality: TreeCheck, black: Boolean): TreeCheck = TreeAdequacy.withFrame(quality, black)

    fun stabilize(previous: Screen?, current: Screen, size: Pair<Int, Int>): Screen {
        fun Screen.asRefs(): RefSnapshot {
            val lists = elements.filter { it.scrollable }
            var context = ""
            val entries = elements.map { e ->
                RefElement(
                    ref = e.n, role = e.role, name = e.label,
                    bounds = e.bounds.toList(), path = e.path, editable = e.editable,
                    inList = lists.any { list -> list !== e && list.contains(e.centerX, e.centerY) },
                    previousName = context,
                ).also { if (e.label.isNotBlank() && e.role != "列表") context = e.label }
            }
            return RefSnapshot(pkg, entries, nextRef)
        }

        val refs = RefStabilizer.next(previous?.asRefs(), current.asRefs(), size.first, size.second)
        return current.copy(
            elements = current.elements.zip(refs.elements) { e, ref -> e.copy(n = ref.ref) },
            nextRef = refs.nextRef,
        )
    }

    private fun UiNode.asTreeNode(): TreeNode = TreeNode(
        bounds = bounds.toList(), text = if (password) "" else text,
        description = if (password) "" else desc, hint = if (password) "" else hint,
        clickable = clickable || longClickable, editable = editable, password = password,
        children = children.map { it.asTreeNode() },
    )
}
