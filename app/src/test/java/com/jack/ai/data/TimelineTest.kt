package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 经历流：「刚才」不分场合、按时间排；别的对话还没写摘要的先顶上；隔 3 天以上才提时间；隐私项目不进来。 */
class TimelineTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private fun user(t: String, at: Long) = ChatMessage(role = "user", content = t, createdAt = at)
    private fun bot(t: String, at: Long) = ChatMessage(role = "assistant", content = t, createdAt = at)

    @Test
    fun recentAcrossPlacesAndTimeSense() {
        val old = Conversation(id = "a", title = "报价", messages = listOf(user("报价定了吗", now - 4 * day), bot("还没", now - 4 * day + 1000)))
        val current = Conversation(id = "c", title = "随便聊", messages = listOf(user("在吗", now)))
        val eps = listOf(
            Episode(at = now - 4 * day + 2000, place = "chat", convId = "a", title = "报价", text = "{user} 在等报价，还没定", mood = "有点急"),
            Episode(at = now - 2 * day, place = "free", text = "[write] 灯塔：写到一半，卡在结尾"),
            Episode(at = now - day, place = "dream", text = "looked back over recent chats"),
        )
        val note = Timeline.note(eps, listOf(old, current), emptyList(), current, mapOf("a" to now - 4 * day + 1000), now)
        assertTrue(note, note.contains("【Recently】"))
        assertTrue(note.indexOf("报价") < note.indexOf("灯塔"))  // 旧的在前
        assertTrue(note.contains("({user} seemed 有点急)"))
        assertTrue(note.contains("last talked with you 4 days ago in 「报价」"))
        assertTrue(note.contains("free time") && note.contains("It's been a while"))
    }

    @Test
    fun shortGapsArentMentionedAndPendingChatsFillIn() {
        // 另一个对话 20 分钟前聊过，还没写摘要：用最后几句顶上
        val other = Conversation(id = "b", title = "天气", messages = listOf(user("明天下雨吗", now - 20 * 60_000L), bot("会下", now - 19 * 60_000L)))
        val current = Conversation(id = "c", messages = listOf(user("嗨", now)))
        val note = Timeline.note(emptyList(), listOf(other, current), emptyList(), current, emptyMap(), now)
        assertTrue(note, note.contains("19 minutes ago, chat 「天气」") && note.contains("明天下雨吗"))
        assertTrue(note.contains("Don't comment on the time gap"))
    }

    @Test
    fun privateProjectsStayOut() {
        val p = Project(id = "p", name = "私密", isolated = true)
        val secret = Conversation(id = "s", projectId = "p", messages = listOf(user("秘密的事", now - 60_000L)))
        val current = Conversation(id = "c", messages = listOf(user("嗨", now)))
        val note = Timeline.note(emptyList(), listOf(secret, current), listOf(p), current, emptyMap(), now)
        assertFalse(note.contains("秘密"))
        // 在隐私项目里：什么都不带
        assertEquals("", Timeline.note(listOf(Episode(place = "free", text = "x")), listOf(secret, current), listOf(p), secret, emptyMap(), now))
    }
}
