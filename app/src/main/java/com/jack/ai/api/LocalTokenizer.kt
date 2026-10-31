package com.jack.ai.api

import java.io.File
import java.io.Reader
import java.util.PriorityQueue

/**
 * 本地模型的分词：读 Hugging Face 的 tokenizer.json，把文字切成编号，结果和 Python 的 tokenizers 一致（单测对过）。
 * 认三种：WordPiece（bge 这类 BERT 模型）、带字节兜底的 BPE（Gemma 这类 SentencePiece 模型）、字节级 BPE（Qwen、GPT 这类）。
 * 文字里写着的特殊记号（比如「<bos>」「<|im_end|>」）当普通文字切，不当控制符；要特殊记号用 id() 单独拿。
 */
interface LocalTokenizer {
    /** 切成编号，开头结尾带上模型要的特殊记号；总长不超过 maxLen。 */
    fun encode(text: String, maxLen: Int): IntArray

    /** 某个记号（含特殊记号）的编号。 */
    fun id(token: String): Int? = null

    /** 编号 → 文字（生成模型用；向量模型用不着）。 */
    fun decode(ids: IntArray): String = throw UnsupportedOperationException("这种分词不支持还原文字")

    companion object {
        /** 读 tokenizer.json（边读边解析，32MB 的也不会整个进内存）。 */
        fun load(file: File): LocalTokenizer = file.bufferedReader(Charsets.UTF_8).use { parse(it) }

        internal fun parse(reader: Reader): LocalTokenizer {
            val p = JsonPull(reader)
            val added = HashMap<String, Int>()
            var type = ""
            var vocab: HashMap<String, Int>? = null
            var merges: List<Pair<String, String>>? = null
            var byteFallback = false
            // 预切分：字节级 BPE 用一个正则先把文字切成词
            var splitRegex = ""
            var byteLevel = false
            p.obj { key ->
                when (key) {
                    "pre_tokenizer" -> p.walk { k, v -> if (k == "Regex") splitRegex = v; if (k == "type" && v == "ByteLevel") byteLevel = true }
                    "added_tokens" -> p.arr {
                        var id = -1; var content = ""
                        p.obj { k -> when (k) { "id" -> id = p.int(); "content" -> content = p.str(); else -> p.skip() } }
                        if (id >= 0) added[content] = id
                    }
                    "model" -> p.obj { k ->
                        when (k) {
                            "type" -> type = p.str()
                            "byte_fallback" -> byteFallback = p.bool()
                            "vocab" -> { val m = HashMap<String, Int>(1 shl 16); p.obj { tok -> m[tok] = p.int() }; vocab = m }
                            "merges" -> {
                                val list = ArrayList<Pair<String, String>>(1 shl 16)
                                p.arr {
                                    // 新格式是 ["a", "b"]，老格式是 "a b"
                                    if (p.peekArray()) { val ab = ArrayList<String>(2); p.arr { ab += p.str() }; list += ab[0] to ab[1] }
                                    else p.str().let { s -> val i = s.indexOf(' '); list += s.substring(0, i) to s.substring(i + 1) }
                                }
                                merges = list
                            }
                            else -> p.skip()
                        }
                    }
                    else -> p.skip()
                }
            }
            val v = vocab ?: error("tokenizer.json 里没有词表")
            added.forEach { (k, id) -> v.putIfAbsent(k, id) }
            return when (type) {
                "WordPiece" -> WordPiece(v)
                "BPE" -> when {
                    byteFallback -> SentencePieceBpe(v, merges.orEmpty())
                    byteLevel && splitRegex.isNotEmpty() -> ByteLevelBpe(v, merges.orEmpty(), splitRegex)
                    else -> error("只支持带字节兜底的 BPE（Gemma 这类）和字节级 BPE（Qwen 这类）")
                }
                else -> error("不认识的分词方式：$type")
            }
        }
    }
}

/** BERT 的 WordPiece：清掉控制字符、汉字两边加空格、按空白和标点切开，再按词表从长到短匹配。不转小写（bge-zh 的设置）。 */
internal class WordPiece(private val vocab: Map<String, Int>) : LocalTokenizer {
    private val unk = vocab.getValue("[UNK]")
    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")

    override fun encode(text: String, maxLen: Int): IntArray {
        val out = ArrayList<Int>()
        out += cls
        for (w in words(text)) {
            if (out.size >= maxLen - 1) break
            piece(w, out)
        }
        while (out.size > maxLen - 1) out.removeAt(out.size - 1)
        out += sep
        return out.toIntArray()
    }

    private fun piece(word: String, out: MutableList<Int>) {
        if (word.codePointCount(0, word.length) > 100) { out += unk; return }
        val ids = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (end > start) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end = word.offsetByCodePoints(end, -1)
            }
            if (found < 0) { out += unk; return }
            ids += found
            start = end
        }
        out += ids
    }

    private fun words(text: String): List<String> {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { words += cur.toString(); cur.setLength(0) } }
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            when {
                c == 0 || c == 0xFFFD || isControl(c) -> {}
                isWhitespace(c) -> flush()
                isCjk(c) || isPunct(c) -> { flush(); words += String(Character.toChars(c)) }
                else -> cur.appendCodePoint(c)
            }
        }
        flush()
        return words
    }

    private fun isWhitespace(c: Int) = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code ||
        Character.getType(c) == Character.SPACE_SEPARATOR.toInt() || Character.isWhitespace(c)

    private fun isControl(c: Int): Boolean {
        if (c == '\t'.code || c == '\n'.code || c == '\r'.code) return false
        return when (Character.getType(c)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(), Character.PRIVATE_USE.toInt() -> true
            else -> false
        }
    }

    private fun isPunct(c: Int): Boolean {
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        return when (Character.getType(c)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isCjk(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0x2A700..0x2B73F ||
        c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F
}

/**
 * SentencePiece 风格的 BPE（Gemma）：空格换成「▁」，整段当一个词；先逐字查词表，查不到的字拆成 UTF-8 字节（<0xE4> 这样），
 * 再按合并表从排名最前的一对开始合并。开头加 <bos>，结尾加 <eos>。
 */
internal class SentencePieceBpe(private val vocab: Map<String, Int>, merges: List<Pair<String, String>>) : LocalTokenizer {
    private val bos = vocab.getValue("<bos>")
    private val eos = vocab.getValue("<eos>")
    private val unk = vocab["<unk>"] ?: -1
    private val bytes = IntArray(256) { b -> vocab["<0x%02X>".format(b)] ?: unk }
    private val merger = BpeMerger(vocab, merges)

    override fun id(token: String): Int? = vocab[token]

    override fun encode(text: String, maxLen: Int): IntArray {
        val ids = merger.merge(symbols(text.replace(' ', '\u2581')))
        val n = minOf(ids.size, maxLen - 2).coerceAtLeast(0)
        return IntArray(n + 2).also { out ->
            out[0] = bos
            System.arraycopy(ids, 0, out, 1, n)
            out[n + 1] = eos
        }
    }

    private fun symbols(s: String): IntArray {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            val ch = String(Character.toChars(c))
            i += ch.length
            val id = vocab[ch]
            if (id != null) out += id
            else ch.toByteArray(Charsets.UTF_8).forEach { b ->
                val t = bytes[b.toInt() and 0xFF]
                // 连着的几个认不出的合成一个 <unk>
                if (t >= 0 && (t != unk || out.lastOrNull() != unk)) out += t
            }
        }
        return out.toIntArray()
    }
}

/**
 * 字节级 BPE（Qwen、GPT 这类）：先 NFC 规整，按 tokenizer.json 里的正则切成词，每个词的 UTF-8 字节换成 GPT-2 的「可见字符」，
 * 再按合并表合并。不加开头结尾记号（对话格式由调用的地方拼）。
 */
internal class ByteLevelBpe(private val vocab: Map<String, Int>, merges: List<Pair<String, String>>, pattern: String) : LocalTokenizer {
    private val merger = BpeMerger(vocab, merges)
    // \s 要按 Unicode 算（Python 那边的正则就是这样）
    private val split = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
    private val byteIds = IntArray(256) { b -> vocab.getValue(BYTE_CHARS[b].toString()) }
    /** 常见的词切过一次就记住。 */
    private val cache = object : LinkedHashMap<String, IntArray>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 4096
    }

    override fun id(token: String): Int? = vocab[token]

    /** 编号 → 记号（还原文字用，第一次用时才建）。 */
    private val tokens: Array<String?> by lazy {
        Array<String?>((vocab.values.maxOrNull() ?: 0) + 1) { null }.also { a -> vocab.forEach { (t, i) -> a[i] = t } }
    }
    private val charToByte: Map<Char, Int> by lazy { BYTE_CHARS.withIndex().associate { (b, c) -> c to b } }

    /** 记号里的「可见字符」换回字节，再按 UTF-8 拼成文字；特殊记号（不在字节表里的字符）原样留着。 */
    override fun decode(ids: IntArray): String {
        val bytes = java.io.ByteArrayOutputStream()
        ids.forEach { i ->
            val t = tokens.getOrNull(i) ?: return@forEach
            val bs = t.map { charToByte[it] }
            if (bs.all { it != null }) bs.forEach { bytes.write(it!!) } else bytes.write(t.toByteArray(Charsets.UTF_8))
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    override fun encode(text: String, maxLen: Int): IntArray {
        val norm = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        val out = ArrayList<Int>()
        val m = split.matcher(norm)
        var last = 0
        fun word(w: String) {
            if (w.isEmpty()) return
            val ids = synchronized(cache) { cache[w] } ?: merger.merge(w.toByteArray(Charsets.UTF_8).map { byteIds[it.toInt() and 0xFF] }.toIntArray())
                .also { synchronized(cache) { cache[w] = it } }
            ids.forEach { out += it }
        }
        // Isolated：匹配到的各成一个词，中间没匹配上的也各成一个词
        while (m.find()) {
            if (m.start() > last) word(norm.substring(last, m.start()))
            word(m.group())
            last = m.end()
            if (out.size >= maxLen) break
        }
        if (last < norm.length && out.size < maxLen) word(norm.substring(last))
        return (if (out.size > maxLen) out.subList(0, maxLen) else out).toIntArray()
    }

    companion object {
        /** GPT-2 的字节 → 字符表：能打印的字节用自己，其余的挪到 256 以后。 */
        val BYTE_CHARS: CharArray = run {
            val keep = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toSet()
            var extra = 0
            CharArray(256) { b -> if (b in keep) b.toChar() else (256 + extra++).toChar() }
        }
    }
}

/** BPE 合并：每次挑排名最前（一样就挑最靠左）的相邻一对，用链表 + 优先队列，长文也快。 */
internal class BpeMerger(vocab: Map<String, Int>, merges: List<Pair<String, String>>) {
    /** (左, 右) → 排名和合并后的编号。 */
    private val table = PairTable(merges.size)

    init {
        merges.forEachIndexed { rank, (a, b) ->
            val ia = vocab[a] ?: return@forEachIndexed
            val ib = vocab[b] ?: return@forEachIndexed
            val merged = vocab[a + b] ?: return@forEachIndexed
            table.putIfAbsent(ia, ib, rank, merged)
        }
    }

    fun merge(sym: IntArray): IntArray {
        val n = sym.size
        if (n < 2) return sym
        val id = sym.copyOf()
        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { if (it + 1 < n) it + 1 else -1 }
        val alive = BooleanArray(n) { true }
        // 队列里：排名、左边位置、当时左右两个编号（取出时核对，过时的丢掉）
        val pq = PriorityQueue<LongArray>(n, compareBy<LongArray>({ it[0] }, { it[1] }))
        fun push(i: Int) {
            val j = next[i]
            if (j < 0) return
            val rank = table.rank(id[i], id[j])
            if (rank >= 0) pq += longArrayOf(rank.toLong(), i.toLong(), id[i].toLong(), id[j].toLong())
        }
        for (i in 0 until n - 1) push(i)
        while (pq.isNotEmpty()) {
            val top = pq.poll()
            val i = top[1].toInt()
            if (!alive[i]) continue
            val j = next[i]
            if (j < 0 || id[i].toLong() != top[2] || id[j].toLong() != top[3]) continue
            id[i] = table.merged(id[i], id[j])
            alive[j] = false
            next[i] = next[j]
            if (next[j] >= 0) prev[next[j]] = i
            if (prev[i] >= 0) push(prev[i])
            push(i)
        }
        val out = ArrayList<Int>(n)
        var k = 0
        while (k >= 0) { out += id[k]; k = next[k] }
        return out.toIntArray()
    }
}

/** (int, int) → (排名, 合并后的编号) 的开放寻址表：五十多万条合并规则，用 HashMap 会多占几十 MB。 */
internal class PairTable(expected: Int) {
    private val cap = Integer.highestOneBit((expected * 2).coerceAtLeast(16)) shl 1
    private val keys = LongArray(cap) { EMPTY }
    private val ranks = IntArray(cap)
    private val ids = IntArray(cap)

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
    private fun slot(k: Long): Int {
        var h = (k xor (k ushr 29)) * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        var i = h.toInt() and (cap - 1)
        while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) and (cap - 1)
        return i
    }

    fun putIfAbsent(a: Int, b: Int, rank: Int, merged: Int) {
        val k = key(a, b)
        val i = slot(k)
        if (keys[i] == EMPTY) { keys[i] = k; ranks[i] = rank; ids[i] = merged }
    }

    fun rank(a: Int, b: Int): Int { val i = slot(key(a, b)); return if (keys[i] == EMPTY) -1 else ranks[i] }
    fun merged(a: Int, b: Int): Int = ids[slot(key(a, b))]

    private companion object { const val EMPTY = Long.MIN_VALUE }
}

/** 极简的流式 JSON 读取：只够读 tokenizer.json（对象、数组、字符串、数字、true/false/null）。 */
internal class JsonPull(private val r: Reader) {
    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var len = 0
    private val sb = StringBuilder()

    private fun peekChar(): Int {
        if (pos >= len) { len = r.read(buf); pos = 0; if (len <= 0) { len = 0; return -1 } }
        return buf[pos].code
    }
    private fun next(): Int = peekChar().also { if (it >= 0) pos++ }
    private fun ws(): Int { while (true) { val c = peekChar(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) pos++ else return c } }
    private fun expect(c: Char) { val got = ws(); if (got != c.code) error("JSON 格式不对：要 $c，读到 ${if (got < 0) "结尾" else got.toChar().toString()}"); pos++ }

    fun peekArray() = ws() == '['.code

    /** 读一个对象，每个键调一次 onKey（它负责读掉对应的值）。 */
    fun obj(onKey: (String) -> Unit) {
        expect('{')
        if (ws() == '}'.code) { pos++; return }
        while (true) {
            val k = str()
            expect(':')
            onKey(k)
            when (ws()) { ','.code -> pos++; '}'.code -> { pos++; return }; else -> error("JSON 格式不对：对象没收尾") }
        }
    }

    /** 读一个数组，每个元素调一次 onItem（它负责读掉这个元素）。 */
    fun arr(onItem: () -> Unit) {
        expect('[')
        if (ws() == ']'.code) { pos++; return }
        while (true) {
            onItem()
            when (ws()) { ','.code -> pos++; ']'.code -> { pos++; return }; else -> error("JSON 格式不对：数组没收尾") }
        }
    }

    fun str(): String {
        expect('"')
        sb.setLength(0)
        while (true) {
            val c = next()
            when {
                c < 0 -> error("JSON 字符串没收尾")
                c == '"'.code -> return sb.toString()
                c == '\\'.code -> when (val e = next()) {
                    'n'.code -> sb.append('\n'); 't'.code -> sb.append('\t'); 'r'.code -> sb.append('\r')
                    'b'.code -> sb.append('\b'); 'f'.code -> sb.append('\u000C')
                    'u'.code -> { var v = 0; repeat(4) { v = v * 16 + Character.digit(next(), 16) }; sb.append(v.toChar()) }
                    else -> sb.append(e.toChar())
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun scalar(): String {
        ws()
        sb.setLength(0)
        while (true) {
            val c = peekChar()
            if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) return sb.toString()
            sb.append(c.toChar()); pos++
        }
    }

    fun int(): Int = scalar().toDouble().toInt()
    fun bool(): Boolean = scalar() == "true"

    /** 走一遍一个值（对象、数组嵌套都走到），每个「键: 字符串」报一次。 */
    fun walk(onString: (key: String, value: String) -> Unit) {
        fun value(key: String) {
            when (ws()) {
                '{'.code -> obj { k -> value(k) }
                '['.code -> arr { value(key) }
                '"'.code -> onString(key, str())
                else -> scalar()
            }
        }
        value("")
    }

    fun skip() {
        when (ws()) {
            '{'.code -> obj { skip() }
            '['.code -> arr { skip() }
            '"'.code -> str()
            else -> scalar()
        }
    }
}
