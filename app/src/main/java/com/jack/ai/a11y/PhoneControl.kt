package com.jack.ai.a11y

import android.graphics.Bitmap
import android.view.KeyEvent
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.core.cap.phone.TypingPacing
import java.io.IOException

/** 主屏、后台屏幕共用的截图判断结果。 */
data class PhoneCapture(val image: Pair<String, Float>? = null, val black: Boolean = false)

/** 同一轮任务固定使用主屏或后台屏幕；后台出错时不退回主屏。 */
class PhoneControl(val service: KaviAccessibility, val background: BackgroundPhone?) {
    private val id get() = background?.displayId ?: android.view.Display.DEFAULT_DISPLAY
    private var observedRevision = 0
    suspend fun ready() { background?.ready() }
    private suspend fun <T> control(needsScreen: Boolean = false, action: suspend () -> T): T {
        val b = background ?: return action()
        return b.control {
            if (needsScreen && observedRevision != b.revision) throw IOException("人工接管后界面已变化，请先用 read 重新读屏，再操作。")
            action()
        }
    }
    fun appLabel(pkg: String) = service.appLabel(pkg)
    fun foreground() = service.foreground(id)
    suspend fun snapshot(): Pair<String, UiNode>? = control {
        service.snapshot(id).also { observedRevision = background?.revision ?: 0 }
    }
    fun screenSize() = background?.display?.let { it.width to it.height } ?: service.screenSize()
    suspend fun click(e: Element, long: Boolean = false) = control(true) { service.click(e, long, id) }
    suspend fun tap(x: Int, y: Int, long: Boolean = false) = control(true) { service.tap(x, y, long, id) }
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = control(true) { service.swipe(x1, y1, x2, y2, displayId = id) }
    suspend fun scroll(e: Element?, direction: String) = control(true) { service.scroll(e, direction, id) }
    suspend fun type(e: Element, text: String, pace: TypingPacing = TypingPacing.Instant) = control(true) {
        service.type(e, text, id, background?.let { b -> { value -> b.shell.setClipText(value) == 0 } }, pace)
    }
    suspend fun imeEnter() = control(true) { service.imeEnter(id) }
    suspend fun back() = control { background?.shell?.injectKey(id, KeyEvent.KEYCODE_BACK) ?: service.back() }
    fun home() = if (background == null) service.home() else false
    suspend fun settle(maxMs: Long = 3000) = service.settle(maxMs, id)
    fun showBar(text: String) = service.showBar(if (background == null) text else "后台：$text")

    suspend fun open(pkg: String): Boolean = control {
        val b = background ?: return@control service.open(pkg)
        when (val result = b.shell.startApp(id, pkg)) {
            ShellService.START_OK -> true
            ShellService.START_IN_USE_ON_MAIN -> throw IOException("你正在主屏使用「${appLabel(pkg)}」。请先切换到其他 App，再继续后台任务；没有抢走主屏。")
            ShellService.START_IN_USE_BY_OTHER -> throw IOException("「${appLabel(pkg)}」正在被另一个后台任务使用。")
            else -> throw IOException("后台打开「${appLabel(pkg)}」失败（$result）。")
        }
    }

    suspend fun screenshot(marks: List<Element>): Pair<String, Float>? = checkedScreenshot(marks)?.image

    /** 主屏用无障碍截图，后台只从虚拟屏幕取画面，绝不落回主屏。 */
    suspend fun checkedScreenshot(marks: List<Element>): PhoneCapture? = control {
        val shot: Bitmap = (if (background == null) service.rawShot() else background.display.latestFrameBitmap())
            ?: return@control null
        if (service.blackFrame(shot)) {
            shot.recycle()
            PhoneCapture(black = true)
        } else PhoneCapture(image = service.mark(shot, marks))
    }

    suspend fun close(): String? = background?.close()

    companion object {
        internal val lock = kotlinx.coroutines.sync.Mutex()
    }
}
