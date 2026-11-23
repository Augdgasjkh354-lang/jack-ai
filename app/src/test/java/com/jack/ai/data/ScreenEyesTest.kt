package com.jack.ai.data

import com.jack.ai.data.phone.ScreenEyes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 操作手机时的「眼睛」：选哪个模型看、看到的怎么换成截图坐标。 */
class ScreenEyesTest {
    @Test fun parsesNormalizedCoordinates() {
        val raw = """{"items":[{"label":"搜索图标","type":"icon","x":900,"y":50},{"label":"开始游戏","type":"button","x":500,"y":800},
            {"label":"","type":"text","x":1,"y":1},{"label":"越界","x":1200,"y":5},{"label":"开始游戏","type":"button","x":502,"y":801}]}"""
        val spots = ScreenEyes.parse(raw, 720, 1600)
        assertEquals(2, spots.size)
        assertEquals(ScreenEyes.Spot("搜索图标", "icon", 648, 80), spots[0])
        assertEquals(1280, spots[1].y)
        val text = ScreenEyes.format(spots, "m")
        assertTrue(text.contains("图标「搜索图标」(648, 80)"))
        assertEquals(emptyList<ScreenEyes.Spot>(), ScreenEyes.parse("不是 JSON", 1, 1))
    }

    @Test fun picksAVisionModel() {
        assertEquals(MODEL_FLASH, ScreenEyes.eyes(Settings(apiKey = "k"))?.model)
        assertNull(ScreenEyes.eyes(Settings(apiKey = "")))
        val s = Settings(apiKey = "", providers = listOf(Provider(id = "q", name = "百炼", baseUrl = "https://x", apiKey = "k",
            models = listOf(ProviderModel("qwen-max"), ProviderModel("qwen-vl-max", vision = true)))))
        assertEquals("qwen-vl-max", ScreenEyes.eyes(s)?.model)
    }
}
