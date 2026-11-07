package com.jack.ai.data

/**
 * 新对话空白页上的「做生意」入口：点一下就把这句话发出去。话说得短、像本人说的，
 * 要问清楚的（卖什么、多少钱、卖给谁、什么口气）交给 Kavi 用问题卡片问（见提示词里的【Business help】）。
 */
object BizStarts {
    data class Start(val key: String, val title: String, val prompt: String, val tint: Long)

    val ALL = listOf(
        Start("moments", "发朋友圈", "帮我写一条发朋友圈的卖货文案", 0xFF22A06B),
        Start("xhs", "写小红书笔记", "帮我写一篇小红书笔记，推广我的产品", 0xFFEF5A5A),
        Start("douyin", "抖音视频脚本", "帮我写一个抖音短视频的脚本，推广我的产品", 0xFF3B82F6),
        Start("reply", "回客户消息", "客户发来一条消息，帮我想想怎么回", 0xFF8B5CF6),
        Start("intro", "产品介绍", "帮我写一份产品介绍，发给客户看", 0xFFE8A33D),
        Start("quote", "做报价单", "帮我做一份报价单", 0xFF0EA5A4),
    )
}
