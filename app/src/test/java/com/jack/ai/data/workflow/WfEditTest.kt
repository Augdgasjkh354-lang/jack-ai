package com.jack.ai.data.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡片编辑器对步骤树的改动，和每一步能用哪些变量。 */
class WfEditTest {
    private val tree = WorkflowFormat.parse("""{"name":"x","steps":[
        {"id":"a","type":"tool","name":"行情","tool":"t"},
        {"id":"b","type":"forEach","name":"这一只","list":"{{行情}}","steps":[
          {"id":"c","type":"ai","name":"点评","prompt":"p"},
          {"id":"d","type":"if","cond":{"left":"1","op":"<","right":"2"},"then":[{"id":"e","type":"notify","title":"t"}]}]},
        {"id":"f","type":"message","text":"m"}]}""").getOrThrow().steps

    private fun order(l: List<WfStep>): List<String> = l.flatMap { listOf(it.id) + order(it.then) + order(it.otherwise) + order(it.steps) }

    @Test fun insertRemoveMove() {
        val n = WfEdit.newId(tree)
        assertEquals("s7", n)
        val added = WfEdit.insert(tree, WfEdit.Slot("d", "else", 0), WfEdit.blank("set", n))
        assertEquals(listOf("a", "b", "c", "d", "e", "s7", "f"), order(added))
        assertEquals(listOf("s7"), WfEdit.find(added, "d")!!.otherwise.map { it.id })
        val top = WfEdit.insert(tree, WfEdit.Slot(null, index = 1), WfEdit.blank("wait", "w"))
        assertEquals(listOf("a", "w", "b"), top.map { it.id }.take(3))
        assertEquals(listOf("a", "b", "c", "d", "f"), order(WfEdit.remove(tree, "e")))
        assertEquals(listOf("a", "b", "d", "e", "c", "f"), order(WfEdit.move(tree, "c", 1)))
        assertFalse(WfEdit.canMove(tree, "a", -1))
        assertTrue(WfEdit.canMove(tree, "d", -1))
        assertFalse(WfEdit.canMove(tree, "e", 1))
        assertEquals("新名字", WfEdit.find(WfEdit.update(tree, "e") { it.copy(name = "新名字") }, "e")!!.name)
        val dup = WfEdit.duplicate(tree, "d")
        assertEquals(order(dup).size, order(dup).toSet().size)
        assertEquals(listOf("c", "d", "s7"), WfEdit.find(dup, "b")!!.steps.map { it.id })
        assertTrue(WorkflowFormat.check(Workflow(name = "x", steps = dup)).isEmpty())
    }

    @Test fun variablesInScope() {
        fun names(id: String) = WfEdit.varsBefore(tree, id).map { it.first }
        assertEquals(listOf("今天", "现在", "输入"), names("a"))
        assertEquals(listOf("今天", "现在", "输入", "行情", "这一只", "序号"), names("c"))
        assertEquals(listOf("今天", "现在", "输入", "行情", "这一只", "序号", "点评"), names("e"))
        // 出了循环：那一项没了，里面算的点评还在
        assertEquals(listOf("今天", "现在", "输入", "行情", "点评"), names("f"))
    }
}
