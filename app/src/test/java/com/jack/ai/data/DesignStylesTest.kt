package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignStylesTest {
    private val assets = File("src/main/assets/design")

    @Test
    fun styleLibraryFontsAreAllAvailable() {
        val fonts = DesignFonts.parse(File(assets, "fonts.json").readText())
        val families = fonts.map { it.family }.toSet()
        assertTrue(fonts.any { it.bundled.isNotEmpty() && File("src/main/assets/" + it.bundled).isFile })
        val lib = DesignStyles.parse(File(assets, "styles.json").readText())
        assertTrue(lib.styles.size >= 10 && lib.layouts.isNotEmpty())
        // 风格库里写的字体都要在字体清单里，不然模型照着写了也加载不到
        val bad = lib.styles.flatMap { s ->
            Regex("\"fonts\":\\{([^}]*)\\}").find(s.o.toString())?.groupValues?.get(1).orEmpty()
                .let { Regex(":\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
                .filter { it !in families }.map { "${s.id}: $it" }
        }
        assertEquals(emptyList<String>(), bad)
        val detail = DesignStyles.catalog(lib, "bold-poster")
        assertTrue(detail, "Smiley Sans" in detail && "primary=" in detail && "禁忌" in detail)
        assertTrue("没有" in DesignStyles.catalog(lib, "nope"))
    }

    @Test
    fun fontCssPointsAtLocalPathsAndUsedFontsAreFound() {
        val css = """@font-face { font-family: 'Noto Serif SC'; font-display: swap; src: url(./files/a-400.woff2) format('woff2'), url(./files/a-400.woff) format('woff'); }
            @font-face { src: url('./files/b.woff2') format('woff2'); }"""
        val out = DesignFonts.rewrite(css, "@fontsource/noto-serif-sc@5.3.1", "")
        assertTrue(out, "url(/fonts/npm/@fontsource/noto-serif-sc@5.3.1/files/a-400.woff2)" in out && ".woff)" !in out && "font-display: block" in out)
        assertTrue("url(/fonts/npm/@fontsource/noto-serif-sc@5.3.1/files/b.woff2)" in out)
        val fonts = DesignFonts.parse(File(assets, "fonts.json").readText())
        val used = DesignFonts.used(fonts, """.t{font-family:"Smiley Sans","Noto Sans CJK SC",sans-serif}.b{font-family:'Noto Serif SC',serif}""")
        assertEquals(listOf("smiley", "noto-serif"), used.map { it.id })
    }
}
