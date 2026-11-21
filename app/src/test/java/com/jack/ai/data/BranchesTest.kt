package com.jack.ai.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchesTest {
    private fun u(id: String, t: String = id) = ChatMessage(id = id, role = "user", content = t)
    private fun a(id: String, t: String = id) = ChatMessage(id = id, role = "assistant", content = t)
    private fun ids(list: List<ChatMessage>) = list.map { it.id }

    @Test fun forkKeepsOldVersionAndSwitchesBack() {
        val msgs = listOf(u("u1"), a("a1"), u("u2"), a("a2"))
        val f = Branches.fork(msgs, 2, u("u2b", "改过"), keepOld = true)
        assertEquals(listOf("u1", "a1", "u2b"), ids(f.messages))
        val head = f.messages[2]
        assertEquals(2, Branches.count(head))
        assertEquals(1, head.altIndex)
        // 回答接上后切回第一个版本
        val withAnswer = f.messages + a("a2b")
        val back = Branches.switch(withAnswer, 2, 0)!!
        assertEquals(listOf("u1", "a1", "u2", "a2"), ids(back))
        assertEquals(0, back[2].altIndex)
        assertEquals(listOf("u2b", "a2b"), ids(back[2].alts.single()))
        // 再切过去，原样回来
        val again = Branches.switch(back, 2, 1)!!
        assertEquals(ids(withAnswer), ids(again))
        assertNull(Branches.switch(again, 2, 1))
        assertNull(Branches.switch(again, 2, 5))
    }

    @Test fun nestedBranchesSurviveSwitching() {
        var msgs = listOf(u("u1"), a("a1"), u("u2"), a("a2"))
        msgs = Branches.fork(msgs, 2, u("u2b"), keepOld = true).messages + a("a2b")
        // 在最早那处再分一次
        msgs = Branches.fork(msgs, 0, u("u1b"), keepOld = true).messages + a("a1b")
        val back = Branches.switch(msgs, 0, 0)!!
        assertEquals(listOf("u1", "a1", "u2b", "a2b"), ids(back))
        assertEquals(2, Branches.count(back[2]))
        assertEquals(4, Branches.all(back).count { it.role == "user" })
    }

    @Test fun capsVersionsAndReportsDropped() {
        var msgs = listOf(u("v0"), a("x0"))
        repeat(Branches.MAX + 2) { n -> msgs = Branches.fork(msgs, 0, u("v${n + 1}"), keepOld = true).messages + a("x${n + 1}") }
        val head = msgs[0]
        assertEquals(Branches.MAX, Branches.count(head))
        assertEquals(Branches.MAX - 1, head.altIndex)
        assertEquals("v${Branches.MAX + 2}", head.id)
        val f = Branches.fork(msgs, 0, u("new"), keepOld = true)
        assertEquals(listOf("v3"), f.dropped.map { it.first().id })
    }

    @Test fun notKeptDropsOldButKeepsEarlierVersions() {
        var msgs = Branches.fork(listOf(u("u"), a("a")), 0, u("ub"), keepOld = true).messages + a("err")
        msgs = Branches.fork(msgs, 0, u("uc"), keepOld = false).messages
        assertEquals(2, Branches.count(msgs[0]))
        assertEquals("u", msgs[0].alts.single().first().id)
    }

    @Test fun worthKeepingNeedsARealAnswer() {
        assertTrue(Branches.worthKeeping(listOf(a("a", "答"))))
        assertEquals(false, Branches.worthKeeping(listOf(ChatMessage(role = "assistant", error = "网络断了"))))
        assertEquals(false, Branches.worthKeeping(emptyList()))
    }

    @Test fun serializesRecursively() {
        val msgs = Branches.fork(listOf(u("u"), a("a")), 0, u("ub"), keepOld = true).messages
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), msgs)
        val back = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), text)
        assertEquals(listOf("u", "a"), ids(back[0].alts.single()))
    }
}
