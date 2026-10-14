package com.jack.ai.data

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Undefined

/** 用 Rhino 在手机上运行一小段 JavaScript，给 AI 做精确计算用。只能算，不能联网、不能读文件。 */
object JsRunner {
    private const val TIMEOUT_MS = 8_000L

    private class TimeoutError : Error("运行超时（超过 8 秒），代码可能死循环了")

    private object Factory : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 10_000
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val start = cx.getThreadLocal("start") as? Long ?: return
            if (System.currentTimeMillis() - start > TIMEOUT_MS) throw TimeoutError()
        }
    }

    private const val PRELUDE = """
var __out = [];
var console = { log: function () {
  var a = [];
  for (var i = 0; i < arguments.length; i++) {
    var x = arguments[i];
    a.push(typeof x === 'object' ? JSON.stringify(x) : String(x));
  }
  __out.push(a.join(' '));
} };
console.info = console.log; console.warn = console.log; console.error = console.log;
"""

    fun run(code: String): String {
        val cx = Factory.enterContext()
        return try {
            cx.putThreadLocal("start", System.currentTimeMillis())
            val scope = cx.initSafeStandardObjects()
            cx.evaluateString(scope, PRELUDE, "prelude", 1, null)
            val result = cx.evaluateString(scope, code, "code", 1, null)
            val out = Context.toString(cx.evaluateString(scope, "__out.join('\\n')", "out", 1, null))
            buildString {
                if (out.isNotBlank()) append("输出：\n").append(out.take(8000)).append('\n')
                if (result != null && result !is Undefined) {
                    val r = if (result is org.mozilla.javascript.Scriptable) {
                        runCatching { Context.toString(org.mozilla.javascript.NativeJSON.stringify(cx, scope, result, null, null)) }
                            .getOrDefault(Context.toString(result))
                    } else Context.toString(result)
                    append("结果：").append(r.take(4000))
                }
            }.ifBlank { "代码运行完了，但没有输出。用 console.log() 打印结果。" }
        } catch (e: TimeoutError) {
            "出错：${e.message}"
        } catch (e: Throwable) {
            "出错：${e.message ?: e.javaClass.simpleName}"
        } finally {
            Context.exit()
        }
    }
}
