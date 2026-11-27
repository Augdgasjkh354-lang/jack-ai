package com.jack.ai.ui

/**
 * 边写边读的分句器：喂进来越来越长的回答全文，吐出「已经完整、还没读过」的句子。
 *
 * 句子边界是 。！？!?；; 和换行；英文的 . 只有后面跟空格才算，小数点、缩写不算。
 * 太短的（不到 6 个字）先攒着和下一句拼；太长没标点的（超过 80 字）在逗号处切。
 * Markdown 按 [Speaker.plain] 同样的规则去掉，代码块、公式不读；代码块没闭合时，
 * 后面的先攒着，等闭合了再读。回答写完调 [finish] 把剩下的（哪怕没标点）一并吐出来。
 * 回答被改写时（新全文不再以读过的部分开头），已经读过的不重读，从对不上的地方接着读。
 */
class SpeechChunker {
    /** 已经读过的正文（去 Markdown 之后的样子，用来和新全文对齐）。 */
    private var emitted = ""
    /** 还没读完的正文尾巴（含攒着的短句，都在里面，等下次喂进来再拼）。 */
    private var buffer = ""

    /** 喂进最新的回答全文，返回这次新完整、还没读过的句子。 */
    fun feed(fullText: String): List<String> {
        val plain = plainHeldBack(fullText)
        val old = emitted + buffer
        val common = commonPrefix(old, plain)
        if (common < old.length) {
            // 改写吃进了已经读过的部分：只能从对不上的地方接着读，已经读过的不重读
            if (common < emitted.length) emitted = plain.substring(0, common)
        }
        buffer = plain.substring(emitted.length)
        val out = drain(flush = false)
        // 吐出去的归已读，没吐的留在 buffer 里：buffer 是新全文的原样尾巴，别丢空格
        emitted = plain.substring(0, plain.length - buffer.length)
        return out
    }

    /** 回答写完了：把剩下的（哪怕没标点、不到 6 个字）一并吐出来。 */
    fun finish(): List<String> = drain(flush = true)

    /**
     * 去 Markdown：代码块没闭合时，先把从开着的 ``` 往后的都截掉（等闭合了再读）；
     * 闭合的代码块、公式按 Speaker.plain() 的规则处理。
     */
    private fun plainHeldBack(md: String): String {
        var text = md
        var from = 0
        var open = -1
        while (true) {
            val i = text.indexOf("```", from)
            if (i < 0) break
            if (open < 0) open = i else open = -1
            from = i + 3
        }
        if (open >= 0) text = text.substring(0, open)
        return Speaker.plain(text)
    }

    /** 从 buffer 里切出完整的句子；flush 时尾巴没标点也当一句吐出来。 */
    private fun drain(flush: Boolean): List<String> {
        val out = mutableListOf<String>()
        var rest = buffer
        var held = ""
        while (rest.isNotBlank()) {
            val cut = findCut(rest)
            if (cut < 0) break
            val raw = rest.substring(0, cut)
            rest = rest.substring(cut)
            val sentence = raw.trim()
            if (sentence.isEmpty()) continue
            val combined = held + raw
            // 短句先攒着，和下一句拼；写完了就不用攒了（攒的时候去掉句尾的换行，免得拼出来的句子中间多个换行）
            if (!flush && combined.trim().length < SHORT) {
                held = combined.trim()
                continue
            }
            held = ""
            out += combined.trim()
        }
        // 攒着的短句和没完整的一句都留在 buffer 里，等下次喂进来再拼（原样留着，别丢空格）
        buffer = held + rest
        if (flush && buffer.isNotBlank()) {
            // 剩下的尾巴：太长先在逗号处切开，免得一句读太久
            var tail = buffer.trim()
            buffer = ""
            while (tail.length > LONG) {
                val comma = tail.lastIndexOfAny(COMMAS, LONG)
                val cut = if (comma > 0) comma + 1 else LONG
                out += tail.substring(0, cut).trim()
                tail = tail.substring(cut).trimStart()
            }
            if (tail.isNotBlank()) out += tail.trim()
        }
        return out
    }

    /**
     * 找下一句的切点（不含切点之后的字），没完整的一句返回 -1。
     * 走到 80 字还没标点，就在 80 字以内的最后一个逗号处切；连逗号都没有就硬切在 80 字。
     */
    private fun findCut(s: String): Int {
        var lastComma = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '，' || c == ',') lastComma = i
            if (isBoundary(s, i)) return boundaryEnd(s, i)
            if (i >= LONG) return if (lastComma > 0) lastComma + 1 else LONG
            i++
        }
        return -1
    }

    private fun isBoundary(s: String, i: Int): Boolean {
        val c = s[i]
        if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；' || c == ';' || c == '\n') return true
        if (c != '.') return false
        // 英文句号：后面要跟空格（或收尾）才算；小数点、缩写不算
        var j = i + 1
        while (j < s.length && s[j] in CLOSERS) j++
        if (j < s.length && s[j] != ' ' && s[j] != '\n' && s[j] != '\t') return false
        var k = i - 1
        while (k >= 0 && s[k].isLetter()) k--
        val word = s.substring(k + 1, i)
        if (word.length <= 1) return false
        if (word.lowercase() in ABBREVIATIONS) return false
        if (word.length <= 3 && word.all { it.isUpperCase() }) return false
        return true
    }

    /** 句号后面的右引号、右括号算进这句里。 */
    private fun boundaryEnd(s: String, i: Int): Int {
        var j = i + 1
        while (j < s.length && s[j] in CLOSERS) j++
        return j
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    companion object {
        /** 不到这么多个字先攒着，和下一句拼。 */
        private const val SHORT = 6
        /** 没标点超过这么多个字，在逗号处切。 */
        private const val LONG = 80
        private val COMMAS = charArrayOf('，', ',')
        private const val CLOSERS = "”’\"'」』）)]"
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "vs", "etc", "eg", "ie", "prof", "sr", "jr")
    }
}
