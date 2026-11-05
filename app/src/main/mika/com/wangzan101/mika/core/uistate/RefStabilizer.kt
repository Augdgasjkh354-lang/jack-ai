/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: retain stable numeric refs while keeping Kavi's original node paths for actions.
 */
package com.wangzan101.mika.core.uistate

import kotlin.math.hypot
import kotlin.math.min

/** Jack AI modification: Kavi 清单的一项。编号仅在工具内部换算，path 仍是当前控件树的。 */
internal data class RefElement(
    val ref: Int,
    val role: String,
    val name: String,
    val bounds: List<Int>,
    val path: List<Int>,
    val editable: Boolean = false,
    val inList: Boolean = false,
    val previousName: String = "",
) {
    val x: Int get() = (bounds[0] + bounds[2]) / 2
    val y: Int get() = (bounds[1] + bounds[3]) / 2
}

internal data class RefSnapshot(
    val packageName: String,
    val elements: List<RefElement>,
    val nextRef: Int = 1,
)

/** 从 Mika RefStabilizer 裁剪：同一 App、可唯一辨认且位置未明显变化的控件沿用编号。 */
internal object RefStabilizer {
    fun next(previous: RefSnapshot?, current: RefSnapshot, width: Int, height: Int): RefSnapshot {
        val old = previous?.takeIf { it.packageName == current.packageName }
        val previousByKey = old?.elements?.groupBy(::key).orEmpty()
        val newCount = current.elements.groupingBy(::key).eachCount()
        val shiftLimit = min(width, height) * 0.05
        // 仅界面完全静止时，才沿用列表里重复文字控件的编号。
        val unchanged = old != null && old.elements.size == current.elements.size && old.elements.zip(current.elements).all { (p, e) ->
            p.role == e.role && p.name == e.name && p.bounds == e.bounds && p.path == e.path
        }
        var nextRef = old?.nextRef ?: 1
        val resolved = current.elements.map { e ->
            val matches = previousByKey[key(e)]
            val candidate = if (matches?.size == 1 && newCount[key(e)] == 1) matches.single() else
                matches?.singleOrNull { it.path == e.path && it.bounds == e.bounds && it.previousName == e.previousName }
                    ?.takeIf { !(it.inList || e.inList) || unchanged }
            val former = candidate?.takeIf { p ->
                (identifiable(e) || unchanged && e.path == p.path && e.bounds == p.bounds) &&
                    hypot((e.x - p.x).toDouble(), (e.y - p.y).toDouble()) <= shiftLimit &&
                    // 列表会复用同一位置的控件：还要上下文一致、控件原地不动。
                    (!(p.inList || e.inList) || (e.previousName == p.previousName &&
                        e.x == p.x && e.y == p.y))
            }
            e.copy(ref = former?.ref ?: nextRef++)
        }
        return RefSnapshot(current.packageName, resolved, nextRef)
    }

    private fun identifiable(e: RefElement): Boolean =
        e.name.isNotBlank() || e.editable

    private fun key(e: RefElement): String = e.role + "\u0000" + e.name
}
