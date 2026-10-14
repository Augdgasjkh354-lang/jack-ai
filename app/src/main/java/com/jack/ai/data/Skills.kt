package com.jack.ai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Skill = 名字 + 一句话简介 + 详细做法说明（+ 它要用的工具）。
 * 平时只把简介告诉 AI；AI 决定使用时，先调用 use_skill 读取详细说明，再动手。
 */
@Serializable
data class Skill(
    val id: String = newId(),
    val name: String,
    val description: String,
    val instructions: String,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
)

/** AI 生成的文件（Word、PPT、Excel）。 */
@Serializable
data class GeneratedFile(
    val name: String,
    val path: String,
    val mime: String,
    val size: Long = 0,
)

object SkillIds {
    const val WEB = "builtin-web"
    const val WORD = "builtin-word"
    const val PPT = "builtin-ppt"
    const val EXCEL = "builtin-excel"
}

private fun schema(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

val BUILTIN_SKILLS = listOf(
    Skill(
        id = SkillIds.WEB, builtin = true, name = "联网搜索",
        description = "用 Tavily 搜索互联网、读取网页全文，回答时效性问题和核实事实。",
        instructions = """联网搜索的做法：
1. 先判断需不需要搜：新闻、价格、天气、最新版本、近期事件、你不确定的事实，才搜；常识、闲聊、写作、编程不搜。
2. 搜索词简洁精准，像人在搜索引擎里打的那样，3 到 8 个词。中文话题用中文搜，国外话题用英文搜效果更好。
3. 一次搜不到满意结果，换个角度或换关键词再搜，最多搜 3 次。
4. 摘要不够详细，或用户给了链接，用 read_webpage 读全文。
5. 回答时综合多个来源，用 [编号](链接) 标注出处；来源之间有矛盾时说出来。
6. 搜不到可靠信息就直说，不要编。""",
    ),
    Skill(
        id = SkillIds.WORD, builtin = true, name = "Word 文档",
        description = "生成 .docx 文档：报告、方案、简历、总结、信件、说明书等。",
        instructions = """生成 Word 文档的做法：
1. 动手前想清楚：这份文档给谁看、要解决什么问题。用户没说清楚篇幅和用途时，按最常见的情况来，不要反复追问。
2. 用 create_word 工具，内容用 Markdown 写：
   - 第一行用 “# 标题” 作为文档标题；章节用 ##，小节用 ###。
   - 列表用 “- ” 或 “1. ”；表格用 Markdown 表格；重点用 **加粗**。
3. 结构清晰：开头一两段说明目的或结论，中间分章节展开，结尾给出总结或下一步。
4. 内容要具体、有信息量，避免空话套话；正式文档用书面语。
5. 文件名简短明确，比如 “2026年第三季度工作总结”。
6. 生成后在聊天里用一两句话说明文档包含什么，不要把全文再贴一遍。""",
    ),
    Skill(
        id = SkillIds.PPT, builtin = true, name = "PPT 演示文稿",
        description = "生成 .pptx 演示文稿：汇报、课件、方案展示、读书分享等。",
        instructions = """生成 PPT 的做法：
1. 先在心里列好大纲：封面 → （目录）→ 若干章节 → 总结/致谢。一般 6 到 15 页，用户指定页数时照做。
2. 用 create_ppt 工具，每页一个对象：
   - layout: "title"（封面，title 写主标题，bullets 第一条写副标题，比如汇报人和日期）、"section"（章节过渡页，只写 title）、"content"（正文页）。
   - 正文页 bullets 每页 3 到 6 条，每条不超过 25 个字，写要点不写长段落。
   - 需要层级时，在条目前加两个空格表示二级要点。
3. 标题短而有力，最好是结论式的（“销售额同比增长 32%”优于“销售情况”）。
4. theme 从 blue、green、orange、purple、dark 里选一个最贴合主题的：商务用 blue，环保健康用 green，活泼创意用 orange，文艺用 purple，科技用 dark。
5. 生成后用一两句话说明一共几页、讲了什么。""",
    ),
    Skill(
        id = SkillIds.EXCEL, builtin = true, name = "Excel 表格",
        description = "生成 .xlsx 表格：清单、预算、计划表、数据整理、统计等。",
        instructions = """生成 Excel 表格的做法：
1. 用 create_excel 工具。每个工作表给一个简短的名字，rows 是二维数组，第一行是表头。
2. 数字就写数字（不要带单位和千分位逗号，单位写进表头，比如“金额（元）”），日期用 2026-10-14 这种格式。
3. 需要计算时可以写 Excel 公式，以 = 开头，比如 =SUM(B2:B10)、=B2*C2。行号从表头那一行算 1。
4. 数据较多时按逻辑分成几个工作表，比如“明细”和“汇总”。
5. 生成后用一两句话说明表格结构，不要把整张表再贴出来。""",
    ),
)

/** 每个内置 skill 对应的工具定义（OpenAI 兼容格式）。 */
object SkillTools {
    val USE_SKILL = schema("""{"type":"function","function":{"name":"use_skill","description":"读取某个 skill 的详细做法说明。使用一个 skill 之前必须先调用它。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"skill 的名字"}},"required":["name"]}}}""")

    val WEB_SEARCH = schema("""{"type":"function","function":{"name":"web_search","description":"在互联网上搜索最新信息，返回若干网页的标题、链接和摘要。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词，简洁明确"}},"required":["query"]}}}""")

    val READ_WEBPAGE = schema("""{"type":"function","function":{"name":"read_webpage","description":"读取一个网页的正文内容。","parameters":{"type":"object","properties":{"url":{"type":"string","description":"完整网址，以 http 开头"}},"required":["url"]}}}""")

    val CREATE_WORD = schema("""{"type":"function","function":{"name":"create_word","description":"生成一个 Word (.docx) 文档。","parameters":{"type":"object","properties":{"filename":{"type":"string","description":"文件名，不带扩展名"},"markdown":{"type":"string","description":"文档全文，Markdown 格式"}},"required":["filename","markdown"]}}}""")

    val CREATE_PPT = schema("""{"type":"function","function":{"name":"create_ppt","description":"生成一个 PPT (.pptx) 演示文稿。","parameters":{"type":"object","properties":{"filename":{"type":"string","description":"文件名，不带扩展名"},"theme":{"type":"string","enum":["blue","green","orange","purple","dark"],"description":"配色主题"},"slides":{"type":"array","description":"每页幻灯片","items":{"type":"object","properties":{"layout":{"type":"string","enum":["title","section","content"]},"title":{"type":"string"},"bullets":{"type":"array","items":{"type":"string"}}},"required":["layout","title"]}}},"required":["filename","slides"]}}}""")

    val CREATE_EXCEL = schema("""{"type":"function","function":{"name":"create_excel","description":"生成一个 Excel (.xlsx) 表格。","parameters":{"type":"object","properties":{"filename":{"type":"string","description":"文件名，不带扩展名"},"sheets":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"rows":{"type":"array","description":"二维数组，第一行是表头","items":{"type":"array","items":{"type":"string","description":"单元格内容；数字也写成字符串，公式以 = 开头"}}}},"required":["name","rows"]}}},"required":["filename","sheets"]}}}""")

    val SEARCH_HISTORY = schema("""{"type":"function","function":{"name":"search_history","description":"在本对话较早的原始聊天记录里按关键词查找原话和细节。提要里找不到的信息用它查。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"关键词，多个用空格隔开"}},"required":["query"]}}}""")

    val SAVE_MEMORY = schema("""{"type":"function","function":{"name":"save_memory","description":"把一条关于用户的信息存进长期记忆。仅在用户明确要求“记住”时使用。","parameters":{"type":"object","properties":{"content":{"type":"string","description":"要记住的内容，一句完整的话"}},"required":["content"]}}}""")
}

/** 当前开着的 skill：内置的看开关（联网还要有 Tavily Key），自定义的看它自己的 enabled。 */
fun Settings.activeSkills(): List<Skill> =
    allSkills().filter { it.enabled && (it.id != SkillIds.WEB || tavilyKey.isNotBlank()) }

/** 全部 skill（内置 + 自定义），enabled 反映当前开关状态。 */
fun Settings.allSkills(): List<Skill> =
    BUILTIN_SKILLS.map { s ->
        s.copy(enabled = if (s.id == SkillIds.WEB) webSearch else s.id !in disabledSkills)
    } + customSkills
