/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: adapted to Kavi's small phone-state inputs, without Mika Agent/ScreenPort.
 */
package com.wangzan101.mika.core.cap.phone

import kotlinx.coroutines.delay

/** Jack AI modification: 沿用 Mika AppDwell 的停留计算，使用 Kavi 自己的屏幕和取消机制。 */
class AppDwell(
    private val pacing: AppPacing,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun before(
        app: String, textChars: Int, careful: Boolean, policyFloorMs: Long,
        lastActionAt: Long?, gate: Boolean,
    ): Long {
        if (gate && pacing.capReached(app, careful))
            throw PageLimitReached(app, pacing.screensIn(app), pacing.screenCap)
        val now = clock()
        val floor = lastActionAt?.let { (policyFloorMs - (now - it)).coerceAtLeast(0L) } ?: 0L
        val wait = maxOf(pacing.waitBefore(app, now, textChars), floor)
        if (wait > 0) sleep(wait)
        return wait
    }

    fun after(app: String?, atMs: Long, newScreen: Boolean, careful: Boolean, counts: Boolean, batchKind: String? = null) =
        pacing.noteAction(app, atMs, newScreen, careful, counts, batchKind)

    fun outwardDone(app: String) = pacing.outwardDone(app)
    fun userAnswered() = pacing.userAnswered()
}

/** 需要用户重新发起，不能在当前轮里继续连续翻屏。 */
class PageLimitReached(val app: String, val screens: Int, val cap: Int) :
    Exception("App $app reached $screens screens (limit $cap)")
