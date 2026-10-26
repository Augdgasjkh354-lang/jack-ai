package com.jack.ai.chat

import com.jack.ai.api.Transcriber
import com.jack.ai.data.Settings
import com.jack.ai.data.Skill
import com.jack.ai.data.SkillIds

/**
 * 能力清单：Kavi 现在能做哪些事、用哪个工具或技能，一行一件。只列这次真开着的，不讲做法（做法在技能里）。
 * 让它别漏用、别说「做不到」，也知道该把哪个 App 入口告诉用户。
 */
object Capabilities {
    fun text(settings: Settings, skills: List<Skill>, tools: List<String>): String {
        fun skill(id: String) = skills.firstOrNull { it.id == id }?.name
        val lines = mutableListOf<String>()
        if ("web_search" in tools) lines += "查资料：web_search；读网页全文：read_webpage"
        if ("download_file" in tools) lines += "按网址下载文件（含 m3u8 视频）：download_file，存到手机加 to=phone"
        skill(SkillIds.BROWSER)?.let { lines += "操作网页、存网页里的视频音乐（download_media）、整页图片附件（download_all）：「$it」" }
        if ("convert_file" in tools) lines += "图片转 PDF、合并拆分 PDF、压缩图片、剪视频压视频、取声音、录音和视频转文字（transcribe，用户发来录音、视频要内容时先转）：convert_file" +
            if (Transcriber.engine(settings.providers) == null) "（转文字还没配服务，要用时请用户在 设置 → 其他模型服务 添加硅基流动）" else ""
        if ("phone_files" in tools) lines += "手机文件夹（已授权：${settings.phoneFolders.joinToString("、") { it.name }}）：phone_files，读写前先 import 拷进对话文件夹"
        if ("run_python" in tools) lines += "算数、处理表格数据、画图：run_python"
        else if ("run_js" in tools) lines += "精确计算：run_js"
        skill(SkillIds.TABLES)?.let { lines += "记账、清单、客户这类表格：「$it」" }
        skill(SkillIds.RECEIPTS)?.let { lines += "照片里的发票、小票、快递单、名片记进表格：「$it」" }
        if ("schedule" in tools) lines += "提醒、定时任务、盯网页变化：schedule"
        listOf(SkillIds.MAIL, SkillIds.CALENDAR, SkillIds.GITHUB, SkillIds.NOTION, SkillIds.TRAIN, SkillIds.WORD, SkillIds.PPT, SkillIds.EXCEL, SkillIds.PDF, SkillIds.APP)
            .mapNotNull { skill(it) }.takeIf { it.isNotEmpty() }?.let { lines += "其他技能：" + it.joinToString("、") { n -> "「$n」" } }
        // App 里用户自己点的入口：用得上时告诉用户
        lines += "用户那边的入口：输入框「+ → 录音纪要」（录完你会收到录音，转文字出纪要）；别的 App 里「分享 → 用 Kavi 下载 / 用 Kavi 总结」；侧边栏「下载」看下过的文件" +
            if ("phone_files" !in tools) "；设置 → 手机文件夹（授权后你才能读写手机里的文件）" else ""
        if ("self_check" in tools) lines += "拿不准自己能不能做、工具为什么报错：先 self_check 看真实状态，再说「做不到」"
        return "【能力清单】\n" + lines.joinToString("\n") { "- $it" }
    }
}
