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
    const val PYTHON = "builtin-python"
    const val CALENDAR = "builtin-calendar"
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
        description = "设置闹钟（“明早 7 点叫我起床”）、到点弹通知的提醒（“明天 9 点提醒我交报告”），或让你定时在后台自动干活（“每天早上 8 点给我整理 AI 新闻”）。",
        instructions = """提醒和定时任务的做法：
1. 只是到点通知用户一件事，用 create_reminder；需要你到点去搜索、整理、写东西的，用 create_task。
2. 时间写成 “yyyy-MM-dd HH:mm”，根据系统提示里的当前日期换算“明天”“下周一”“今晚”等说法。用户没说具体几点时，按常理挑一个（早上 9:00、晚上 20:00 等），并在回复里说明。
3. repeat 填 none（一次）、daily（每天）、weekdays（工作日）、weekly（每周同一天）。
4. 提醒的 text 写成到时候通知里显示的一句话，比如“该交周报了”。
5. 定时任务的 prompt 要写成完整、独立的指令，因为到时候你是在后台、看不到现在这段对话的。比如“搜索过去 24 小时 AI 领域的重要新闻，整理成 5 条简报，每条一句话加来源链接”。
6. 设置好后用一句话确认：什么时候、做什么。
7. 不赶时间的定时任务（整理资料、写总结之类）可以把 off_peak 设为 true：碰上 DeepSeek 高峰时段（工作日 9–12 点、14–18 点）会自动顺延到空闲时段执行，价格减半。用户要求准点的（比如早安问候、开会前提醒）不要开。
8. 用户想看或删除已有的提醒和任务时，先用 list_routines 查，再用 delete_routine 删。
9. 用户说“闹钟”“叫我起床”“X 点叫醒我”时，用 set_alarm 在手机自带的时钟里设真正的闹钟（会一直响到用户按掉）；只是“提醒我做某事”就用 create_reminder。
   - set_alarm 只需要时和分（24 小时制），days 不填是一次性闹钟，填了就是每周这几天重复（1=周一 … 7=周日）。
   - 要关掉或改闹钟：用 cancel_alarm 按时间或标签关掉旧的，需要时再 set_alarm 设新的。手机不允许读取已有闹钟列表，你看不到用户有哪些闹钟，不知道时就问用户。""",
    ),
    Skill(
        id = SkillIds.CALENDAR, builtin = true, name = "日历",
        description = "在手机系统日历里添加、查看、删除日程（“下周三下午三点开会”“我明天有什么安排”“把周五的聚餐取消”）。",
        instructions = """日历的做法：
1. 用户要记一个有具体时间的安排（开会、约会、考试、出差、生日），用 add_calendar_event 写进手机日历。只是想到点被提醒一下、不算日程的，用提醒技能。
2. start 写成 “yyyy-MM-dd HH:mm”，根据系统提示里的当前日期换算“明天”“下周三”等说法；全天的事（生日、假期、出差一整天）只写 “yyyy-MM-dd” 并把 all_day 设为 true。没说结束时间就不填 end（默认 1 小时）。没说几点时按常理挑一个并在回复里说明。
3. remind_minutes 是提前多少分钟提醒，默认 15；用户说了提前多久就按用户的来，不要提醒写 -1。
4. 用户问“我某天有什么安排”时，用 list_calendar_events 查，start_date 写 yyyy-MM-dd，days 写查几天。回答时按时间顺序简洁列出。
5. 要删除或修改日程，先用 list_calendar_events 找到它的编号，再用 delete_calendar_event 删掉；修改就是删掉旧的再加新的。删除前如果有多个相似的，先问用户是哪一个。
6. 工具返回“没有日历权限”时，告诉用户去左侧菜单「提醒和任务」页面点「允许读写日历」。添加日程时没有权限会打开日历 App 让用户自己点保存，照实告诉用户。
7. 加好后用一句话确认：哪天几点、什么事。""",
    ),
    Skill(
        id = SkillIds.CALC, builtin = true, name = "计算与图表",
        description = "真正运行代码来算数、统计、处理数据；把数据画成柱状图、折线图、饼图；画思维导图（树状图）和流程图。涉及精确计算（贷款、利息、汇率换算、统计）或用户想要图时使用。",
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
4. 图生成后会直接显示在聊天里，你用一两句话点出图里最值得注意的地方。
5. 思维导图、知识结构、分类层级用 create_diagram，type=mindmap：
   - outline 写成缩进的 Markdown 列表，每层多缩进 2 个空格，第一行是中心主题。
   - 每个节点简短（一般不超过 15 个字），层级不要超过 4 层，一级分支 3 到 7 个最好看。
6. 步骤、流程、决策过程用 create_diagram，type=flowchart：
   - nodes 每个有 id、label、shape：开始和结束用 round，判断用 diamond，普通步骤用 box。
   - edges 写 from、to，判断的分支在 label 写“是”“否”之类。
   - direction 默认 TB（从上到下），步骤少且想横着看时用 LR。
7. 图会保存在对话文件夹里。用户要改图时，用 edit_file 改它的底稿，不要重新画一张。""",
    ),
    Skill(
        id = SkillIds.PYTHON, builtin = true, name = "Python",
        description = "在手机上真正运行 Python 代码，处理对话文件夹里的文件：分析 Excel/CSV 数据、批量处理和转换文件、用 matplotlib 画专业图表、复杂计算。",
        instructions = """Python 的做法：
1. 用 run_python 运行代码。工作目录就是对话文件夹，用户发来的文件直接按文件名打开，比如 pd.read_excel("销售.xlsx")。
2. 可用的库：Python 3.10 标准库、numpy、pandas、matplotlib、openpyxl。没有别的第三方库，也不能 pip 安装。
3. 用 print() 输出结果；最后一行是表达式时会自动打印它的值。数据量大时只打印摘要（head()、describe()、统计结果），不要把整张表打印出来。
4. 要给用户的结果存成文件放在当前目录：图表用 plt.savefig("名字.png", bbox_inches="tight")，表格用 df.to_excel("名字.xlsx", index=False) 或 to_csv。新建或改动的文件会自动显示在聊天里。图里可以直接写中文。
5. 单次运行最多 90 秒。出错就看报错改代码再试，最多 3 次。
6. 回答时说结论和生成了什么文件，不要把代码贴给用户（除非用户想看）。
7. 简单的算数用计算工具就行；只是画个简单的柱状图、折线图、饼图，用 create_chart 更快。""",
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

    val CREATE_DIAGRAM = schema("""{"type":"function","function":{"name":"create_diagram","description":"画思维导图（树状图）或流程图，生成图片显示在聊天里。","parameters":{"type":"object","properties":{"type":{"type":"string","enum":["mindmap","flowchart"]},"title":{"type":"string","description":"图的标题，可以为空"},"outline":{"type":"string","description":"mindmap 用：缩进的 Markdown 列表，第一行是中心主题"},"nodes":{"type":"array","description":"flowchart 用","items":{"type":"object","properties":{"id":{"type":"string"},"label":{"type":"string"},"shape":{"type":"string","enum":["box","round","diamond"]}},"required":["id","label"]}},"edges":{"type":"array","description":"flowchart 用","items":{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"},"label":{"type":"string"}},"required":["from","to"]}},"direction":{"type":"string","enum":["TB","LR"]}},"required":["type"]}}}""")

    val RUN_PYTHON = schema("""{"type":"function","function":{"name":"run_python","description":"在对话文件夹里运行一段 Python 代码，返回 print 的输出和报错。可用 numpy、pandas、matplotlib、openpyxl。","parameters":{"type":"object","properties":{"code":{"type":"string","description":"Python 代码"}},"required":["code"]}}}""")

    val SET_ALARM = schema("""{"type":"function","function":{"name":"set_alarm","description":"在手机自带的时钟 App 里设一个闹钟。","parameters":{"type":"object","properties":{"time":{"type":"string","description":"HH:mm，24 小时制"},"label":{"type":"string","description":"闹钟标签，可以为空"},"days":{"type":"array","items":{"type":"integer"},"description":"每周重复的日子，1=周一 … 7=周日；一次性闹钟不填"}},"required":["time"]}}}""")

    val CANCEL_ALARM = schema("""{"type":"function","function":{"name":"cancel_alarm","description":"关掉时钟 App 里的一个闹钟，按时间或标签找。","parameters":{"type":"object","properties":{"time":{"type":"string","description":"HH:mm"},"label":{"type":"string"}}}}}""")

    val LIST_FILES = schema("""{"type":"function","function":{"name":"list_files","description":"列出本对话文件夹里的文件。","parameters":{"type":"object","properties":{}}}}""")

    val READ_FILE = schema("""{"type":"function","function":{"name":"read_file","description":"读取对话文件夹里一个文件的文字内容。你生成的 Word/PPT/Excel/PDF/图会返回它的底稿。可以只读一部分行。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"文件名"},"start_line":{"type":"integer","description":"从第几行开始，默认 1"},"max_lines":{"type":"integer","description":"最多读多少行，默认 400"}},"required":["name"]}}}""")

    val WRITE_FILE = schema("""{"type":"function","function":{"name":"write_file","description":"在对话文件夹里新建或整个覆盖一个文字文件（代码、md、txt、csv、json、html 等）。只改一小部分时用 edit_file。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"文件名，带扩展名"},"content":{"type":"string"}},"required":["name","content"]}}}""")

    val EDIT_FILE = schema("""{"type":"function","function":{"name":"edit_file","description":"局部修改对话文件夹里的文件：把 old_text 这一段原文换成 new_text，其他内容不动。适用于文字/代码文件、Word 文件，以及你生成的 Word/PPT/Excel/PDF/图（改的是底稿，改完自动重新生成）。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"文件名"},"old_text":{"type":"string","description":"要替换的原文，必须和文件里的一字不差，并且在文件里唯一"},"new_text":{"type":"string","description":"替换成的新内容，删除就写空字符串"},"replace_all":{"type":"boolean","description":"原文出现多次时全部替换"}},"required":["name","old_text","new_text"]}}}""")

    val CREATE_REMINDER = schema("""{"type":"function","function":{"name":"create_reminder","description":"设置一个到点弹通知的提醒。","parameters":{"type":"object","properties":{"text":{"type":"string","description":"通知里显示的话"},"datetime":{"type":"string","description":"第一次提醒的时间，格式 yyyy-MM-dd HH:mm"},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly"]}},"required":["text","datetime"]}}}""")

    val CREATE_TASK = schema("""{"type":"function","function":{"name":"create_task","description":"设置一个定时任务：到点后你在后台自动执行 prompt，结果发给用户。","parameters":{"type":"object","properties":{"title":{"type":"string","description":"任务名，简短"},"prompt":{"type":"string","description":"到时候要执行的完整指令"},"datetime":{"type":"string","description":"第一次执行的时间，格式 yyyy-MM-dd HH:mm"},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly"]},"off_peak":{"type":"boolean","description":"碰上高峰时段就顺延到空闲时段再执行，省一半钱；不急的任务可以开"}},"required":["title","prompt","datetime"]}}}""")

    val LIST_ROUTINES = schema("""{"type":"function","function":{"name":"list_routines","description":"列出用户现有的提醒和定时任务。","parameters":{"type":"object","properties":{}}}}""")

    val DELETE_ROUTINE = schema("""{"type":"function","function":{"name":"delete_routine","description":"删除一个提醒或定时任务。","parameters":{"type":"object","properties":{"id":{"type":"string","description":"list_routines 返回的编号"}},"required":["id"]}}}""")

    val SEARCH_LIBRARY = schema("""{"type":"function","function":{"name":"search_library","description":"在用户的资料库里按关键词检索，返回最相关的原文段落和出处。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"关键词，多个用空格隔开"}},"required":["query"]}}}""")

    val LIST_LIBRARY = schema("""{"type":"function","function":{"name":"list_library","description":"列出资料库里的全部文档。","parameters":{"type":"object","properties":{}}}}""")

    val SAVE_FLASHCARDS = schema("""{"type":"function","function":{"name":"save_flashcards","description":"把知识点保存成复习卡片，用户之后会按间隔重复复习。","parameters":{"type":"object","properties":{"deck":{"type":"string","description":"卡组名，一般是主题"},"cards":{"type":"array","items":{"type":"object","properties":{"front":{"type":"string","description":"问题"},"back":{"type":"string","description":"答案"}},"required":["front","back"]}}},"required":["deck","cards"]}}}""")

    val SEARCH_HISTORY = schema("""{"type":"function","function":{"name":"search_history","description":"在本对话较早的原始聊天记录里按关键词查找原话和细节。提要里找不到的信息用它查。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"关键词，多个用空格隔开"}},"required":["query"]}}}""")

    val ADD_EVENT = schema("""{"type":"function","function":{"name":"add_calendar_event","description":"在手机日历里添加一个日程。","parameters":{"type":"object","properties":{"title":{"type":"string","description":"日程标题"},"start":{"type":"string","description":"开始时间 yyyy-MM-dd HH:mm；全天日程只写 yyyy-MM-dd"},"end":{"type":"string","description":"结束时间，格式同 start，可以不填"},"all_day":{"type":"boolean"},"location":{"type":"string"},"notes":{"type":"string","description":"备注"},"remind_minutes":{"type":"integer","description":"提前多少分钟提醒，默认 15，不要提醒写 -1"}},"required":["title","start"]}}}""")

    val LIST_EVENTS = schema("""{"type":"function","function":{"name":"list_calendar_events","description":"查看手机日历里一段时间内的日程。","parameters":{"type":"object","properties":{"start_date":{"type":"string","description":"从哪天开始，yyyy-MM-dd，默认今天"},"days":{"type":"integer","description":"查几天，默认 1，最多 62"}}}}}""")

    val DELETE_EVENT = schema("""{"type":"function","function":{"name":"delete_calendar_event","description":"删除手机日历里的一个日程。","parameters":{"type":"object","properties":{"id":{"type":"string","description":"list_calendar_events 返回的编号"}},"required":["id"]}}}""")

    val SEARCH_PROJECT = schema("""{"type":"function","function":{"name":"search_project","description":"在当前项目的大份资料里按关键词检索，返回最相关的原文段落和出处。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"关键词，多个用空格隔开"}},"required":["query"]}}}""")

    val SAVE_MEMORY = schema("""{"type":"function","function":{"name":"save_memory","description":"存一条记忆。","parameters":{"type":"object","properties":{"content":{"type":"string","description":"要记住的内容，一句完整、独立能看懂的话"},"scope":{"type":"string","enum":["chat","project","global"],"description":"存到哪：chat 本对话记忆，project 项目记忆，global 全局记忆"}},"required":["content","scope"]}}}""")
}

/** 当前开着的 skill：内置的看开关（联网还要有 Tavily Key），自定义的看它自己的 enabled。 */
fun Settings.activeSkills(): List<Skill> =
    allSkills().filter { it.enabled && (it.id != SkillIds.WEB || tavilyKey.isNotBlank()) }

/** 全部 skill（内置 + 自定义），enabled 反映当前开关状态。 */
fun Settings.allSkills(): List<Skill> =
    BUILTIN_SKILLS.map { s ->
        s.copy(enabled = if (s.id == SkillIds.WEB) webSearch else s.id !in disabledSkills)
    } + customSkills
