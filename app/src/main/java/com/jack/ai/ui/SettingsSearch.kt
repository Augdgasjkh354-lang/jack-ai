package com.jack.ai.ui

/**
 * 设置里的搜索：每一页记一串能搜到它的词（页里的设置名、常见叫法），打一个词就列出在哪一页，点了直接进去。
 * [key] 是 SettingsPage 的名字，或者 "SKILLS"、"USAGE"（这两个是单独的页面）。
 * [rows] 是页里能直接展开的那一行（比如「连接」里的邮箱）：词 → 行名。
 */
internal data class SettingsTopic(val key: String, val title: String, val words: List<String>, val rows: Map<String, String> = emptyMap())

/** 搜到的一项：哪一页、因为哪个词搜到的（标题本身命中时是 null）、要展开的那一行。 */
internal data class SettingsHit(val topic: SettingsTopic, val word: String?, val row: String?)

internal val SETTINGS_TOPICS = listOf(
    SettingsTopic("PROFILE", "个性化", listOf("名字", "称呼", "关于你", "希望 AI 怎么回答", "回答风格", "说话方式", "职业")),
    SettingsTopic("KEY", "DeepSeek API Key", listOf("DeepSeek", "API Key", "余额", "查余额", "语音输入", "转文字", "离线语音识别", "话筒", "朗读", "女声", "男声")),
    SettingsTopic("PROVIDERS", "其他模型服务", listOf("模型服务", "OpenAI", "ChatGPT", "Claude", "Gemini", "Kimi", "通义", "硅基流动", "中转", "免费额度", "添加模型")),
    SettingsTopic("MODEL", "模型和思考", listOf("模型", "思考", "深度思考", "备用模型", "思考摘要", "折叠思考", "快捷入口", "文案")),
    SettingsTopic("LOCAL", "本地模型", listOf("本地模型", "向量", "离线", "嵌入", "下载模型")),
    SettingsTopic("COMPRESS", "长对话自动压缩", listOf("压缩", "上下文", "长对话", "提要", "token")),
    SettingsTopic("CONNECT", "连接", listOf("联网搜索", "搜索", "Tavily", "Exa", "邮箱", "邮件", "GitHub", "Notion", "微信", "MCP", "连接器", "代理", "VPN", "节点", "订阅", "翻墙"),
        rows = mapOf(
            "联网搜索" to "联网搜索", "搜索" to "联网搜索", "Tavily" to "联网搜索", "Exa" to "联网搜索",
            "邮箱" to "邮箱", "邮件" to "邮箱", "GitHub" to "GitHub", "Notion" to "Notion", "微信" to "微信消息",
            "MCP" to "MCP 连接器", "连接器" to "MCP 连接器",
            "代理" to "Kavi 专用代理（VPN）", "VPN" to "Kavi 专用代理（VPN）", "节点" to "Kavi 专用代理（VPN）",
            "订阅" to "Kavi 专用代理（VPN）", "翻墙" to "Kavi 专用代理（VPN）",
        )),
    SettingsTopic("SKILLS", "技能", listOf("技能", "插件")),
    SettingsTopic("LINUX", "Linux 沙箱", listOf("Linux", "终端", "沙箱", "Python", "pip", "Node", "git", "命令行")),
    SettingsTopic("REMOTE", "远程连接", listOf("远程", "iPhone", "电脑", "配对", "邀请", "站点")),
    SettingsTopic("PHONE", "操作其他 App", listOf("操作其他 App", "无障碍", "悬浮球", "自动点", "操作节奏", "后台")),
    SettingsTopic("ASK", "问这一屏", listOf("问这一屏", "屏幕", "截屏", "当前屏幕")),
    SettingsTopic("PHONEDATA", "手机数据", listOf("手机数据", "通知", "联系人", "位置", "定位", "使用时长")),
    SettingsTopic("FOLDERS", "手机文件夹", listOf("文件夹", "手机文件", "存储", "授权文件夹")),
    SettingsTopic("LOOK", "外观", listOf("外观", "主题", "深色", "浅色", "暗色", "夜间", "跟随系统", "主题色", "气泡", "字体", "字号", "桌面图标", "图标", "链接", "浏览器打开")),
    SettingsTopic("BACKGROUND", "后台运行", listOf("后台运行", "后台", "省电", "电池", "耗电", "自启动", "被杀", "保活", "定时任务不执行", "锁屏")),
    SettingsTopic("USAGE", "统计", listOf("统计", "用量", "花费", "花了多少", "预算", "额度", "账单")),
    SettingsTopic("PRIVACY", "隐私和数据", listOf("隐私", "应用锁", "指纹", "密码", "完全授权", "以后都允许", "重置记忆", "备份", "恢复", "导出")),
    SettingsTopic("UPDATE", "版本更新", listOf("更新", "版本", "新版本", "升级")),
    SettingsTopic("ADVANCED", "高级", listOf("新手教学", "教程", "接口地址", "清空聊天记录", "清空全部")),
    SettingsTopic("LEGAL", "用户协议和隐私政策", listOf("用户协议", "隐私政策")),
)

/**
 * 按词找设置：标题命中的排前面，再是关键词命中的；关键词包含搜的字、或者搜的话里含着关键词（「怎么改字体」也能搜到「外观」）都算。
 * 不分大小写，空白忽略。一页只出一次。
 */
internal fun searchSettings(query: String, topics: List<SettingsTopic> = SETTINGS_TOPICS): List<SettingsHit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val byTitle = topics.filter { q in it.title.lowercase() }.map { SettingsHit(it, null, null) }
    val byWord = topics.filter { t -> byTitle.none { it.topic == t } }.mapNotNull { t ->
        val w = t.words.firstOrNull { q in it.lowercase() } ?: t.words.firstOrNull { it.length >= 2 && it.lowercase() in q }
        w?.let { SettingsHit(t, it, t.rows[it]) }
    }
    return byTitle + byWord
}
