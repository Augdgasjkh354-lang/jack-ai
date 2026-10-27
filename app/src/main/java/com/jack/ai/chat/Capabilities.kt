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
        if ("web_search" in tools) lines += "Look things up: web_search; read a full web page: read_webpage"
        if ("download_file" in tools) lines += "Download a file by URL (m3u8 video included): download_file; add to=phone to save it to the phone"
        skill(SkillIds.BROWSER)?.let { lines += "Operate web pages, save video or music from a page (download_media), grab all images and attachments on a page (download_all): 「$it」" }
        if ("convert_file" in tools) lines += "Images to PDF, merge or split PDFs, compress images, trim or compress video, extract audio, transcribe recordings and video (transcribe; when {user} sends a recording or video and wants its content, transcribe it first): convert_file" +
            if (Transcriber.engine(settings.providers) == null) " (transcription has no service set up yet; when it's needed, ask {user} to add SiliconFlow under 设置 → 其他模型服务)" else ""
        if ("phone_files" in tools) lines += "Phone folders (authorized: ${settings.phoneFolders.joinToString(", ") { it.name }}): phone_files; import a file into the conversation folder before reading or editing it"
        if ("run_python" in tools) lines += "Arithmetic, processing tabular data, plotting: run_python"
        else if ("run_js" in tools) lines += "Exact calculation: run_js"
        skill(SkillIds.TABLES)?.let { lines += "Bookkeeping, lists, clients and other tables: 「$it」" }
        skill(SkillIds.RECEIPTS)?.let { lines += "Record invoices, receipts, delivery slips and business cards from photos into tables: 「$it」" }
        if ("schedule" in tools) lines += "Reminders, scheduled tasks, watching a web page for changes: schedule"
        listOf(SkillIds.MAIL, SkillIds.CALENDAR, SkillIds.GITHUB, SkillIds.NOTION, SkillIds.TRAIN, SkillIds.WORD, SkillIds.PPT, SkillIds.EXCEL, SkillIds.PDF, SkillIds.APP)
            .mapNotNull { skill(it) }.takeIf { it.isNotEmpty() }?.let { lines += "Other skills: " + it.joinToString(", ") { n -> "「$n」" } }
        // App 里用户自己点的入口：用得上时告诉用户
        lines += "Entry points on {user}'s side: 「+ → 录音纪要」 in the input box (you'll receive the recording; transcribe it and write up notes); 「分享 → 用 Kavi 下载 / 用 Kavi 总结」 in other apps; 「下载」 in the side menu for downloaded files" +
            if ("phone_files" !in tools) "; 设置 → 手机文件夹 (you can only read and write the phone's files after {user} authorizes a folder there)" else ""
        if ("self_check" in tools) lines += "Unsure whether you can do something, or why a tool failed: check the real state with self_check before saying you can't"
        return "【What you can do】\n" + lines.joinToString("\n") { "- $it" }
    }
}
