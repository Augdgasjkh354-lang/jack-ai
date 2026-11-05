package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillRepoTest {
    @Test fun parsesFrontmatterWithFoldedDescription() {
        val md = "---\nname: \"xhs-writer\"\ndescription: >\n  写小红书笔记。\n  先列标题。\nlicense: MIT\n---\n# 做法\n1. 先看对标\n"
        val p = SkillRepo.parse(md, "dir")
        assertEquals("xhs-writer", p.name)
        assertEquals("写小红书笔记。 先列标题。", p.description)
        assertEquals("# 做法\n1. 先看对标", p.body)
    }

    @Test fun fallsBackToFolderNameWithoutFrontmatter() {
        val p = SkillRepo.parse("直接写做法", "my-skill")
        assertEquals("my-skill", p.name)
        assertEquals("直接写做法", p.body)
    }
}
