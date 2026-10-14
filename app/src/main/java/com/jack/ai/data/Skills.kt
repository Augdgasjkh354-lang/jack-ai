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
    const val PDF = "builtin-pdf"
    const val CHATS = "builtin-chats"
    const val RESEARCH = "builtin-research"
    const val ROUTINE = "builtin-routine"
    const val CALC = "builtin-calc"
    const val LIBRARY = "builtin-library"
    const val LEARN = "builtin-learn"
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
5. 配图：挑 2 到 4 页最适合配图的正文页，给它们写 image_query（配图搜索词，用英文、具体的名词效果最好，比如 "electric car charging station"）。抽象的、全是数字的页不配图。配图会放在正文页右侧。
6. 生成后用一两句话说明一共几页、讲了什么。""",
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
    Skill(
        id = SkillIds.PDF, builtin = true, name = "PDF 文档",
        description = "生成 .pdf 文件：适合直接发给别人看、打印的文档，比如简历、通知、说明、行程单。",
        instructions = """生成 PDF 的做法：
1. 用 create_pdf 工具，内容用 Markdown 写，规则和 Word 一样：第一行 “# 标题”，章节用 ##，小节用 ###，列表、表格、**加粗**、> 引用都可以用。
2. PDF 不能再编辑，适合定稿的内容；用户想自己改的，建议用 Word。
3. 版面要清爽：段落不要太长，多用小标题和列表；表格列数不超过 5 列。
4. 文件名简短明确。生成后用一两句话说明内容，不要把全文再贴一遍。""",
    ),
    Skill(
        id = SkillIds.CHATS, builtin = true, name = "聊天记录搜索",
        description = "翻找用户和你以前所有对话的记录。用户问“我们之前聊过的…”“上次说的那个…”时使用。",
        instructions = """搜索聊天记录的做法：
1. 用 search_chats 工具，搜索词用对方可能说过的关键词，2 到 4 个词，用空格隔开。比如“餐厅 推荐”“周报 模板”。
2. 搜不到就换同义词再搜，最多 3 次。
3. 结果会带日期和对话标题。回答时说明是哪天、在哪个对话里聊的，例如“10 月 3 日在「周末去哪玩」里你提到过…”。
4. 只根据搜到的内容回答，找不到就直说没找到，不要编。""",
    ),
    Skill(
        id = SkillIds.RESEARCH, builtin = true, name = "深度研究",
        description = "对一个问题做彻底的调研：多角度连续搜索、读原文、交叉核对，最后写成带出处的研究报告。用户说“帮我研究/调研/深入了解…”时使用。需要联网搜索技能开着。",
        instructions = """深度研究的做法（这次你最多可以用 25 轮工具）：
1. 先拆题：把问题拆成 4 到 8 个子问题（是什么、现状和数据、不同观点、利弊、案例、最新进展、对用户的建议等），心里列好计划。
2. 广泛搜索：每个子问题至少搜一次，中文和英文都搜；换角度、换关键词，总共搜 8 到 20 次。
3. 读原文：挑 3 到 6 个最权威、最相关的来源（官方、论文、权威媒体、行业报告），用 read_webpage 读全文，不要只看摘要。
4. 交叉核对：关键数字和结论至少两个来源互相印证；来源有矛盾时，说明分歧和各自依据；注意信息的发布日期。
5. 写报告，结构如下：
   # 标题
   ## 结论摘要（3 到 5 条最重要的发现）
   ## 背景
   ## 分章节的详细发现（每段都用 [编号](链接) 标出处）
   ## 不同观点与不确定的地方
   ## 给用户的建议
   ## 参考来源（编号列表：标题 + 链接）
6. 报告较长时，问用户要不要存成 Word 或 PDF；用户一开始就要文件的，直接用 create_word 或 create_pdf 生成，聊天里只写摘要。
7. 不确定的就说不确定，不要为了完整而编造。""",
    ),
    Skill(
        id = SkillIds.ROUTINE, builtin = true, name = "提醒和定时任务",
        description = "设置到点弹通知的提醒（“明天 9 点提醒我交报告”），或让你定时在后台自动干活（“每天早上 8 点给我整理 AI 新闻”）。",
        instructions = """提醒和定时任务的做法：
1. 只是到点通知用户一件事，用 create_reminder；需要你到点去搜索、整理、写东西的，用 create_task。
2. 时间写成 “yyyy-MM-dd HH:mm”，根据系统提示里的当前日期换算“明天”“下周一”“今晚”等说法。用户没说具体几点时，按常理挑一个（早上 9:00、晚上 20:00 等），并在回复里说明。
3. repeat 填 none（一次）、daily（每天）、weekdays（工作日）、weekly（每周同一天）。
4. 提醒的 text 写成到时候通知里显示的一句话，比如“该交周报了”。
5. 定时任务的 prompt 要写成完整、独立的指令，因为到时候你是在后台、看不到现在这段对话的。比如“搜索过去 24 小时 AI 领域的重要新闻，整理成 5 条简报，每条一句话加来源链接”。
6. 设置好后用一句话确认：什么时候、做什么。
7. 用户想看或删除已有的提醒和任务时，先用 list_routines 查，再用 delete_routine 删。""",
    ),
    Skill(
        id = SkillIds.CALC, builtin = true, name = "计算与图表",
        description = "真正运行代码来算数、统计、处理数据，或把数据画成柱状图、折线图、饼图。涉及精确计算（贷款、利息、汇率换算、统计）时使用。",
        instructions = """计算与图表的做法：
1. 需要精确计算时，不要心算，用 run_js 运行 JavaScript：
   - 用 console.log() 输出结果，或者让最后一行是要返回的值。
   - 只能做纯计算，不能联网、不能读文件。日期用 Date，数学用 Math。
   - 金额结果用 toFixed(2) 保留两位小数。
   - 运行出错就看错误信息改代码再试，最多 3 次。
2. 回答时给出最终数字和简要的计算思路，不要把代码贴给用户（除非用户想看）。
3. 画图用 create_chart：
   - 比较几个类别用 bar（柱状图），看随时间的变化用 line（折线图），看占比用 pie（饼图，只用第一组数据）。
   - labels 是横轴的类别或时间；series 是一组或多组数据，每组有 name 和 values，values 的个数和 labels 一样。
   - 标题简短，unit 写单位（比如“万元”“%”）。
4. 图生成后会直接显示在聊天里，你用一两句话点出图里最值得注意的地方。""",
    ),
    Skill(
        id = SkillIds.LIBRARY, builtin = true, name = "资料库",
        description = "在用户的个人资料库（他导入的书、笔记、合同、课件等）里查找内容。用户问到他自己的资料、某本书或某份文件里的内容时使用。",
        instructions = """查资料库的做法：
1. 不确定资料库里有什么时，先用 list_library 看有哪些文档。
2. 用 search_library 检索，搜索词用原文里可能出现的关键词，2 到 5 个词，用空格隔开。中文资料用中文搜，英文资料用英文搜。
3. 一次没找到就换同义词、换角度再搜，最多 4 次。
4. 回答时注明出处，比如“据《活着》第 12 段……”。只根据搜到的原文回答；资料里没有的，明确说资料里没找到，再看要不要用你自己的知识补充（要说明是补充）。""",
    ),
    Skill(
        id = SkillIds.LEARN, builtin = true, name = "学习模式",
        description = "像一位耐心的老师，用提问和引导帮用户真正学会一个知识点，并把重点做成复习卡片。用户说“教我…”“我想学…”“帮我理解…”时使用。",
        instructions = """学习模式的做法：
1. 先摸底：问一两个问题了解用户已经知道多少、想学到什么程度。
2. 引导而不是灌输：把知识拆成小步，每次只讲一小块，讲完就提一个问题让用户自己想、自己说。用户答对了给肯定并往下走；答错了不要直接给答案，给提示，引导他自己发现。
3. 多用生活中的类比和具体例子，少用术语；必须用术语时先解释。
4. 每讲完一部分，用一句话帮用户总结，再问他能不能用自己的话复述。
5. 学完一个主题后，主动用 save_flashcards 把 5 到 15 个关键知识点做成复习卡片：正面是一个具体的问题，背面是简洁准确的答案（不超过 80 字）。deck 写主题名。然后告诉用户可以在左侧菜单「复习卡片」里按时复习。
6. 保持鼓励、有耐心，但不要空洞夸奖。""",
    ),
)

/** 每个内置 skill 对应的工具定义（OpenAI 兼容格式）。 */
object SkillTools {
    val USE_SKILL = schema("""{"type":"function","function":{"name":"use_skill","description":"读取某个 skill 的详细做法说明。使用一个 skill 之前必须先调用它。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"skill 的名字"}},"required":["name"]}}}""")

    val WEB_SEARCH = schema("""{"type":"function","function":{"name":"web_search","description":"在互联网上搜索最新信息，返回若干网页的标题、链接和摘要。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词，简洁明确"}},"required":["query"]}}}""")

    val READ_WEBPAGE = schema("""{"type":"function","function":{"name":"read_webpage","description":"读取一个网页的正文内容。","parameters":{"type":"object","properties":{"url":{"type":"string","description":"完整网址，以 http 开头"}},"required":["url"]}}}""")

    val CREATE_WORD = schema("""{"type":"function","function":{"name":"create_word","description":"生成一个 Word (.docx) 文档。","parameters":{"type":"object","properties":{"filename":{"type":"string","description":"文件名，不带扩展名"},"markdown":{"type":"string","description":"文档全文，Markdown 格式"}},"required":["filename","markdown"]}}}""")

    val CREATE_PPT = schema("""{"type":"function","function":{"name":"create_ppt","description":"生成一个 PPT (.pptx) 演示文稿。","parameters":{"type":"object","properties":{"filename":{"type":"string","description":"文件名，不带扩展名"},"theme":{"type":"string","enum":["blue","green","orange","purple","dark"],"description":"配色主题"},"slides":{"type":"array","description":"每页幻灯片","items":{"type":"object","properties":{"layout":{"type":"string","enum":["title","section","content"]},"title":{"type":"string"},"bullets":{"type":"array","items":{"type":"string"}},"image_query":{"type":"string","description":"配图搜索词（英文更好），只用于正文页，不需要配图就不写"}},"required":["layout","title"]}}},"required":["filename","slides"]}}}""")

    val CREATE_EXCEL = schema("""{"type":"function","function":{"name":"create_excel","description":"生成一个 Excel (.xlsx) 表格。","parameters":{"type":"object","properties":{"filename":{"type":"string","description":"文件名，不带扩展名"},"sheets":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"rows":{"type":"array","description":"二维数组，第一行是表头","items":{"type":"array","items":{"type":"string","description":"单元格内容；数字也写成字符串，公式以 = 开头"}}}},"required":["name","rows"]}}},"required":["filename","sheets"]}}}""")

    val CREATE_PDF = schema("""{"type":"function","function":{"name":"create_pdf","description":"生成一个 PDF 文档。","parameters":{"type":"object","properties":{"filename":{"type":"string","description":"文件名，不带扩展名"},"markdown":{"type":"string","description":"文档全文，Markdown 格式"}},"required":["filename","markdown"]}}}""")

    val SEARCH_CHATS = schema("""{"type":"function","function":{"name":"search_chats","description":"在用户的全部聊天记录里按关键词搜索，返回相关的原话、日期和对话标题。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"关键词，多个用空格隔开"}},"required":["query"]}}}""")

    val RUN_JS = schema("""{"type":"function","function":{"name":"run_js","description":"运行一段 JavaScript 代码做精确计算，返回 console.log 的输出和最后一个表达式的值。","parameters":{"type":"object","properties":{"code":{"type":"string","description":"JavaScript 代码"}},"required":["code"]}}}""")

    val CREATE_CHART = schema("""{"type":"function","function":{"name":"create_chart","description":"把数据画成图表图片，显示在聊天里。","parameters":{"type":"object","properties":{"type":{"type":"string","enum":["bar","line","pie"]},"title":{"type":"string"},"unit":{"type":"string","description":"数值单位，可以为空"},"labels":{"type":"array","items":{"type":"string"},"description":"横轴类别或时间"},"series":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"values":{"type":"array","items":{"type":"number"}}},"required":["name","values"]}}},"required":["type","title","labels","series"]}}}""")

    val CREATE_REMINDER = schema("""{"type":"function","function":{"name":"create_reminder","description":"设置一个到点弹通知的提醒。","parameters":{"type":"object","properties":{"text":{"type":"string","description":"通知里显示的话"},"datetime":{"type":"string","description":"第一次提醒的时间，格式 yyyy-MM-dd HH:mm"},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly"]}},"required":["text","datetime"]}}}""")

    val CREATE_TASK = schema("""{"type":"function","function":{"name":"create_task","description":"设置一个定时任务：到点后你在后台自动执行 prompt，结果发给用户。","parameters":{"type":"object","properties":{"title":{"type":"string","description":"任务名，简短"},"prompt":{"type":"string","description":"到时候要执行的完整指令"},"datetime":{"type":"string","description":"第一次执行的时间，格式 yyyy-MM-dd HH:mm"},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly"]}},"required":["title","prompt","datetime"]}}}""")

    val LIST_ROUTINES = schema("""{"type":"function","function":{"name":"list_routines","description":"列出用户现有的提醒和定时任务。","parameters":{"type":"object","properties":{}}}}""")

    val DELETE_ROUTINE = schema("""{"type":"function","function":{"name":"delete_routine","description":"删除一个提醒或定时任务。","parameters":{"type":"object","properties":{"id":{"type":"string","description":"list_routines 返回的编号"}},"required":["id"]}}}""")

    val SEARCH_LIBRARY = schema("""{"type":"function","function":{"name":"search_library","description":"在用户的资料库里按关键词检索，返回最相关的原文段落和出处。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"关键词，多个用空格隔开"}},"required":["query"]}}}""")

    val LIST_LIBRARY = schema("""{"type":"function","function":{"name":"list_library","description":"列出资料库里的全部文档。","parameters":{"type":"object","properties":{}}}}""")

    val SAVE_FLASHCARDS = schema("""{"type":"function","function":{"name":"save_flashcards","description":"把知识点保存成复习卡片，用户之后会按间隔重复复习。","parameters":{"type":"object","properties":{"deck":{"type":"string","description":"卡组名，一般是主题"},"cards":{"type":"array","items":{"type":"object","properties":{"front":{"type":"string","description":"问题"},"back":{"type":"string","description":"答案"}},"required":["front","back"]}}},"required":["deck","cards"]}}}""")

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
