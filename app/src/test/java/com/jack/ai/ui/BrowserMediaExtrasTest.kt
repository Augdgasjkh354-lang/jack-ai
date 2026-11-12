package com.jack.ai.ui

import com.jack.ai.api.BrowserPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** 浏览器的视频倍速、长按快进、朗读切段。 */
class BrowserMediaExtrasTest {
    @Test
    fun rateText() {
        assertEquals("1×", BrowserPages.rateText(1f))
        assertEquals("1.5×", BrowserPages.rateText(1.5f))
        assertEquals("0.75×", BrowserPages.rateText(0.75f))
        assertEquals("3×", BrowserPages.rateText(3f))
    }

    @Test
    fun rateAndLongPressInPage() {
        val cx = org.mozilla.javascript.Context.enter()
        try {
            val scope = cx.initStandardObjects()
            // 假的网页：一个正在放的视频，记下事件监听和计时器
            cx.evaluateString(scope, """
                var window = this, top = this, parent = this, frames = [], listeners = {}, timers = [];
                var console = { log: function () {} };
                function setTimeout(f) { timers.push(f); return timers.length; }
                function clearTimeout(i) { timers[i - 1] = null; }
                function setInterval() {}
                window.addEventListener = function () {};
                window.postMessage = function () {};
                var v = { tagName: 'VIDEO', paused: false, ended: false, muted: false, volume: 1, playbackRate: 1,
                  getBoundingClientRect: function () { return { left: 0, top: 0, right: 400, bottom: 300, width: 400, height: 300 }; },
                  pause: function () { this.paused = true; }, play: function () {} };
                var body = { kids: [], appendChild: function (c) { this.kids.push(c); c.parentNode = this; },
                  removeChild: function (c) { this.kids.splice(this.kids.indexOf(c), 1); } };
                var document = { body: body, documentElement: body, querySelectorAll: function () { return [v]; },
                  addEventListener: function (n, f) { (listeners[n] = listeners[n] || []).push(f); },
                  createElement: function () { return { style: {} }; } };
                function fire(n, e) { (listeners[n] || []).forEach(function (f) { f(e); }); return e; }
            """.trimIndent(), "env", 1, null)
            cx.evaluateString(scope, BrowserPages.MEDIA_JS, "media", 1, null)
            val out = cx.evaluateString(scope, """
                var r = [];
                eval(${'"'}${BrowserPages.rateJs(1.5f).replace("'", "\\'")}${'"'});
                r.push(v.playbackRate);
                fire('touchstart', { touches: [{ clientX: 10, clientY: 10 }] });
                timers[timers.length - 1]();
                r.push(v.playbackRate, body.kids.length);
                fire('touchend', {});
                r.push(v.playbackRate, body.kids.length);
                var c = fire('click', { stopped: false, preventDefault: function () { this.stopped = true; }, stopPropagation: function () {} });
                r.push(c.stopped);
                __kaviMediaCommand('rate:1');
                r.push(v.playbackRate);
                fire('touchstart', { touches: [{ clientX: 10, clientY: 10 }] });
                fire('touchmove', { touches: [{ clientX: 60, clientY: 10 }] });
                r.push(timers.length, v.playbackRate);
                r.join(',')
            """.trimIndent(), "use", 1, null).toString()
            // 1.5 倍；长按变 2 倍并显示提示；松手回到 1.5、提示消失、吞掉这次点击；回到 1 倍；拖动不算长按
            assertEquals("1.5,2,1,1.5,0,true,1,2,1", out)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    @Test
    fun readerChunks() {
        val long = "第一句话很长。".repeat(100)
        val parts = PageReader.chunks("# 标题\n\n短的一段。\n\n$long\n\n---\n")
        assertEquals("标题", parts[0])
        assertEquals("短的一段。", parts[1])
        assertTrue(parts.drop(2).all { it.length <= 400 })
        assertEquals(long, parts.drop(2).joinToString(""))
        // 没标点的超长段也切开
        assertTrue(PageReader.chunks("a".repeat(1000)).all { it.length <= 400 })
    }

    @Test
    fun readerLocale() {
        assertEquals(Locale.US, PageReader.localeFor("This is an English article about browsers and speed."))
        assertEquals(Locale.CHINA, PageReader.localeFor("这是一篇中文文章，里面有 Chrome 和 WebView。"))
    }
}
