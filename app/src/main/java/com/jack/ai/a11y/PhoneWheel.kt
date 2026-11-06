package com.jack.ai.a11y

/**
 * 滚轮（时间、日期、地区这类一格一格转的）和系统「用哪个 App 打开」弹窗的纯逻辑。
 * 思路来自 Mika 的 SetWheel / WheelMath 和装了两份 App 的选择器处理，Apache-2.0；做法按 Kavi 的控件清单重写。
 */
object PhoneWheel {
    /** 滚轮框里看得到、带字的几项，从上到下。 */
    fun items(s: Screen, box: IntArray): List<Element> = s.elements.filter { e ->
        e.role != "列表" && e.label.isNotBlank() && e.centerX in box[0] until box[2] && e.centerY in box[1] until box[3]
    }.sortedBy { it.centerY }

    /** 选中的那一项：离滚轮正中最近的。 */
    fun current(items: List<Element>, box: IntArray): Element? {
        val mid = (box[1] + box[3]) / 2
        return items.minByOrNull { kotlin.math.abs(it.centerY - mid) }
    }

    private fun num(s: String): Double? = Regex("-?\\d+(\\.\\d+)?").find(s)?.value?.toDoubleOrNull()

    /** 这一项是不是要的值：字一样，或者都是数、数相等（「09」和「9」、「9月」和「9」）。 */
    fun matches(label: String, want: String): Boolean {
        val a = label.trim(); val b = want.trim()
        if (a == b) return true
        val na = num(a); val nb = num(b)
        if (na != null && nb != null && na == nb) {
            // 数相等时，非数字的部分也要对得上（「9月」不是「9日」）
            val ra = a.replace(Regex("[\\d.\\-\\s]"), ""); val rb = b.replace(Regex("[\\d.\\-\\s]"), "")
            return rb.isEmpty() || ra.isEmpty() || ra == rb
        }
        return false
    }

    /**
     * 往哪边转：+1 往后（值变大、下面的项转上来），-1 往前；判断不了是 null。
     * 看得到目标就按位置；都是数就比大小。
     */
    fun direction(items: List<Element>, cur: Element?, want: String): Int? {
        if (cur == null) return null
        items.firstOrNull { matches(it.label, want) }?.let { return if (it.centerY > cur.centerY) 1 else -1 }
        val a = num(cur.label) ?: return null
        val b = num(want) ?: return null
        return if (b > a) 1 else if (b < a) -1 else null
    }

    private val CHOOSER_PKGS = setOf("android", "com.android.intentresolver", "com.android.internal.app", "com.huawei.android.internal.app")
    private val CHOOSER_WORDS = Regex("打开方式|使用以下方式打开|用其他应用打开|选择应用|选择要使用的应用|选择打开的应用|始终|仅此一次|只有一次|Open with|Complete action using|Choose an app|Just once|Always")
    private val CHOOSER_BUTTONS = setOf("仅此一次", "只有一次", "始终", "总是", "Just once", "Always", "取消", "确定")

    /** 系统在问「用哪个 App 打开」（或装了两份的 App 问打开哪一个）。 */
    fun isChooser(pkg: String, texts: List<String>): Boolean =
        (pkg in CHOOSER_PKGS || pkg.startsWith("com.android.internal")) && texts.any { CHOOSER_WORDS.containsMatchIn(it) }

    /** 选择器上能点的：用户勾过的 App（按名字）和「仅此一次」这类按钮。 */
    fun chooserTapOk(label: String, allowedLabels: List<String>): Boolean {
        val l = label.trim()
        return l in CHOOSER_BUTTONS || allowedLabels.any { it.isNotBlank() && (l.contains(it) || it.contains(l) && l.length >= 2) }
    }
}
