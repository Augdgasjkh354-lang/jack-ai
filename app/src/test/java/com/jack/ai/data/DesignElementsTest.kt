package com.jack.ai.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignElementsTest {
    private val lib = Json { ignoreUnknownKeys = true }.decodeFromString(DesignElements.Library.serializer(),
        File("src/main/assets/design/stickers.json").readText())

    @Test fun everyStickerHasAFileAndSensibleSize() {
        assertTrue(lib.stickers.size >= 100)
        lib.stickers.forEach { s ->
            assertTrue(s.id, File("src/main/assets/design/stickers/${s.id}.svg").isFile)
            val (w, h) = s.size()
            assertTrue("${s.id} $w×$h", w in 40..1080 && h in 10..1440)
        }
    }

    @Test fun searchesStickersAndIconsInChinese() {
        assertTrue(DesignElements.search(lib, "箭头").all { it.category == "arrow" || it.tags.any { t -> "箭头" in t } })
        assertTrue(DesignElements.search(lib, "箭头").isNotEmpty())
        val icons = setOf("phone", "phone-call", "coffee", "cup", "gift", "x")
        assertEquals("phone", DesignElements.searchIcons(icons, "电话").first())
        assertTrue("coffee" in DesignElements.searchIcons(icons, "咖啡"))
        assertTrue(DesignElements.searchIcons(icons, "").containsAll(listOf("phone", "coffee", "gift")))
    }

    @Test fun stickerSvgFillsItsBox() {
        val raw = File("src/main/assets/design/stickers/arrow-curve-loop.svg").readText()
        val svg = DesignElements.fit(raw.replace(Regex("<metadata>[\\s\\S]*?</metadata>"), ""))
        assertTrue(svg.startsWith("<svg width=\"100%\" height=\"100%\""))
        assertTrue("<metadata>" !in svg)
    }
}
