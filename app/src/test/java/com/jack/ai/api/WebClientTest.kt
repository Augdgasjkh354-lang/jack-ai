package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Test

class WebClientTest {
    @Test
    fun fetchEscapesAreUndoneOutsideCode() {
        val md = "\\# 标题\n版本 1\\.2 \\| 表格\n```\na \\| b\n```\n结尾\\."
        assertEquals("# 标题\n版本 1.2 | 表格\n```\na \\| b\n```\n结尾.", WebClient.unescapeFetch(md))
    }
}
