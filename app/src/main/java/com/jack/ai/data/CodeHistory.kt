package com.jack.ai.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 代码文件的旧版本（借鉴 Aider 每改一次就存一版）：write_file / edit_file 改代码文件之前，先把原来的样子存进
 * 文件夹里的 .versions/<文件名>/，每个文件留最近 [KEEP] 版。改坏了能看差在哪、退回去，Kavi 才敢放手改。
 * 只管代码和文字文件（[CODE_EXT]），Word、PPT 这些有自己的底稿。
 */
object CodeHistory {
    const val KEEP = 30
    val CODE_EXT = setOf("py", "js", "mjs", "ts", "html", "htm", "css", "json", "md", "txt", "csv", "java", "kt", "c", "cpp", "h", "go", "rs", "sh", "sql", "yaml", "yml", "xml", "svg", "toml")
    private const val DIR = ".versions"

    fun tracked(file: File) = file.extension.lowercase() in CODE_EXT

    private fun dirOf(file: File) = File(File(file.parentFile, DIR), file.name)

    /** 改之前调：文件已经在、内容和最近一版不一样，就存一版。[why] 是这次是谁改的（write_file、edit_file、退回）。 */
    fun save(file: File, why: String, now: Long = System.currentTimeMillis()) {
        if (!tracked(file) || !file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val dir = dirOf(file).apply { mkdirs() }
        val last = versions(file).firstOrNull()
        if (last != null && runCatching { last.file.readText() }.getOrNull() == text) return
        File(dir, "$now-${why.filter { it.isLetterOrDigit() || it == '_' }}").writeText(text)
        versions(file).drop(KEEP).forEach { it.file.delete() }
    }

    class Version(val n: Int, val at: Long, val why: String, val file: File) {
        val lines get() = runCatching { file.readLines().size }.getOrDefault(0)
    }

    /** 这个文件存过的版本，最新的在前，编号 1 是最近一版（改之前的样子）。 */
    fun versions(file: File): List<Version> =
        dirOf(file).listFiles()?.mapNotNull { f ->
            val at = f.name.substringBefore('-').toLongOrNull() ?: return@mapNotNull null
            at to f
        }?.sortedByDescending { it.first }?.mapIndexed { i, (at, f) -> Version(i + 1, at, f.name.substringAfter('-'), f) }.orEmpty()

    fun listing(file: File, now: Long = System.currentTimeMillis()): String {
        val vs = versions(file)
        if (vs.isEmpty()) return "「${file.name}」还没有存过旧版本（第一次改它之前才会存）。"
        val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ENGLISH)
        return "「${file.name}」的旧版本（1 是最近的；现在的文件 ${runCatching { file.readLines().size }.getOrDefault(0)} 行）：\n" +
            vs.joinToString("\n") { v -> "${v.n}. ${f.format(Date(v.at))}，${v.lines} 行，在 ${v.why} 之前" }
    }

    /** 退回第 [n] 版：现在的样子也先存一版，退错了还能再回来。返回退回去的那版。 */
    fun restore(file: File, n: Int): Version? {
        val v = versions(file).firstOrNull { it.n == n } ?: return null
        val text = v.file.readText()
        save(file, "restore")
        file.writeText(text)
        return v
    }

    // ---------- 差异 ----------

    /**
     * 两段文字逐行比较，输出 unified diff 那样的格式（- 旧的，+ 新的，前后带 [context] 行不变的）。
     * 行数太多（超过 [MAX_LINES]）就只比开头那些。
     */
    fun diff(old: String, new: String, oldName: String = "旧版", newName: String = "现在", context: Int = 2): String {
        val a = old.lines().take(MAX_LINES)
        val b = new.lines().take(MAX_LINES)
        val ops = lineOps(a, b)
        if (ops.none { it.first != ' ' }) return "两版一样。"
        val out = StringBuilder("--- $oldName\n+++ $newName\n")
        // 每一行在两边的行号
        var ai = 0; var bi = 0
        val rows = ops.map { (k, t) -> Triple(k, t, ai to bi).also { if (k != '+') ai++; if (k != '-') bi++ } }
        val changed = rows.indices.filter { rows[it].first != ' ' }
        var i = 0
        while (i < changed.size) {
            val start = (changed[i] - context).coerceAtLeast(0)
            var end = (changed[i] + context).coerceAtMost(rows.lastIndex)
            var j = i
            while (j + 1 < changed.size && changed[j + 1] - context <= end + 1) { j++; end = (changed[j] + context).coerceAtMost(rows.lastIndex) }
            val (la, lb) = rows[start].third
            out.append("@@ 旧 ${la + 1} 行 / 新 ${lb + 1} 行 @@\n")
            for (r in start..end) out.append(rows[r].first).append(rows[r].second).append('\n')
            i = j + 1
        }
        val text = out.toString()
        return if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n…（差异太长，后面省略）" else text
    }

    private const val MAX_LINES = 3000
    private const val MAX_CHARS = 12000

    /** 最长公共子序列算出逐行的保留 / 删除 / 新增。 */
    internal fun lineOps(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        // 头尾相同的先去掉，中间才算，省内存
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val x = a.subList(pre, a.size - suf); val y = b.subList(pre, b.size - suf)
        val n = x.size; val m = y.size
        // 中间不同的部分太大：不细比，整段算删掉再加上（手机内存有限）
        if (n.toLong() * m > 2_000_000L) return a.take(pre).map { ' ' to it } + x.map { '-' to it } + y.map { '+' to it } + a.takeLast(suf).map { ' ' to it }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val mid = mutableListOf<Pair<Char, String>>()
        var i = 0; var j = 0
        while (i < n && j < m) when {
            x[i] == y[j] -> { mid += ' ' to x[i]; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { mid += '-' to x[i]; i++ }
            else -> { mid += '+' to y[j]; j++ }
        }
        while (i < n) mid += '-' to x[i++]
        while (j < m) mid += '+' to y[j++]
        return a.take(pre).map { ' ' to it } + mid + a.takeLast(suf).map { ' ' to it }
    }
}
