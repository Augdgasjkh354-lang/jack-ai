/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: Adapted to Kavi's phone tools without Mika's Agent/UI dependencies.
 */
package com.wangzan101.mika.core.uistate

/** Jack AI modification: 从 Mika ActionEffect 裁剪出 Kavi 控件清单可提供的纯逻辑输入。 */
data class EffectElement(
    val role: String,
    val label: String,
    val value: String,
    val bounds: List<Int>,
    val checked: Boolean?,
    val password: Boolean = false,
)

data class EffectScreen(val packageName: String, val elements: List<EffectElement>)

enum class EffectKind { CHANGED, NO_EFFECT, UNCERTAIN }

/** 根据动作前后的控件内容和位置判断效果；控件编号变化不算效果。 */
object ActionEffect {
    fun classify(before: EffectScreen?, after: EffectScreen?, typed: String? = null): EffectKind {
        if (before == null || after == null) return EffectKind.UNCERTAIN
        if (before.packageName != after.packageName) return EffectKind.CHANGED
        if (typed != null && after.elements.any { it.role == "输入框" && !it.password && it.value == typed }) return EffectKind.CHANGED
        val old = meaningful(before)
        val now = meaningful(after)
        if (old.isEmpty() || now.isEmpty()) return EffectKind.UNCERTAIN
        if (old == now) return if (old.size >= 3) EffectKind.NO_EFFECT else EffectKind.UNCERTAIN

        // 输入字、开关变化哪怕只有一处，也能确定操作生效。
        val prior = before.elements.filterNot { it.password }
        val later = after.elements.filterNot { it.password }
        if (prior.any { a -> later.any { b ->
                a.role == b.role && a.bounds == b.bounds &&
                    (a.value != b.value && a.role == "输入框" || a.checked != b.checked && a.checked != null && b.checked != null)
            } }) return EffectKind.CHANGED

        // 多处控件增加、消失或移动才报明显变化；一处局部变化归为拿不准。
        val differences = (old.groupingBy { it }.eachCount().keys + now.groupingBy { it }.eachCount().keys)
            .sumOf { key -> kotlin.math.abs(old.count { it == key } - now.count { it == key }) }
        return if (differences >= 3) EffectKind.CHANGED else EffectKind.UNCERTAIN
    }

    private fun meaningful(screen: EffectScreen): List<String> = screen.elements
        .filter { !it.password && (it.label.isNotBlank() || it.value.isNotBlank() || it.checked != null) }
        .map { e -> listOf(e.role, e.label, e.value, e.bounds.joinToString(","), e.checked?.toString().orEmpty()).joinToString("|") }
        .sorted()
}
