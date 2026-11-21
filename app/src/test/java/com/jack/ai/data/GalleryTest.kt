package com.jack.ai.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.jack.ai.data.browser.BrowserFlows

class GalleryTest {
    @Test fun onlyVideosAndImagesGoToTheGallery() {
        assertEquals(Gallery.Kind.VIDEO, Gallery.kindOf(File("成片.MP4")))
        assertEquals(Gallery.Kind.VIDEO, Gallery.kindOf(File("a.mov")))
        assertEquals(Gallery.Kind.IMAGE, Gallery.kindOf(File("封面.png")))
        assertNull(Gallery.kindOf(File("配音.wav")))
        assertNull(Gallery.kindOf(File("字幕.srt")))
    }

    @Test fun jianyingKnowledgeComesWithBothPackages() {
        listOf("com.lemon.lv", "com.lemon.lvoverseas").forEach { pkg ->
            val note = BrowserFlows.builtinNotesFor("app:$pkg").single().text
            assertTrue(note.contains("剪同款"))
            assertTrue(note.contains("VIP"))
        }
    }
}
