package com.jack.ai.chat

import com.jack.ai.data.BUILTIN_SKILLS
import com.jack.ai.data.PhoneFolder
import com.jack.ai.data.Provider
import com.jack.ai.data.Settings
import com.jack.ai.data.SkillIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {
    private val skills = BUILTIN_SKILLS.filter { it.id in setOf(SkillIds.BROWSER, SkillIds.TABLES, SkillIds.MAIL) }

    @Test
    fun listsOnlyWhatIsOn() {
        val t = Capabilities.text(Settings(), skills, listOf("web_search", "download_file", "convert_file", "use_skill"))
        assertTrue(t, t.startsWith("【What you can do】"))
        assertTrue(t.contains("download_file") && t.contains("「操作浏览器」") && t.contains("「表格」"))
        // 没开的不写；没配语音服务就说清去哪配；没授权文件夹就告诉它入口
        assertFalse(t.contains("phone_files")); assertFalse(t.contains("run_python")); assertFalse(t.contains("拍票据"))
        assertTrue(t.contains("add SiliconFlow")); assertTrue(t.contains("设置 → 手机文件夹"))
        assertFalse(t.contains("self_check"))
        assertTrue(Capabilities.text(Settings(), skills, listOf("self_check")).contains("check the real state with self_check"))
    }

    @Test
    fun namesGrantedFolders() {
        val s = Settings(phoneFolders = listOf(PhoneFolder("content://a", "Download"), PhoneFolder("content://b", "工作")),
            providers = listOf(Provider(name = "硅基流动", baseUrl = "https://api.siliconflow.cn/v1", apiKey = "k")))
        val t = Capabilities.text(s, skills, listOf("convert_file", "phone_files"))
        assertTrue(t, t.contains("authorized: Download, 工作"))
        assertFalse(t.contains("add SiliconFlow")); assertFalse(t.contains("设置 → 手机文件夹"))
    }
}
