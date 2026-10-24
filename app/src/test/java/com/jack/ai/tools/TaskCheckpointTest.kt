package com.jack.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskCheckpointTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun msg(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }
    private fun call(vararg ids: String) = buildJsonObject {
        put("role", "assistant")
        put("tool_calls", buildJsonArray { ids.forEach { id -> add(buildJsonObject { put("id", id); put("type", "function") }) } })
    }
    private fun result(id: String) = buildJsonObject { put("role", "tool"); put("tool_call_id", id); put("content", "ok") }

    @Test
    fun savesAndResumesOnlyMatchingFreshProgress() {
        val f = TaskCheckpoint.file(tmp.root, "r1")
        val msgs = listOf(msg("system", "s"), msg("user", "比价"), call("a"), result("a"))
        TaskCheckpoint.save(f, "k", msgs, 0.12, now = 1000)
        val back = TaskCheckpoint.load(f, "k", now = 2000)
        assertNotNull(back)
        assertEquals(4, back!!.messages.size)
        assertEquals(0.12, back.spent, 1e-9)
        // 指令改了、太旧了：不接
        assertNull(TaskCheckpoint.load(f, "other", now = 2000))
        assertNull(TaskCheckpoint.load(f, "k", now = 1000 + TaskCheckpoint.MAX_AGE_MS + 1))
        // 还没做过任何工具：没什么可接的
        TaskCheckpoint.save(f, "k", msgs.take(2), 0.0, now = 1000)
        assertNull(TaskCheckpoint.load(f, "k", now = 2000))
        TaskCheckpoint.clear(f)
        assertNull(TaskCheckpoint.load(f, "k", now = 2000))
    }

    @Test
    fun halfDoneRoundGetsPlaceholderResults() {
        val msgs = listOf(msg("system", "s"), msg("user", "u"), call("a"), result("a"), call("b", "c"), result("b"))
        val fixed = TaskCheckpoint.repair(msgs)
        assertEquals(7, fixed.size)
        val last: JsonObject = fixed.last()
        assertEquals("c", (last["tool_call_id"] as JsonPrimitive).content)
        assertEquals(msgs.take(4), TaskCheckpoint.repair(msgs.take(4)))
    }

    @Test
    fun findsSkillsOpenedBeforeTheInterruption() {
        val use = buildJsonObject {
            put("role", "assistant")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "x"); put("type", "function")
                    put("function", buildJsonObject { put("name", "use_skill"); put("arguments", "{\"name\":\"操作浏览器\"}") })
                })
            })
        }
        assertEquals(listOf("操作浏览器"), TaskCheckpoint.skillsUsed(listOf(msg("user", "u"), use, result("x"))))
    }
}
