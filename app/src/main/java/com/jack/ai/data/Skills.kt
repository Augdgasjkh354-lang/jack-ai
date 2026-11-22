package com.jack.ai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.jack.ai.data.integrations.GitHub

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
    /** Kavi 自己做完事总结出来的（save_skill 写的）：它只能改、删自己写的这些。 */
    val byKavi: Boolean = false,
    /** 从 GitHub 装的：「作者/项目@分支:技能文件夹」，同步时按它去拉；自己加的、内置的是空的。 */
    val source: String = "",
    /** 装的那一版的指纹（技能文件夹里每个文件的 blob sha 合起来），同步时比它判断有没有更新。 */
    val sourceHash: String = "",
    /** 跟着技能一起装进来的资料（references/… 这类），相对技能文件夹的路径；用 use_skill 的 file 读。 */
    val files: List<String> = emptyList(),
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
    const val MAIL = "builtin-mail"
    const val TABLES = "builtin-tables"
    const val TRAIN = "builtin-train"
    const val APP = "builtin-app"
    const val NOTION = "builtin-notion"
    const val BROWSER = "builtin-browser"
    const val GITHUB = "builtin-github"
    const val SALES_LOG = "builtin-sales-log"
    const val SALES_ADVISOR = "builtin-sales-advisor"
    const val SALES_REVIEW = "builtin-sales-review"
    const val FLOWS = "builtin-browser-flows"
    const val RECEIPTS = "builtin-receipts"
    const val CODE = "builtin-code"
    const val JOB = "builtin-job"
    const val DESIGN = "builtin-design"
    const val TONE = "builtin-ai-tone"
    /**
     * 不是技能：手机工具的完整说明（很长）用到才给。开局只给一个短入口，第一次调用 phone 之后换成完整的，
     * 之后这个对话一直带着。思路来自 Mika 的工具分层（entry 工具），Apache-2.0。
     */
    const val PHONE_FULL = "tool-phone-full"
    const val XHS = "builtin-xhs"
    const val XHS_VISUAL = "builtin-xhs-visual"
    const val REPURPOSE = "builtin-repurpose"
    const val DIAGRAM_DESIGN = "builtin-diagram-design"
    const val AD_CHECK = "builtin-ad-check"
    const val VIDEO_PROMPT = "builtin-video-prompt"
    const val IMAGE_PROMPT = "builtin-image-prompt"
    const val NOVEL = "builtin-novel"
    const val DOUYIN = "builtin-douyin"
    const val VIDEO_EDIT = "builtin-video-edit"
    const val CONTRACT = "builtin-contract"
    const val XIANYU = "builtin-xianyu"
    const val AESTHETIC = "builtin-aesthetic"

    /**
     * 这些技能的工具不在开场就给，等模型用 use_skill 读了做法才加进来（本对话之后一直带着）：
     * 平时用得少、工具说明又长，每次都摆在面前既占上下文，也让模型更容易选错工具。
     * 计算与图表只有画图（create_chart / create_diagram）算在这里，算数一直能用。
     */
    /**
     * 插件：本身带工具的能力（Word、邮箱、浏览器……）。不进「技能」清单和推荐，模型按工具说明去调；
     * 用法在第一次调它的工具时附上（见 TOOL_PLUGIN），工具要先打开的（ON_DEMAND 里的）还是用 use_skill 打开。
     * 剩下的是「技能」：一类活的做法（小红书运营、合同挑坑……），加上从 GitHub 装的、用户写的、Kavi 自己存的。
     */
    val PLUGINS = setOf(WEB, WORD, PPT, EXCEL, PDF, CHATS, ROUTINE, APP, DESIGN, TRAIN, GITHUB, BROWSER, FLOWS, NOTION,
        CALENDAR, MAIL, TABLES, CALC, PYTHON, CODE, LIBRARY, RECEIPTS)

    /** 一直带着的插件工具 → 插件：第一次调用时把插件的用法附在结果后面（每个对话一次）。 */
    val TOOL_PLUGIN = mapOf(
        "web_search" to WEB, "read_webpage" to WEB, "download_file" to WEB,
        "schedule" to ROUTINE, "cancel_schedule" to ROUTINE,
        "add_rows" to TABLES, "query_table" to TABLES, "edit_table" to TABLES,
        "run_python" to PYTHON, "run_shell" to PYTHON,
        "knowledge_base" to LIBRARY,
    )

    /**
     * 以前这些技能的工具要 use_skill 打开后才带上，可一加工具，工具说明就变了，DeepSeek 的缓存从开头附近就断：
     * 实测同一个对话中途打开「设计」后命中掉到 4%，新对话第一条也只有 3%。现在全部一直带着，工具说明在所有对话、
     * 整个对话里都一样；多出来的几千 token 几乎全命中缓存。机制留着，以后真要按需加再填回来。
     */
    val ON_DEMAND: Set<String> = emptySet()
}

/** 正式版没有的技能：浏览器、浏览器流程、在招聘 App 上打招呼（都要操作浏览器或其他 App）。 */
private val STORE_HIDDEN = setOf(SkillIds.BROWSER, SkillIds.FLOWS, SkillIds.JOB)

/** 当前开着的 skill：内置的看开关（联网还要有 Tavily 或 DeepSeek Key），自定义的看它自己的 enabled。 */
fun Settings.activeSkills(): List<Skill> =
    allSkills().filter {
        it.enabled && (it.id != SkillIds.FLOWS || SkillIds.BROWSER !in disabledSkills) && (it.id != SkillIds.WEB || exaKey.isNotBlank() || tavilyKey.isNotBlank() || apiKey.isNotBlank()) && (it.id != SkillIds.MAIL || mails.any { it.ready }) && (it.id != SkillIds.NOTION || notionToken.isNotBlank())
    }

/** 全部 skill（内置 + 自定义），enabled 反映当前开关状态。 */
fun Settings.allSkills(): List<Skill> =
    BUILTIN_SKILLS.filter { !com.jack.ai.Edition.STORE || it.id !in STORE_HIDDEN }.map { s ->
        s.copy(enabled = if (s.id == SkillIds.WEB) webSearch else s.id !in disabledSkills)
    } + customSkills
