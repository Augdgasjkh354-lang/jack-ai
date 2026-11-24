package com.jack.ai.chat

import com.jack.ai.data.Conversation
import com.jack.ai.data.Project
import com.jack.ai.data.ProjectKind
import com.jack.ai.data.Settings
import com.jack.ai.data.SkillIds
import com.jack.ai.data.activeSkills
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代码、写作项目开局只带相关的工具，别的读过做法（use_skill）才带；通用创作全带。 */
class ProjectKindTest {
    private val settings = Settings(apiKey = "k")
    private val skills = settings.activeSkills()
    private fun tools(kind: ProjectKind, unlocked: Set<String> = emptySet()): Set<String> {
        val p = Project(name = "p", kind = kind.id)
        val conv = Conversation(projectId = p.id)
        return PromptBuilder.toolSet(settings, skills, conv, p, unlocked).orEmpty()
            .map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
    }

    @Test fun codeProjectStartsSmall() {
        val code = tools(ProjectKind.CODE)
        assertTrue("search_code" in code && "web_search" in code && "read_file" in code)
        assertFalse("design" in code || "create_ppt" in code || "create_document" in code)
        assertTrue(tools(ProjectKind.GENERAL).size > code.size + 5)
        // 读过「设计」的做法就带上
        assertTrue("design" in tools(ProjectKind.CODE, setOf(SkillIds.DESIGN)))
    }

    @Test fun writingProjectHasDocumentsButNotCodeTools() {
        val w = tools(ProjectKind.WRITING)
        assertTrue("create_document" in w && "check_ai_tone" in w)
        assertFalse("search_code" in w || "design" in w || "create_ppt" in w)
    }

    @Test fun generalProjectMatchesNoProject() {
        val conv = Conversation()
        val none = PromptBuilder.toolSet(settings, skills, conv, null).orEmpty().size
        assertTrue(tools(ProjectKind.GENERAL).size >= none - 1)
    }

    @Test fun gptStartsWithAFewToolsAndOpensTheRestOnDemand() {
        val gpt = Settings(apiKey = "k", tavilyKey = "t", providers = listOf(com.jack.ai.data.Provider(id = "g", name = "ChatGPT",
            baseUrl = com.jack.ai.data.CHATGPT_PROVIDER_URL, apiKey = com.jack.ai.data.CHATGPT_API_KEY,
            models = listOf(com.jack.ai.data.ProviderModel("gpt-6.1-sol", vision = true, reasoning = true)))),
            model = com.jack.ai.data.customModelKey("g", "gpt-6.1-sol"))
        fun names(s: Settings, unlocked: Set<String> = emptySet()) = PromptBuilder.toolSet(s, s.activeSkills(), Conversation(), null, unlocked).orEmpty()
        val slim = names(gpt)
        val full = names(gpt.copy(model = ""))
        val slimNames = slim.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("web_search" in slimNames && "read_file" in slimNames && "schedule" in slimNames)
        assertFalse("create_ppt" in slimNames || "browser" in slimNames || "convert_file" in slimNames || "github" in slimNames || "design" in slimNames)
        // 至少省一半
        assertTrue("${slim.toString().length} vs ${full.toString().length}", slim.toString().length * 2 < full.toString().length)
        // 读过「PPT」的做法，PPT 和转换文件的工具就带上
        val opened = names(gpt, setOf(SkillIds.PPT)).map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("create_ppt" in opened && "convert_file" in opened)
        // 读过「动画短片」：画面、配音合成的工具都要有（以前只解锁了技能本身，make_animation 卡在「设计」上）
        val anim = names(gpt, setOf(SkillIds.ANIMATION)).map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(anim.toString(), "make_animation" in anim && "convert_file" in anim && "design" in anim)
        // 「设计」插件关了也照样能做动画
        val noDesign = gpt.copy(disabledSkills = gpt.disabledSkills + SkillIds.DESIGN)
        val anim2 = names(noDesign, setOf(SkillIds.ANIMATION)).map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(anim2.toString(), "make_animation" in anim2 && "design" !in anim2)
    }
}
