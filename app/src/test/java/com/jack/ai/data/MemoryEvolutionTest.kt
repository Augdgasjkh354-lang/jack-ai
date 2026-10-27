package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆自更迭：放到深处、拿回来、合并，用到的记下来，深处的聊到相关的浮上来。 */
class MemoryEvolutionTest {
    private val now = 1_800_000_000_000L
    private val ignore = MemoryOps.ignoreFor(Settings(userName = "Jack"))

    @Test
    fun demotePromoteMerge() {
        val cat = MemoryItem(text = "Jack 养了一只叫团子的橘猫", uses = 2)
        val tea = MemoryItem(text = "Jack 喜欢喝乌龙茶")
        val tea2 = MemoryItem(text = "Jack 平时爱喝乌龙", uses = 3, lastUsedAt = now - 1000)
        val old = MemoryItem(text = "Jack 以前在杭州读大学")
        val r = MemoryOps.apply(
            listOf(cat, tea, tea2, old),
            listOf(
                MemoryOps.Op("demote", old.id.take(6)),
                MemoryOps.Op("merge", text = "Jack 爱喝乌龙茶", ids = listOf(tea.id.take(6), tea2.id.take(6))),
                MemoryOps.Op("promote", "nope00"),
            ),
            now,
        )
        assertEquals(listOf(cat.id, tea.id), r.live.map { it.id })
        assertEquals("Jack 爱喝乌龙茶", r.live[1].text)
        assertEquals(3, r.live[1].uses)                 // 用过的次数合在一起
        assertEquals(listOf(old.id), r.deep.map { it.id })
        assertEquals(1, r.archived.size)                 // 合并掉的进存档
        assertTrue(r.archived[0].why.contains("合并"))
        assertEquals(1, r.demoted); assertEquals(1, r.merged); assertEquals(1, r.skipped)

        // 拿回来；新增一条深处已有的，等于拿回来
        val back = MemoryOps.apply(r.live, listOf(MemoryOps.Op("add", text = "Jack 以前在杭州读大学")), now, r.deep)
        assertTrue(back.deep.isEmpty())
        assertEquals(old.id, back.live.last().id)
        assertEquals(1, back.promoted); assertEquals(0, back.added)
    }

    @Test
    fun mergeAndDemoteCountTowardLimit() {
        assertFalse(MemoryOps.tooMany(10, List(20) { MemoryOps.Op("demote", "id$it") }))   // 放到深处能撤回，不算
        assertTrue(MemoryOps.tooMany(10, listOf(MemoryOps.Op("merge", ids = List(6) { "id$it" }))))
    }

    @Test
    fun markUsedIgnoresFillerAndName() {
        val cat = MemoryItem(text = "Jack 养了一只叫团子的橘猫")
        val job = MemoryItem(text = "Jack 在一家做跨境电商的公司上班")
        val out = MemoryOps.markUsed(listOf(cat, job), "我：团子今天又把橘猫粮打翻了，Jack 你说怎么办", now, ignore)
        assertEquals(1, out[0].uses); assertEquals(now, out[0].lastUsedAt)
        assertEquals(0, out[1].uses)                     // 只沾了名字和虚字，不算
    }

    @Test
    fun recallFromDeep() {
        val deep = listOf(
            MemoryItem(text = "Jack 对花生过敏，吃了会起疹子"),
            MemoryItem(text = "Jack 小时候学过三年钢琴"),
        )
        assertEquals(deep[0].id, MemoryOps.recall(deep, "晚上想做个花生酱拌面，会不会过敏啊", ignore = ignore).single().id)
        assertTrue(MemoryOps.recall(deep, "Jack 今天好累", ignore = ignore).isEmpty())
    }

    @Test
    fun budget() {
        val list = List(10) { MemoryItem(text = "Jack 喜欢这件事情第 $it 条") }
        assertTrue(MemoryOps.hotTokens(list) in 50..200)
        assertTrue(MemoryOps.listing(listOf(MemoryItem(text = "x", createdAt = now)), now).contains("not used since"))
    }
}
