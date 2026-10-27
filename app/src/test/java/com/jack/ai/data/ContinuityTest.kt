package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连续性：做梦的修改单（有出处、作废不删、改太多整次不做）；挂着的事（记、了结、碰、淡出）。 */
class ContinuityTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun changeListEditsInPlaceWithHistory() {
        val a = MemoryItem(id = "aaaa1111", text = "Jack 喜欢咖啡")
        val b = MemoryItem(id = "bbbb2222", text = "Jack 住在杭州")
        val c = MemoryItem(id = "cccc3333", text = "Jack 在学吉他")
        val ops = MemoryOps.parse(Json.parseToJsonElement("""[
            {"op":"update","id":"m:aaaa11","text":"Jack 喜欢咖啡，但最近改喝茶","why":"他说最近戒咖啡","source":"10-25 「随便聊」"},
            {"op":"invalidate","id":"bbbb22","why":"搬去上海了","source":"10-26 「搬家」"},
            {"op":"add","text":"Jack 搬去了上海","source":"10-26 「搬家」"},
            {"op":"add","text":"jack 在学吉他。"},
            {"op":"update","id":"zzzz99","text":"不存在的"},
            {"op":"delete","id":"cccc33"}
        ]""") as JsonArray)
        assertEquals(5, ops.size)  // 不认的 op 丢掉
        val r = MemoryOps.apply(listOf(a, b, c), ops, now)
        assertEquals(listOf("Jack 喜欢咖啡，但最近改喝茶", "Jack 在学吉他", "Jack 搬去了上海"), r.live.map { it.text })
        assertEquals("Jack 喜欢咖啡", r.live[0].history.single().old)
        assertEquals("10-25 「随便聊」", r.live[0].source)
        assertEquals("Jack 住在杭州", r.archived.single().item.text)  // 作废不删，进存档
        assertEquals("搬去上海了", r.archived.single().why)
        assertEquals(1, r.added); assertEquals(1, r.updated); assertEquals(1, r.invalidated); assertEquals(2, r.skipped)
        assertTrue(MemoryOps.listing(r.live).contains("[m:${r.live[0].id.take(6)}]"))
    }

    @Test
    fun tooManyChangesAtOnceAreHeldBack() {
        val ops = List(6) { MemoryOps.Op("invalidate", "id$it") }
        assertTrue(MemoryOps.tooMany(10, ops))           // 10 条里动 6 条：超过 5
        assertFalse(MemoryOps.tooMany(40, ops))          // 40 条里动 6 条：30% 是 12
        assertFalse(MemoryOps.tooMany(10, List(20) { MemoryOps.Op("add", text = "新的 $it") }))  // 新增不算
    }

    @Test
    fun threadsAreHeldSettledAndFade() {
        var t = Threads.hold(emptyList(), "答应 Jack 帮他查报价", "promise", "c1", now - 20 * day)
        t = Threads.hold(t, "想弄明白弦理论在说什么", "wondering", "reading", now - 2 * day)
        t = Threads.hold(t, "想弄明白弦理论在说什么。", "wondering", "", now - day)  // 同一件：只算碰了一下
        assertEquals(2, Threads.open(t).size)
        val string = t.first { it.kind == "wondering" }
        assertEquals(now - day, string.touchedAt)
        // 模型说：了结了报价那件
        val promise = t.first { it.kind == "promise" }
        val o = Json.parseToJsonElement("""{"closed":[{"id":"${promise.id.take(6)}","how":"查好发给他了"}],"open":[{"text":"等 Jack 看完那本书聊聊","kind":"looking_forward"}]}""").jsonObject
        t = Threads.applyJson(t, o, "c1", now)
        assertEquals("closed", t.first { it.id == promise.id }.status)
        val note = Threads.note(t, now)
        assertTrue(note, note.contains("looking forward to") && note.contains("弦理论"))
        assertFalse(note.contains("报价"))
        // 14 天没碰的淡出（不删）
        val (faded, gone) = Threads.fade(t, now + 15 * day)
        assertEquals(2, gone.size)
        assertTrue(Threads.open(faded).isEmpty())
        assertEquals(t.size, faded.size)
    }
}
