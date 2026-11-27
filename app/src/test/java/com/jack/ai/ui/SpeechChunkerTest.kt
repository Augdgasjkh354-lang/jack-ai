package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechChunkerTest {

    @Test fun chineseSentencesStreamOutAsTheyComplete() {
        val chunker = SpeechChunker()
        assertEquals(emptyList<String>(), chunker.feed("第一句还没有写完"))
        assertEquals(
            listOf("第一句已经写完了，第二句也写完了。"),
            chunker.feed("第一句已经写完了，第二句也写完了。第三句"),
        )
        // 短句先攒着，和下一句拼在一起读
        assertEquals(emptyList<String>(), chunker.feed("第一句已经写完了，第二句也写完了。第三句。好"))
        assertEquals(listOf("第三句。好，再试一次。"), chunker.feed("第一句已经写完了，第二句也写完了。第三句。好，再试一次。"))
        assertEquals(emptyList<String>(), chunker.finish())
    }

    @Test fun decimalsAndCodeBlocksAreNotRead() {
        val chunker = SpeechChunker()
        // 小数点、缩写里的点不算句号
        assertEquals(
            listOf("价格是 3.5 元，Dr. Smith 说没问题。"),
            chunker.feed("价格是 3.5 元，Dr. Smith 说没问题。"),
        )
        // 代码块没闭合：后面的先别读（第一句够长，先读出来）
        val code = SpeechChunker()
        assertEquals(listOf("先说一个很重要的结论。"), code.feed("先说一个很重要的结论。\n```kotlin\nval x = 1\n"))
        // 闭合了再读：代码本身不读，只读后面的
        assertEquals(listOf("（代码略）接着说。"), code.feed("先说一个很重要的结论。\n```kotlin\nval x = 1\n```\n接着说。"))
    }

    @Test fun rewrittenAnswerIsNotReadTwice() {
        val chunker = SpeechChunker()
        // 「第一句。」不到 6 个字，先攒着，还没读出去
        assertEquals(emptyList<String>(), chunker.feed("第一句。第二句正在写"))
        // 回答被改写：攒着的短句不重读，从对不上的地方接着读
        assertEquals(
            listOf("第一句改写了，内容不一样。", "第二句也改了。"),
            chunker.feed("第一句改写了，内容不一样。第二句也改了。"),
        )
        assertEquals(emptyList<String>(), chunker.finish())
    }
}
