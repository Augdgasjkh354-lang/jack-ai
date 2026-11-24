package com.jack.ai.data.packs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 宽松的 JSON：标准 JSON、JS 对象写法（键不带引号、单引号、末尾逗号、// 和 /* */ 注释）、
 * HJSON 写法（不写逗号、字符串不带引号一直到行尾、# 注释、'''多行'''）都认。读 Operit 脚本的 METADATA 用。
 */
object LooseJson {
    fun parse(text: String): JsonElement = P(text).run { val v = value(); ws(); if (i < s.length) fail("后面还有多余的内容"); v }

    private class P(val s: String) {
        var i = 0
        /** 外面那层 { 或 [ 在哪（判断值是不是和它写在同一行）。 */
        val opens = ArrayDeque<Int>()
        fun fail(why: String): Nothing {
            val line = s.substring(0, i.coerceAtMost(s.length)).count { it == '\n' } + 1
            throw IllegalArgumentException("第 $line 行：$why")
        }

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '#' || s.startsWith("//", i) -> { while (i < s.length && s[i] != '\n') i++ }
                    s.startsWith("/*", i) -> { val e = s.indexOf("*/", i + 2); i = if (e < 0) s.length else e + 2 }
                    else -> return
                }
            }
        }

        /** 跳过空白、注释和一个可有可无的逗号。 */
        fun sep() { ws(); if (i < s.length && s[i] == ',') i++; ws() }

        fun value(): JsonElement {
            ws()
            if (i >= s.length) fail("没写完")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"', '\'' -> if (s.startsWith("'''", i)) JsonPrimitive(multi()) else JsonPrimitive(quoted(c))
                else -> bare()
            }
        }

        fun obj(): JsonObject {
            opens.addLast(i); i++
            val m = linkedMapOf<String, JsonElement>()
            while (true) {
                sep()
                if (i >= s.length) fail("少了 }")
                if (s[i] == '}') { i++; opens.removeLast(); return JsonObject(m) }
                val key = if (s[i] == '"' || s[i] == '\'') quoted(s[i]) else {
                    val st = i
                    while (i < s.length && s[i] != ':' && !s[i].isWhitespace()) i++
                    s.substring(st, i).ifEmpty { fail("这里要写键名") }
                }
                ws()
                if (i >= s.length || s[i] != ':') fail("「$key」后面要有冒号")
                i++
                m[key] = value()
            }
        }

        fun arr(): JsonArray {
            opens.addLast(i); i++
            val l = mutableListOf<JsonElement>()
            while (true) {
                sep()
                if (i >= s.length) fail("少了 ]")
                if (s[i] == ']') { i++; opens.removeLast(); return JsonArray(l) }
                l += value()
            }
        }

        fun quoted(q: Char): String {
            i++
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == q -> return b.toString()
                    c == '\\' && i < s.length -> {
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> { b.append(s.substring(i, (i + 4).coerceAtMost(s.length)).toIntOrNull(16)?.toChar() ?: '?'); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
            fail("引号没有收尾")
        }

        fun multi(): String {
            i += 3
            val e = s.indexOf("'''", i).takeIf { it >= 0 } ?: fail("''' 没有收尾")
            val raw = s.substring(i, e); i = e + 3
            val lines = raw.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            val indent = lines.filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' }.length } ?: 0
            return lines.joinToString("\n") { it.drop(indent) }
        }

        /** 不带引号的：数字、true/false/null，或者 HJSON 那种一直到行尾的字符串。 */
        fun bare(): JsonElement {
            val st = i
            while (i < s.length && s[i] !in ",}]\n\r") i++
            val tok = s.substring(st, i).trim()
            when (tok) {
                "true" -> return JsonPrimitive(true)
                "false" -> return JsonPrimitive(false)
                "null" -> return JsonNull
            }
            if (Regex("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$").matches(tok)) return JsonPrimitive(tok.toBigDecimal().let { if (it.scale() <= 0) it.toLong() as Number else it.toDouble() })
            // 和外面那层括号写在同一行（{ a: x, b: y }）：到逗号或括号为止
            val open = opens.lastOrNull()
            if (open != null && s.substring(open, st).none { it == '\n' }) return JsonPrimitive(tok)
            // HJSON：字符串一直到行尾（逗号也算在里面），末尾的逗号去掉
            i = st
            while (i < s.length && s[i] != '\n' && s[i] != '\r') i++
            return JsonPrimitive(s.substring(st, i).trim().removeSuffix(",").trim())
        }
    }
}
