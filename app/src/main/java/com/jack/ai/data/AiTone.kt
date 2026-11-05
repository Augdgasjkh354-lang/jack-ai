package com.jack.ai.data

/**
 * 去 AI 味检测：用脚本数出中文 AI 文章最常见的写作习惯，逐条标出原句和改法，只检测不改。
 *
 * 规则是 Kavi 自己写的，依据公开的研究结论（「躺在废墟里」Lieflat 的同题配对实验：1169 篇、520 万字、50 万次语义判断，
 * 见 github.com/larashero3-dotcom/lieflat-less-ai-tone）：「不是 A，而是 B」约是人的 3.4 倍，下结论的冒号 2–4 倍，
 * 密集枚举 1.8 倍，三连排比 2.75 倍，超长前置定语多见，段与段衔接太顺。没有复制那个项目的规则原文和脚本。
 * 比喻贴不贴切这类脚本看不出的，交给模型自己读。
 */
object AiTone {
    data class Hit(val rule: Rule, val para: Int, val quote: String)

    enum class Rule(val label: String, val fix: String) {
        NOT_BUT("不是 A，而是 B", "直接说 B；要对比就拆成两句平说"),
        COLON("下结论的冒号", "删掉冒号前的引子（「核心是：」这类），直接把结论说出来"),
        LIST("密集枚举", "只留最要紧的两三项，或拆成几句分开说"),
        TRIPLET("三连排比", "合成一句，或只留一句最有力的"),
        LONG_MOD("超长前置定语", "把名词前那一长串修饰挪到后面，另起一个分句"),
        CLICHE("套话", "删掉，或换成具体的事实、例子"),
        DASH("破折号太多", "多数换成逗号、句号或括号"),
        TRANSITION("段首过渡词", "删掉段首的过渡词，让内容自己接上"),
    }

    data class Report(
        val chars: Int,
        val hits: List<Hit>,
        val sentenceAvg: Int,
        val sentenceCv: Double,
        val sentences: Int,
        val paragraphs: Int,
        val transitionParas: Int,
        val transitionRun: Int,
        val headingsPerK: Double,
        val dashesPerK: Double,
    ) {
        val uniform get() = sentences >= 8 && sentenceCv < 0.4
        val tooManyHeadings get() = chars >= 600 && headingsPerK > 3
        fun count(r: Rule) = hits.count { it.rule == r }
    }

    private val END = Regex("[。！？!?…]+|\n")
    private val CLAUSE = Regex("[，,。；;！？!?：:\n]")

    private val NOT_BUT = listOf(
        Regex("(不是|并非|并不是)[^。！？!?\n]{1,40}?[，,]\\s*(而是|而在于|反而是)"),
        Regex("与其说[^。！？!?\n]{1,30}?不如说"),
    )
    private val COLON_FIXED = Regex("(核心|关键|本质|答案|真相|结论|重点|秘诀|要害|底层逻辑|逻辑)(其实|恰恰)?(就)?(是|在于)?[：:]|" +
        "(一句话总结|一句话概括|一句话|简单来说|说白了|换句话说|总结一下|划重点|记住|原因很简单|道理很简单|答案很简单)[：:]")
    private val COLON_GENERIC = Regex("([^，,。！？!?\n：:]{2,14}的是)[：:]")
    private val SPEECH = Regex("(说|道|问|写|表示|指出|称|回答|提到|认为|强调)")
    private val LONG_MOD = Regex("(一个|一种|一款|一套|一项|一份|一位|一家|一篇|一场|一次|这个|这种|那种|这样一个|这样一种)" +
        "([^，,。；;！？!?、：:“”\"\n]{16,})的([^的，,。；;！？!?、：:“”\"\n]{1,6})(?=[，,。；;！？!?\n]|$)")
    private val CLICHES = listOf(
        "值得注意的是", "值得一提的是", "毋庸置疑", "不可否认", "总而言之", "综上所述", "总的来说", "不言而喻", "至关重要",
        "赋能", "助力", "深度融合", "全方位", "多维度", "一站式", "抓手", "颗粒度", "让我们一起", "希望对你有所帮助", "希望对您有所帮助",
    ).map { Regex(Regex.escape(it)) } + listOf(
        Regex("在当今[^，,。\n]{0,10}(时代|社会|世界)"),
        Regex("随着[^，,。\n]{1,15}的(快速|飞速|迅猛|不断|日益)发展"),
        Regex("扮演着[^，,。\n]{0,6}(重要|关键|核心)的?角色"),
        Regex("起到了?[^，,。\n]{0,6}(重要|关键)的?作用"),
        Regex("不仅仅?是[^。！？\n]{1,25}更是"),
        Regex("开启了?[^，,。\n]{0,10}新篇章"),
    )
    private val TRANSITIONS = listOf(
        "此外", "另外", "同时", "与此同时", "然而", "但是", "因此", "所以", "总之", "综上", "首先", "其次", "最后", "另一方面",
        "不仅如此", "事实上", "实际上", "更重要的是", "值得一提的是", "换句话说", "接下来", "当然", "而且", "于是",
    )
    private val HEADING = Regex("^\\s*(#{1,6}\\s+|[一二三四五六七八九十]+、|\\d+[.、]\\s*|（[一二三四五六七八九十]+）)")

    fun check(text: String): Report {
        val clean = text.replace("\r", "")
        val chars = clean.count { !it.isWhitespace() }.coerceAtLeast(1)
        val paras = clean.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val hits = ArrayList<Hit>()
        fun add(rule: Rule, pi: Int, para: String, at: Int) {
            hits += Hit(rule, pi + 1, sentenceAt(para, at))
        }
        val dashes = Regex("——|—{2,}|--").findAll(clean).count()
        val dashesPerK = dashes * 1000.0 / chars
        var transitionParas = 0
        var run = 0
        var maxRun = 0
        val bodyParas = paras.count { it.length > 20 && !HEADING.containsMatchIn(it) }
        paras.forEachIndexed { pi, p ->
            NOT_BUT.forEach { r -> r.findAll(p).forEach { add(Rule.NOT_BUT, pi, p, it.range.first) } }
            COLON_FIXED.findAll(p).forEach { add(Rule.COLON, pi, p, it.range.first) }
            COLON_GENERIC.findAll(p).forEach { m ->
                if (!SPEECH.containsMatchIn(m.groupValues[1]) && hits.none { it.rule == Rule.COLON && it.para == pi + 1 && it.quote == sentenceAt(p, m.range.first) })
                    add(Rule.COLON, pi, p, m.range.first)
            }
            LONG_MOD.findAll(p).forEach { add(Rule.LONG_MOD, pi, p, it.range.first) }
            CLICHES.forEach { r -> r.findAll(p).forEach { add(Rule.CLICHE, pi, p, it.range.first) } }
            // 枚举：一个分句里四项以上（三个顿号，或两个顿号再加「和、与、及」收尾）
            var pos = 0
            for (clause in p.split(CLAUSE)) {
                val commas = clause.count { it == '、' }
                val tail = if (commas > 0 && Regex("(和|与|及|以及|还有)").containsMatchIn(clause.substringAfterLast('、'))) 1 else 0
                if (commas + 1 + tail >= 4) add(Rule.LIST, pi, p, pos + clause.indexOf('、').coerceAtLeast(0))
                pos += clause.length + 1
            }
            triplets(p).forEach { add(Rule.TRIPLET, pi, p, it) }
            if (dashesPerK > 2) Regex("——|—{2,}").findAll(p).forEach { add(Rule.DASH, pi, p, it.range.first) }
            val starts = p.length > 20 && !HEADING.containsMatchIn(p) && TRANSITIONS.any { p.startsWith(it) }
            if (starts) { transitionParas++; run++; maxRun = maxOf(maxRun, run) } else if (p.length > 20) run = 0
        }
        // 段首过渡词只在用得多时才逐条标出来：偶尔一两段是正常的
        if (bodyParas >= 4 && (transitionParas * 10 >= bodyParas * 3 || maxRun >= 3)) {
            paras.forEachIndexed { pi, p -> if (p.length > 20 && TRANSITIONS.any { p.startsWith(it) }) add(Rule.TRANSITION, pi, p, 0) }
        }
        val sentences = clean.split(END).map { s -> s.count { !it.isWhitespace() } }.filter { it >= 5 }
        val avg = if (sentences.isEmpty()) 0.0 else sentences.average()
        val cv = if (sentences.size < 2 || avg == 0.0) 1.0 else kotlin.math.sqrt(sentences.sumOf { (it - avg) * (it - avg) } / sentences.size) / avg
        val headings = paras.count { HEADING.containsMatchIn(it) || (it.length in 2..15 && it.none { c -> c in "，。！？,.!?；;：:" }) }
        return Report(
            chars = chars, hits = hits.distinct(), sentenceAvg = avg.toInt(), sentenceCv = cv, sentences = sentences.size,
            paragraphs = bodyParas, transitionParas = transitionParas, transitionRun = maxRun,
            headingsPerK = headings * 1000.0 / chars, dashesPerK = dashesPerK,
        )
    }

    /** 三连排比：连着三个短句长短差不多、收尾一样（都是问号、句号或分号；逗号的还要开头一样）。返回第一句的位置。 */
    internal fun triplets(p: String): List<Int> {
        data class Piece(val text: String, val end: Char, val at: Int)
        val pieces = ArrayList<Piece>()
        var start = 0
        p.forEachIndexed { i, c ->
            if (c in "，,。；;！？!?") { pieces += Piece(p.substring(start, i).trim(), c, start); start = i + 1 }
        }
        val out = ArrayList<Int>()
        var i = 0
        while (i + 2 < pieces.size) {
            val three = pieces.subList(i, i + 3)
            val lens = three.map { it.text.length }
            val sameEnd = three.all { it.end == three[0].end }
            val sameHead = three.all { it.text.length >= 2 && it.text.take(2) == three[0].text.take(2) }
            val ok = lens.all { it in 2..14 } && lens.max() - lens.min() <= 2 && sameEnd &&
                (three[0].end !in "，," || sameHead)
            if (ok) { out += three[0].at; i += 3 } else i++
        }
        return out
    }

    /** 命中位置所在的那一句（太长截一段）。 */
    private fun sentenceAt(p: String, at: Int): String {
        val ends = "。！？!?…\n"
        var s = at.coerceIn(0, p.length)
        while (s > 0 && p[s - 1] !in ends) s--
        var e = at.coerceIn(0, p.length)
        while (e < p.length && p[e] !in ends) e++
        if (e < p.length) e++
        val sentence = p.substring(s, e).trim()
        return if (sentence.length <= 90) sentence else {
            val from = (at - s - 30).coerceAtLeast(0)
            "…" + sentence.substring(from, (from + 80).coerceAtMost(sentence.length)) + "…"
        }
    }

    /** 给模型的报告：总体问题、逐条命中（编号、第几段、原句、改法）；带上旧稿时多一张前后对比。 */
    fun format(r: Report, before: Report? = null, maxHits: Int = 60): String = buildString {
        val perK = r.hits.size * 1000.0 / r.chars
        append("【去 AI 味检测】共 ${r.chars} 字，命中 ${r.hits.size} 处（每千字 ${"%.1f".format(perK)} 处）\n")
        if (before != null) {
            append("\n前后对比（原稿 → 现在）：\n")
            Rule.entries.forEach { rule ->
                val a = before.count(rule); val b = r.count(rule)
                if (a > 0 || b > 0) append("- ${rule.label}：$a → $b\n")
            }
            append("- 句子长短波动：${"%.2f".format(before.sentenceCv)} → ${"%.2f".format(r.sentenceCv)}（低于 0.40 算太均匀）\n")
        }
        append("\n整体：\n")
        append("- 句子：${r.sentences} 句，平均 ${r.sentenceAvg} 字，长短波动 ${"%.2f".format(r.sentenceCv)}")
        append(if (r.uniform) "（太均匀，读着像一个模子刻的：长句短句交替着来）\n" else "\n")
        if (r.paragraphs > 0) append("- 段落：${r.paragraphs} 段，${r.transitionParas} 段用过渡词开头" +
            (if (r.transitionRun >= 3) "，最多连着 ${r.transitionRun} 段" else "") + "\n")
        if (r.tooManyHeadings) append("- 小标题太密：每千字 ${"%.1f".format(r.headingsPerK)} 个，短文可以去掉大半\n")
        if (r.dashesPerK > 2) append("- 破折号每千字 ${"%.1f".format(r.dashesPerK)} 个，偏多\n")
        if (r.hits.isEmpty()) { append("\n逐条：没有命中。\n") } else {
            append("\n逐条（编号用来让用户挑要改哪些）：\n")
            r.hits.take(maxHits).forEachIndexed { i, h -> append("[${i + 1}] ${h.rule.label} · 第 ${h.para} 段：「${h.quote}」\n") }
            if (r.hits.size > maxHits) append("……还有 ${r.hits.size - maxHits} 处没列出\n")
            append("\n改法：\n")
            Rule.entries.filter { r.count(it) > 0 }.forEach { append("- ${it.label}：${it.fix}\n") }
        }
        append("\n脚本看不出、要你自己读一遍的：比喻贴不贴切、抽象词硬配（「数据很平静」「经验的薄」这种），挑出来列在后面给用户定。")
    }
}
