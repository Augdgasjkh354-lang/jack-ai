package com.jack.ai.data.workflow

/**
 * 卡片编辑器对步骤树的改动：都按步骤 id 找（id 在整个工作流里唯一），返回新的树，不改原来的。
 * 「放在哪」用 [Slot]：哪个步骤里面（null = 最外层）、哪一支（then / else / steps）、第几个。
 */
object WfEdit {
    data class Slot(val parent: String?, val branch: String = "root", val index: Int)

    /** 新步骤的 id：s + 4 位，不和已有的重。 */
    fun newId(all: List<WfStep>): String {
        val used = ids(all)
        var i = used.size + 1
        while ("s$i" in used) i++
        return "s$i"
    }

    fun ids(list: List<WfStep>): Set<String> = list.flatMap { listOf(it.id) + ids(it.then) + ids(it.otherwise) + ids(it.steps) }.toSet()

    fun find(list: List<WfStep>, id: String): WfStep? {
        for (s in list) {
            if (s.id == id) return s
            (find(s.then, id) ?: find(s.otherwise, id) ?: find(s.steps, id))?.let { return it }
        }
        return null
    }

    fun update(list: List<WfStep>, id: String, f: (WfStep) -> WfStep): List<WfStep> = list.map { s ->
        if (s.id == id) f(s) else s.copy(then = update(s.then, id, f), otherwise = update(s.otherwise, id, f), steps = update(s.steps, id, f))
    }

    fun remove(list: List<WfStep>, id: String): List<WfStep> = list.filterNot { it.id == id }.map { s ->
        s.copy(then = remove(s.then, id), otherwise = remove(s.otherwise, id), steps = remove(s.steps, id))
    }

    fun insert(list: List<WfStep>, slot: Slot, step: WfStep): List<WfStep> {
        if (slot.parent == null) return list.toMutableList().apply { add(slot.index.coerceIn(0, size), step) }
        return update(list, slot.parent) { p ->
            fun add(l: List<WfStep>) = l.toMutableList().apply { add(slot.index.coerceIn(0, size), step) }
            when (slot.branch) {
                "then" -> p.copy(then = add(p.then))
                "else" -> p.copy(otherwise = add(p.otherwise))
                else -> p.copy(steps = add(p.steps))
            }
        }
    }

    /** 在同一层里往上（-1）或往下（+1）挪一格。 */
    fun move(list: List<WfStep>, id: String, delta: Int): List<WfStep> {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            val j = i + delta
            if (j !in list.indices) return list
            return list.toMutableList().apply { add(j, removeAt(i)) }
        }
        return list.map { s -> s.copy(then = move(s.then, id, delta), otherwise = move(s.otherwise, id, delta), steps = move(s.steps, id, delta)) }
    }

    /** 能不能往上 / 往下挪（在同一层的第一个、最后一个就不能）。 */
    fun canMove(list: List<WfStep>, id: String, delta: Int): Boolean {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) return i + delta in list.indices
        return list.any { canMove(it.then, id, delta) || canMove(it.otherwise, id, delta) || canMove(it.steps, id, delta) }
    }

    /** 复制一份（里面的步骤换新 id），放在它后面。 */
    fun duplicate(list: List<WfStep>, id: String): List<WfStep> {
        val used = ids(list).toMutableSet()
        fun nextId(): String { var k = used.size + 1; while ("s$k" in used) k++; return "s$k".also { used += it } }
        fun fresh(s: WfStep): WfStep = s.copy(id = nextId(), name = if (s.name.isBlank()) "" else s.name + "2",
            then = s.then.map(::fresh), otherwise = s.otherwise.map(::fresh), steps = s.steps.map(::fresh))
        fun go(l: List<WfStep>): List<WfStep> {
            val i = l.indexOfFirst { it.id == id }
            if (i >= 0) return l.toMutableList().apply { add(i + 1, fresh(l[i])) }
            return l.map { s -> s.copy(then = go(s.then), otherwise = go(s.otherwise), steps = go(s.steps)) }
        }
        return go(list)
    }

    /** 这种步骤跑完有没有结果能当变量用。 */
    fun yields(type: String) = type !in setOf("if", "forEach", "notify", "wait", "stop")

    /**
     * 跑到 [id] 这一步时能用的变量：自带的三个，前面（按执行顺序）有结果的步骤，外面套着的「对每一项」的那一项和序号。
     * 返回（变量名，从哪来）。
     */
    fun varsBefore(list: List<WfStep>, id: String): List<Pair<String, String>> {
        val out = linkedMapOf("今天" to "自带：今天的日期", "现在" to "自带：现在的时间", "输入" to "自带：这次运行的输入")
        fun walk(l: List<WfStep>): Boolean {
            for (s in l) {
                if (s.id == id) return true
                when (s.type) {
                    "if" -> { if (walk(s.then) || walk(s.otherwise)) return true }
                    "forEach" -> {
                        val before = LinkedHashMap(out)
                        out[s.varName] = "对每一项：${s.list} 里的这一项"; out["序号"] = "对每一项：第几项（从 1 开始）"
                        if (walk(s.steps)) return true
                        // 出了循环，那一项就不能用了；里面算出来的留着（最后一次的）
                        val inner = out.filterKeys { it !in before && it != s.varName && it != "序号" }
                        out.clear(); out.putAll(before); out.putAll(inner)
                    }
                    else -> if (yields(s.type)) out[s.varName] = "第 ${s.id} 步：" + label(s)
                }
            }
            return false
        }
        walk(list)
        return out.map { it.key to it.value }
    }

    /** 新加一步时的默认样子。 */
    fun blank(type: String, id: String): WfStep = when (type) {
        "if" -> WfStep(id = id, type = type, cond = WfCond(op = ">"))
        "forEach" -> WfStep(id = id, type = type, name = "这一项")
        "wait" -> WfStep(id = id, type = type, seconds = 60)
        else -> WfStep(id = id, type = type)
    }
}
