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
}
