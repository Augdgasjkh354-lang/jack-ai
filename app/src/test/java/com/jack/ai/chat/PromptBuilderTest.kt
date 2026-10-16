package com.jack.ai.chat

import com.jack.ai.data.Attachment
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Project
import com.jack.ai.data.Settings
import com.jack.ai.data.SkillIds
import com.jack.ai.data.activeSkills
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val settings = Settings(apiKey = "k", userName = "Jack")
    private val memories = listOf(MemoryItem(text = "喜欢简短的回答"))

    private fun user(text: String, vararg atts: Attachment) = ChatMessage(role = "user", content = text, attachments = atts.toList())
    private fun bot(text: String, trail: List<String> = emptyList(), reasoning: String = "", error: String? = null) =
        ChatMessage(role = "assistant", content = text, trail = trail, reasoning = reasoning, error = error)

    private fun input(
        conv: Conversation,
        project: Project? = null,
        sendReasoning: Boolean = false,
        extra: String? = null,
        now: Long = 1_760_000_000_000,
        s: Settings = settings,
    ) = PromptBuilder.Input(s, conv, project, memories, s.activeSkills(), vision = false, sendReasoning = sendReasoning,
        extraUser = extra, now = now)

    private fun JsonObject.role() = this["role"]!!.jsonPrimitive.content
    private fun JsonObject.textContent(): String = when (val c = this["content"]) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.joinToString("\n") { (it as JsonObject)["text"]?.jsonPrimitive?.content.orEmpty() }
        else -> ""
    }

    private fun tools(conv: Conversation, project: Project? = null, s: Settings = settings) =
        PromptBuilder.toolSet(s, s.activeSkills(), conv, project)

    private fun toolNames(conv: Conversation, project: Project? = null, s: Settings = settings) =
        tools(conv, project, s)!!.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }

    // ---------- 缓存：系统提示词不随时间和新消息变化 ----------

    @Test
    fun systemPromptIsStableAcrossTimeAndNewMessages() {
        val c1 = Conversation(messages = listOf(user("你好")))
        val c2 = c1.copy(messages = c1.messages + bot("你好呀") + user("今天几号"))
        val a = PromptBuilder.history(input(c1, now = 1_760_000_000_000), tools(c1))[0]
        val b = PromptBuilder.history(input(c2, now = 1_760_090_000_000), tools(c2))[0]
        assertEquals("system", a.role())
        assertEquals(a, b)
        assertFalse(a.textContent().contains("当前时间："))
    }

    @Test
    fun stableSectionsComeBeforeChangingOnes() {
        val conv = Conversation(summary = "之前聊了旅行计划", summaryUntilId = "x")
        val project = Project(name = "小说", instructions = "主角叫阿明")
        val sys = PromptBuilder.systemPrompt(settings, conv, project, tools(conv, project), memories, settings.activeSkills())
        val order = listOf("【开发者指令", "【技能】", "【当前项目：小说】", "【个性化设定】", "【关于用户的长期记忆】", "【较早对话的提要】")
            .map { sys.indexOf(it) }
        order.forEach { assertTrue("缺少某个段落：$order", it >= 0) }
        assertEquals(order.sorted(), order)
    }

    // ---------- 当前时间加在最后一条用户消息上 ----------

    @Test
    fun timeIsStampedOnLastUserMessageOnly() {
        val conv = Conversation(messages = listOf(user("第一句"), bot("回答"), user("第二句")))
        val list = PromptBuilder.history(input(conv), tools(conv))
        val users = list.filter { it.role() == "user" }
        assertFalse(users[0].textContent().contains("当前时间"))
        assertTrue(users[1].textContent().startsWith("【当前时间："))
        assertTrue(users[1].textContent().endsWith("第二句"))
    }

    @Test
    fun extraUserMessageGetsTheTimestamp() {
        val conv = Conversation(messages = listOf(user("问题"), bot("回答")))
        val list = PromptBuilder.history(input(conv, extra = "请复核"), tools(conv))
        assertTrue(list.last().textContent().startsWith("【当前时间："))
        assertTrue(list.last().textContent().endsWith("请复核"))
        assertFalse(list.first { it.role() == "user" }.textContent().contains("当前时间"))
    }

    @Test
    fun timestampWorksWithImageMessages() {
        val list = mutableListOf(JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonArray(listOf(
            JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("看图"))),
        )))))
        PromptBuilder.stampLastUser(list, 0)
        val parts = list[0]["content"] as JsonArray
        assertEquals(2, parts.size)
        assertTrue((parts[0] as JsonObject)["text"]!!.jsonPrimitive.content.startsWith("【当前时间："))
    }

    // ---------- 历史消息 ----------

    @Test
    fun failedRepliesAreSkippedAndSummaryHidesOldMessages() {
        val old = user("很久以前")
        val conv = Conversation(
            messages = listOf(old, bot("旧回答"), user("出错那次"), bot("", error = "网络失败"), user("现在")),
            summary = "提要", summaryUntilId = old.id,
        )
        val texts = PromptBuilder.history(input(conv), tools(conv)).drop(1).map { it.textContent() }
        assertFalse(texts.any { it.contains("很久以前") })
        assertTrue(texts.any { it.contains("旧回答") })
        assertFalse(texts.any { it.contains("网络失败") })
    }

    private val trailCall = """{"role":"assistant","content":null,"tool_calls":[{"id":"1","type":"function","function":{"name":"web_search","arguments":"{}"}}]}"""
    private val trailResult = """{"role":"tool","tool_call_id":"1","content":"结果"}"""

    @Test
    fun toolTrailIsReplayedUnlessFlat() {
        val conv = Conversation(messages = listOf(user("搜一下"), bot("搜到了", trail = listOf(trailCall, trailResult)), user("谢谢")))
        val full = PromptBuilder.history(input(conv), tools(conv))
        assertEquals(1, full.count { it.role() == "tool" })
        val flat = PromptBuilder.history(input(conv), tools(conv), flat = true)
        assertEquals(0, flat.count { it.role() == "tool" })
        val noTools = PromptBuilder.history(input(conv), null)
        assertEquals(0, noTools.count { it.role() == "tool" })
    }

    @Test
    fun thinkingModeDropsTrailWithoutReasoning() {
        // DeepSeek 开思考时，旧的工具回合缺思考内容会报 400，所以不发
        val conv = Conversation(messages = listOf(user("搜一下"), bot("搜到了", trail = listOf(trailCall, trailResult)), user("再说说")))
        val list = PromptBuilder.history(input(conv, sendReasoning = true), tools(conv))
        assertEquals(0, list.count { it.role() == "tool" })
    }

    @Test
    fun reasoningIsStrippedWhenNotSendingIt() {
        val call = """{"role":"assistant","content":null,"reasoning_content":"想一想","tool_calls":[]}"""
        val conv = Conversation(messages = listOf(user("问"), bot("答", trail = listOf(call, trailResult), reasoning = "想一想"), user("再问")))
        val off = PromptBuilder.history(input(conv), tools(conv))
        assertTrue(off.none { "reasoning_content" in it })
        val on = PromptBuilder.history(input(conv, sendReasoning = true), tools(conv))
        assertTrue(on.any { "reasoning_content" in it && it["tool_calls"] != null })
        assertTrue(on.any { it.role() == "assistant" && it.textContent() == "答" && "reasoning_content" in it })
    }

    // ---------- 用户消息 ----------

    @Test
    fun filesAreInlinedAndImagesExplainedWhenModelCannotSee() {
        val file = Attachment(kind = "file", name = "合同.txt", text = "第一条：甲方……")
        val img = Attachment(kind = "image", name = "a.jpg", path = "/x/a.jpg")
        val m = PromptBuilder.userMessage(user("看看", file, img), imagesOk = false)
        val t = m.textContent()
        assertTrue(t.contains("【文件：合同.txt】\n第一条：甲方……\n【文件结束】"))
        assertTrue(t.contains("用户发了 1 张图片，但当前模型看不了图片"))
        assertTrue(t.endsWith("看看"))
    }

    @Test
    fun imagesBecomeMultipartWhenModelCanSee() {
        val img = Attachment(kind = "image", name = "a.jpg", path = "/x/a.jpg")
        val m = PromptBuilder.userMessage(user("", img), imagesOk = true, imageUrl = { "data:image/jpeg;base64,AAA" })
        val parts = m["content"] as JsonArray
        assertEquals("image_url", (parts[0] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("请看图片。", (parts[1] as JsonObject)["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun uploadedImagesAreSentById() {
        val a = Attachment(kind = "image", name = "a.jpg", path = "/x/a.jpg")
        val b = Attachment(kind = "image", name = "b.jpg", path = "/x/b.jpg")
        val m = PromptBuilder.userMessage(user("", a, b), imagesOk = true, imageUrl = { "data:image/jpeg;base64,AAA" },
            fileIds = mapOf("/x/a.jpg" to "file-api-1"))
        val parts = m["content"] as JsonArray
        assertEquals("file", (parts[0] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("file-api-1", (parts[0] as JsonObject)["file_id"]!!.jsonPrimitive.content)
        assertEquals("image_url", (parts[1] as JsonObject)["type"]!!.jsonPrimitive.content)
    }

    // ---------- 工具列表 ----------

    @Test
    fun toolsFollowSettingsAndState() {
        val conv = Conversation()
        val names = toolNames(conv)
        assertTrue("save_memory" in names)
        assertTrue("web_search" in names) // 没有 Tavily Key，但有 DeepSeek Key 也能联网
        assertFalse("search_history" in names)
        assertFalse("search_project" in names)
        assertTrue("add_calendar_event" in names)

        // 本对话记忆随时能存，关了全局记忆工具也在
        assertTrue("save_memory" in toolNames(conv, s = settings.copy(memoryEnabled = false)))
        assertTrue("web_search" in toolNames(conv, s = settings.copy(tavilyKey = "t")))
        assertTrue("search_history" in toolNames(conv.copy(summary = "提要")))
        assertFalse("add_calendar_event" in toolNames(conv, s = settings.copy(disabledSkills = setOf(SkillIds.CALENDAR))))
    }

    // ---------- 项目资料 ----------

    @Test
    fun bigProjectFilesAreSearchedNotInlined() {
        val small = Attachment(kind = "file", name = "大纲.md", text = "第一章：出发")
        val big = Attachment(kind = "file", name = "全文.txt", text = "字".repeat(PromptBuilder.PROJECT_INLINE_CHARS + 1))
        val project = Project(name = "小说", files = listOf(small, big))
        val conv = Conversation(projectId = project.id)
        val sys = PromptBuilder.systemPrompt(settings, conv, project, tools(conv, project), memories, settings.activeSkills())
        assertTrue(sys.contains("第一章：出发"))
        assertTrue(sys.contains("《全文.txt》"))
        assertFalse(sys.contains("字".repeat(1000)))
        assertTrue("search_project" in toolNames(conv, project))
    }

    @Test
    fun contextBreakdownNamesTheParts() {
        val conv = Conversation(messages = listOf(user("你好"), bot("你好呀")))
        val parts = PromptBuilder.contextBreakdown(settings, conv, null, memories, settings.activeSkills()).toMap()
        assertNotNull(parts["工具说明"])
        assertNotNull(parts["固定说明"])
        assertNotNull(parts["长期记忆"])
        assertNotNull(parts["聊天记录"])
        assertTrue(parts["工具说明"]!! > 1000)
    }

    @Test
    fun journalGoesWithGlobalMemoryOnly() {
        val conv = Conversation()
        val sys = PromptBuilder.systemPrompt(settings, conv, null, null, memories, settings.activeSkills(), journal = "他说话玩笑裹着认真")
        assertTrue(sys.contains("【你的日记】"))
        assertTrue(sys.contains("玩笑裹着认真"))
        val off = PromptBuilder.systemPrompt(settings, conv.copy(useGlobalMemory = false), null, null, memories, settings.activeSkills(), journal = "他说话玩笑裹着认真")
        assertFalse(off.contains("玩笑裹着认真"))
    }

    @Test
    fun tokenEstimate() {
        assertEquals(6, PromptBuilder.estTokens("一二三四五六七八九十"))
        assertEquals(3, PromptBuilder.estTokens("abcdefghij"))
    }

    // ---------- 三层记忆 ----------

    @Test
    fun chatAndProjectMemoryComeAfterGlobalAndBeforeSummary() {
        val project = Project(name = "小说")
        val conv = Conversation(projectId = project.id, summary = "提要", summaryUntilId = "x")
        val sys = PromptBuilder.systemPrompt(settings, conv, project, tools(conv, project), memories, settings.activeSkills(),
            chatMemory = "- 主角叫阿明", projectMemory = "- 故事发生在 1990 年")
        val order = listOf("【关于用户的长期记忆】", "【项目记忆】", "【本对话记忆】", "【较早对话的提要】").map { sys.indexOf(it) }
        order.forEach { assertTrue("缺少某段：$order", it >= 0) }
        assertEquals(order.sorted(), order)
        assertTrue(sys.contains("主角叫阿明"))
        assertTrue(sys.contains("故事发生在 1990 年"))
        assertTrue(sys.contains("对话记忆-"))
    }

    @Test
    fun chatCanOptOutOfGlobalMemory() {
        val conv = Conversation(useGlobalMemory = false)
        val sys = PromptBuilder.systemPrompt(settings, conv, null, tools(conv), memories, settings.activeSkills(), chatMemory = "- 只在这里")
        assertFalse(sys.contains("喜欢简短的回答"))
        assertTrue(sys.contains("不要存 global"))
        assertTrue(sys.contains("只在这里"))
        assertTrue(sys.contains("「记忆.md」"))
    }

    @Test
    fun memoryRulesMatchWhereWeAre() {
        val plain = PromptBuilder.memoryRules(Conversation(), inProject = false, globalOn = true)
        assertTrue(plain.contains("chat（本对话记忆）"))
        assertTrue(plain.contains("global（全局记忆）"))
        assertFalse(plain.contains("project（项目记忆）"))
        assertTrue(PromptBuilder.memoryRules(Conversation(), inProject = true, globalOn = true).contains("project（项目记忆）"))
    }

    @Test
    fun memoryRulesAreStableSoCacheHolds() {
        // 规则只跟对话类型有关，存了新记忆也不变
        val conv = Conversation()
        val a = PromptBuilder.systemPrompt(settings, conv, null, tools(conv), memories, settings.activeSkills())
        val b = PromptBuilder.systemPrompt(settings, conv, null, tools(conv), memories, settings.activeSkills(), chatMemory = "- 新的一条")
        assertTrue(b.startsWith(a.substringBefore("【个性化设定】")))
    }

    @Test
    fun mailToolsOnlyWhenAccountIsReady() {
        val conv = Conversation()
        assertFalse("send_email" in toolNames(conv))
        val half = settings.copy(mail = com.jack.ai.data.mailPresetFor("jack@163.com"))
        assertFalse("send_email" in toolNames(conv, s = half))
        val ready = settings.copy(mail = com.jack.ai.data.mailPresetFor("jack@163.com")!!.copy(password = "code"))
        assertTrue(listOf("list_emails", "read_email", "send_email", "mark_email_read").all { it in toolNames(conv, s = ready) })
    }
}
