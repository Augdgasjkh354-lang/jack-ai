/*
 * Source: https://github.com/wangzan101/Mika, commit 0091d2d (Apache-2.0).
 * Jack AI modification: classify Kavi's readable element facts without Mika ScreenPort.
 */
package com.wangzan101.mika.core.uistate

enum class ScreenState { NORMAL, BLANK, LOADING, PROTECTED, NEEDS_HUMAN }

data class ScreenFacts(
    val currentApp: String?,
    val readableElements: Int,
    val texts: List<String>,
    val passwordField: Boolean = false,
    val signedOut: Boolean = false,
    val changing: Boolean = false,
    val blackFrames: List<Boolean> = emptyList(),
    val hasWindow: Boolean = true,
)

data class ScreenCheck(
    val state: ScreenState,
    val needsHuman: Boolean = false,
    val reason: String? = null,
)

/** 登录、验证码、防截屏、加载中。付款规则仍由 PhoneGuard 先处理。 */
object ScreenDiagnosis {
    private val captcha = listOf("captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块",
        "向右滑动完成", "请完成验证", "verify you are human", "not a robot")
    private val code = listOf("短信验证码", "获取验证码", "发送验证码", "verification code", "get code")

    fun classify(f: ScreenFacts): ScreenCheck {
        val text = f.texts.joinToString("\n").lowercase()
        if (f.changing && f.blackFrames.any { it })
            return ScreenCheck(ScreenState.LOADING)
        val reason = when {
            captcha.any { it in text } -> "captcha"
            f.passwordField || f.signedOut || code.any { it in text } -> "login"
            else -> null
        }
        if (f.hasWindow && f.blackFrames.size >= 2 && f.blackFrames.all { it })
            return ScreenCheck(ScreenState.PROTECTED, true, "protected")
        if (reason != null) return ScreenCheck(ScreenState.NEEDS_HUMAN, true, reason)
        if (f.changing) return ScreenCheck(ScreenState.LOADING)
        if (!f.hasWindow || f.readableElements == 0) return ScreenCheck(ScreenState.BLANK)
        return ScreenCheck(ScreenState.NORMAL)
    }
}
