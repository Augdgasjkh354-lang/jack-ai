package com.jack.ai.data.games

/**
 * App 自带的网页游戏：安装包里的一个 HTML，第一次打开时拷进它专门的对话文件夹。
 * 它就是个普通的小工具，Kavi 在那个对话里能用 edit_file 改规则、对手、样子；改坏了能「恢复原版」。
 */
enum class BuiltinGame(val convId: String, val title: String, val asset: String, val blurb: String, val intro: String) {
    HOLDEM(
        convId = "game_holdem",
        title = "德州扑克",
        asset = "games/texas-holdem.html",
        blurb = "你和 4 个对手，每个对手可以用不同的模型，各想各的",
        intro = "这是德州扑克，点上面的卡片「全屏」就能开打：你和 4 个对手，每个对手可以选不同的模型，上下文各自独立。\n\n" +
            "想改什么直接在这里跟我说，比如「把老K改成说话很毒舌的老赌棍」「盲注改成 50/100」「牌桌换成蓝色桌布」「加一个看牌记录」，我来改这个游戏文件。" +
            "改坏了可以在侧边栏「游戏」里点它右边的「⋮ → 恢复原版」。",
    ),
}
