package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultKindsTest {
    @Test fun videoFirst() {
        val s = ResultKinds.nextSteps(listOf("封面.png", "成片.mp4", "配音.srt"))
        assertEquals("加字幕", s.first().first)
        assertTrue(s.first().second.contains("「成片.mp4」"))
    }

    @Test fun imagesAndDocs() {
        val many = ResultKinds.nextSteps(listOf("a-01.png", "a-02.png", "a-03.png", "a-04.png"))
        assertEquals("做成小红书", many.first().first)
        assertTrue(many.first().second.contains("这 4 个文件") && many.first().second.contains("等"))
        assertEquals("总结要点", ResultKinds.nextSteps(listOf("报告.pdf")).first().first)
        assertEquals("写演讲稿", ResultKinds.nextSteps(listOf("方案.pptx")).first().first)
    }

    @Test fun ignoresPreviewsAndSources() {
        assertTrue(ResultKinds.nextSteps(listOf("动画-预览.png", "x.html", "y.srt")).isEmpty())
        assertTrue(ResultKinds.nextSteps(listOf("压缩包.zip")).isEmpty())
    }
}
