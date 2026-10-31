package com.jack.ai.a11y

/**
 * 「教 Kavi 一个手机流程」：用户自己在勾过的 App 里做一遍，Kavi 记下点了什么、输入了什么，
 * 做完交给 Kavi 整理成手机流程存下来（和浏览器的演示教学一样，存的是文字步骤，不是点击坐标）。纯规则，单测。
 */
object PhoneTeach {
    const val MAX_STEPS = 60
    private const val INPUT = "在输入框"

    fun opened(app: String) = "打开「$app」"

    fun click(label: String): String? = label.replace('\n', ' ').trim().take(40).takeIf { it.isNotEmpty() }?.let { "点「$it」" }

    fun typed(field: String, value: String, password: Boolean): String? = when {
        password -> "在密码框填密码（请用户自己填）"
        value.isBlank() -> null
        else -> INPUT + field.trim().take(20).let { if (it.isEmpty()) "" else "「$it」" } + "输入「${value.replace('\n', ' ').take(80)}」"
    }

    /** 加一步：和上一步一样的不重复；同一个输入框连着打字只留最后一次（打一个字记一次太碎）。 */
    fun append(steps: List<String>, step: String): List<String> {
        if (steps.lastOrNull() == step || steps.size >= MAX_STEPS) return steps
        val last = steps.lastOrNull()
        if (last != null && step.startsWith(INPUT) && last.startsWith(INPUT) && field(last) == field(step)) return steps.dropLast(1) + step
        return steps + step
    }

    private fun field(step: String) = step.substringBefore("输入「")

    /** 交给 Kavi 的话：让它把演示整理成手机流程存下来。 */
    fun request(steps: List<String>): String =
        "我刚在手机上演示了一遍操作，帮我存成手机流程（用 phone 的 save_flow，app 填主要用的那个 App）：名字和「什么时候用」你根据步骤起；" +
            "每次会变的内容（搜的词、联系人、日期这类）写成 {关键词} 这样的空；密码、验证码、付款写成「请用户自己……」；" +
            "看得出是多余的步骤（点错、来回切）就去掉。存好告诉我流程名和步骤。\n\n我的操作：\n" +
            steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
}
