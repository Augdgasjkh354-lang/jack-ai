package com.jack.ai.tools

import com.jack.ai.api.BrowserNet
import com.jack.ai.data.SearchRecord
import com.jack.ai.ui.JackBrowser
import com.jack.ai.ui.BrowserCdp
import com.jack.ai.ui.BrowserPacing
import com.wangzan101.mika.core.cap.web.WebBarrier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.jack.ai.data.backgroundEndpoint

// 网页前后两次的差别（从 BrowserTools.kt 拆出来）。

/** 同一个页面这一步和上一步比，变了什么。 */
internal object BrowserDiff {
    data class Diff(val changed: List<JackBrowser.Item>, val gone: List<Int>, val newText: List<String>)

    /** 跳到了别的网址、或者变化太大（新的超过一半），返回 null：发整页。 */
    fun of(prev: JackBrowser.Page?, p: JackBrowser.Page): Diff? {
        if (prev == null || prev.url != p.url) return null
        val old = prev.items.associateBy { it.n }
        val changed = p.items.filter { i ->
            val o = old[i.n]
            o == null || o.label != i.label || o.value != i.value || o.checked != i.checked || o.options != i.options
        }
        val now = p.items.map { it.n }.toSet()
        val gone = prev.items.map { it.n }.filter { it !in now }
        val oldLines = lines(prev.text).toHashSet()
        val newText = lines(p.text).filter { it !in oldLines }
        val newChars = newText.sumOf { it.length }
        if (changed.size > 5 && changed.size * 2 > p.items.size) return null
        if (newChars > 800 && newChars * 2 > p.text.length) return null
        return Diff(changed, gone, newText)
    }

    private fun lines(t: String) = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
