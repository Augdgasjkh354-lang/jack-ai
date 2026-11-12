package com.jack.ai.data

/**
 * 新对话空白页上的「做生意」入口：点一下就把这句话发出去。话说得短、像本人说的，
 * 要问清楚的（卖什么、多少钱、卖给谁、什么口气）交给 Kavi 用问题卡片问（见提示词里的【Business help】）。
 */
object BizStarts {
    data class Start(val key: String, val title: String, val prompt: String, val tint: Long)

    val ALL = listOf(
        // 只留三个最常用的（Jack 定的）；抖音脚本、回客户、报价单直接跟 Kavi 说就行
        Start("xhs", "写小红书笔记", "帮我写一篇小红书笔记，推广我的产品", 0xFFEF5A5A),
        Start("intro", "产品介绍", "帮我写一份产品介绍，发给客户看", 0xFFE8A33D),
        Start("moments", "发营销朋友圈", "帮我写一条发朋友圈的卖货文案", 0xFF22A06B),
    )
}
