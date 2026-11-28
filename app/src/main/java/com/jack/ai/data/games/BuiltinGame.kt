package com.jack.ai.data.games

/**
 * App 自带的网页游戏：安装包里的一个 HTML，第一次打开时拷进它专门的对话文件夹。
 * 它就是个普通的小工具，Kavi 在那个对话里能用 edit_file 改规则、对手、样子；改坏了能「恢复原版」。
 * [version] 写在网页的 <meta name="kavi-builtin"> 里：自带的换了新版，打开时旧的那份改名成「（旧版）」留着，再放新的。
 */
enum class BuiltinGame(val convId: String, val title: String, val asset: String, val version: String, val blurb: String, val intro: String) {
    HOLDEM(
        convId = "game_holdem",
        title = "德州扑克",
        asset = "games/texas-holdem.html",
        version = "holdem-11",
        blurb = "6 人桌，你和 5 个对手；带训练营和复盘，对手也能接模型",
        intro = "这是德州扑克，点上面的卡片「全屏」就能开打：6 人桌，你和 5 个风格不同的对手，四个场次按职业晋级解锁。\n\n" +
            "对手默认是规则 AI（快、不花钱），每个人有自己的性格，会按性格说台词、互相接话，你点底栏的 💬 也能跟他们说话；大厅里「对手用什么想」能改性格，也能把任何一个换成模型（翻牌前自动打，翻牌后才让模型想，省钱）。" +
            "打完的牌在回放里能点「让 Kavi 讲讲这手怎么打」；「训练」页有分技能的练习，还会从你真打的牌里找毛病。\n\n" +
            "想改什么直接在这里跟我说，比如「把 Echo 改成说话很毒舌的老赌棍」「新手场盲注改小一点」「牌桌换成蓝色桌布」，我来改这个游戏文件。" +
            "改坏了可以在侧边栏「游戏」里点它右边的「⋮ → 恢复原版」。",
    ),
}
