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

    /** 空白页弹幕里另外飘的那些：生意、生活、手机上的事都有，点哪条发哪条。 */
    val MORE = listOf(
        Start("douyin", "写抖音口播脚本", "帮我写一个抖音口播脚本，30 秒左右，卖我的产品", 0xFFEF5A5A),
        Start("reply", "回客户的消息", "客户嫌贵想砍价，帮我想几句得体的回复", 0xFF22A06B),
        Start("quote", "做一份报价单", "帮我做一份报价单表格", 0xFFE8A33D),
        Start("poster", "做一张促销海报", "帮我做一张促销海报", 0xFFEF5A5A),
        Start("title", "起 10 个爆款标题", "帮我给产品起 10 个吸引人的标题", 0xFFE8A33D),
        Start("live", "写直播话术", "帮我写一段直播间卖货的话术", 0xFFEF5A5A),
        Start("compete", "看看同行怎么卖", "帮我查查同行是怎么卖这类产品的，价格和卖点各是什么", 0xFF2F6FEB),
        Start("ppt", "做一份汇报 PPT", "帮我做一份工作汇报 PPT", 0xFFE8A33D),
        Start("excel", "整理成表格", "帮我把一堆数据整理成 Excel 表格", 0xFF22A06B),
        Start("weekly", "写本周周报", "帮我写本周的周报", 0xFF2F6FEB),
        Start("email", "写一封正式邮件", "帮我写一封正式的商务邮件", 0xFF2F6FEB),
        Start("remind", "明早 8 点提醒我", "明天早上 8 点提醒我一件事", 0xFF7C4DEB),
        Start("news", "今天有什么新闻", "今天有什么值得看的新闻？挑几条讲讲", 0xFF0C8FD6),
        Start("weather", "这周天气怎么样", "这周天气怎么样？要不要带伞", 0xFF0C8FD6),
        Start("dinner", "今晚吃什么", "今晚吃什么？给我几个简单好做的家常菜", 0xFFE0561A),
        Start("trip", "规划一次周末出游", "帮我规划一次周末两天的短途出游", 0xFF0C8FD6),
        Start("translate", "翻译一段话", "帮我把一段话翻译成英文，要地道", 0xFF7C4DEB),
        Start("photo", "看看这张图", "我发一张图，你帮我看看", 0xFF7C4DEB),
        Start("summary", "总结一篇文章", "我发一篇文章或链接，帮我总结要点", 0xFF2F6FEB),
        Start("watch", "帮我盯一个价格", "帮我盯着一个商品的价格，降价了告诉我", 0xFF7C4DEB),
        Start("buy", "这两个哪个好", "两个东西拿不定主意买哪个，帮我对比一下", 0xFFE0561A),
        Start("fit", "定个减脂计划", "帮我定一个一个月的减脂计划，吃和练都要有", 0xFF22A06B),
        Start("sleep", "最近睡不好", "最近总是睡不好，有什么办法", 0xFF7C4DEB),
        Start("learn", "教我一个新东西", "用最简单的话教我一个有意思的新知识", 0xFF0C8FD6),
        Start("words", "帮我回一句难回的话", "有句话不知道怎么回，帮我想想", 0xFF22A06B),
        Start("birthday", "写一段生日祝福", "帮我写一段走心的生日祝福", 0xFFEF5A5A),
        Start("gift", "送什么礼物好", "想送人一个礼物，帮我出出主意", 0xFFE0561A),
        Start("story", "讲个睡前故事", "给我讲一个温暖的睡前故事", 0xFF7C4DEB),
        Start("money", "记一笔账", "帮我记一笔账", 0xFF22A06B),
        Start("plan", "排一下明天的事", "帮我把明天要做的事排一排", 0xFF2F6FEB),
        Start("video", "剪一个短视频", "帮我把几段素材剪成一个短视频", 0xFFEF5A5A),
        Start("idea", "想个副业点子", "帮我想几个适合我的副业点子", 0xFFE8A33D),
    )

    /** 弹幕里飘的全部：常用的三条加上 [MORE]。 */
    val DRIFT = ALL + MORE
}
