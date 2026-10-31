package com.jack.ai.a11y

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.text.InputType

/**
 * Kavi 当手机的「默认数字助理」：长按电源键、从屏幕角落划（各家手机不一样）叫出来时，
 * 系统把当前屏幕的文字（用户在系统里允许「使用屏幕上的文字」时）和截图交给 Kavi，开一个新对话问它。
 * 这条路不用开无障碍。语音识别那部分系统要求得有，Kavi 不用，只占个位。
 */
class KaviVoiceService : VoiceInteractionService()

class KaviVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = KaviAssistSession(this)
}

/** 系统要求助理带一个语音识别服务；Kavi 用自己的语音输入，这里直接回「不支持」。 */
class KaviRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

class KaviAssistSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private var pkg = ""
    private var label = ""
    private var text = ""
    private var shot: Bitmap? = null
    private var gotText = false
    private var gotShot = false
    private var done = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // 屏幕内容和截图是分开、异步送来的；用户在系统里关了其中一个就等不到它，最多等 1.5 秒
        main.postDelayed({ finishUp() }, 1500)
    }

    @Deprecated("系统仍会调用：安卓 10 以上的新版本默认转到这里")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        if (structure != null) {
            pkg = structure.activityComponent?.packageName.orEmpty()
            label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val roots = (0 until structure.windowNodeCount).map { i ->
                val w = structure.getWindowNodeAt(i)
                toUiNode(w.rootViewNode, w.left, w.top)
            }
            text = ScreenText.plain(Screen(pkg, label, ScreenText.elements(UiNode(bounds = intArrayOf(0, 0, 100_000, 100_000), children = roots))))
        }
        gotText = true
        if (gotShot) finishUp()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        shot = screenshot
        gotShot = true
        if (gotText) finishUp()
    }

    private fun finishUp() {
        if (done) return
        done = true
        main.removeCallbacksAndMessages(null)
        val why = if (pkg.isNotEmpty()) ScreenAsk.refuse(context, pkg, label) else null
        val intent = if (why != null) {
            ScreenAsk.toast(context, why)
            null
        } else if (text.isBlank() && shot == null) {
            // 系统没给屏幕内容（用户关了「使用屏幕上的文字」）：直接开个新对话
            Intent(context, com.jack.ai.MainActivity::class.java).setAction(com.jack.ai.MainActivity.ACTION_NEW_CHAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else ScreenAsk.intent(context, label.ifBlank { "当前屏幕" }, text, shot)
        if (intent != null) runCatching {
            if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(intent) else context.startActivity(intent)
        }
        hide()
    }

    /** 系统给的控件树 → UiNode（位置换成屏幕上的绝对位置）。 */
    private fun toUiNode(v: AssistStructure.ViewNode, ox: Int, oy: Int): UiNode {
        val l = ox + v.left - v.scrollX
        val t = oy + v.top - v.scrollY
        val visible = v.visibility == android.view.View.VISIBLE
        val cls = v.className.orEmpty()
        val pwd = (v.inputType and InputType.TYPE_MASK_VARIATION).let {
            it == InputType.TYPE_TEXT_VARIATION_PASSWORD || it == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                it == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || it == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        return UiNode(
            cls = cls, text = v.text?.toString().orEmpty(), desc = v.contentDescription?.toString().orEmpty(), hint = v.hint.orEmpty(),
            bounds = if (visible) intArrayOf(l, t, l + v.width, t + v.height) else IntArray(4),
            clickable = v.isClickable, longClickable = v.isLongClickable, editable = "EditText" in cls,
            checkable = v.isCheckable, checked = v.isChecked, enabled = v.isEnabled, password = pwd,
            children = if (visible) (0 until v.childCount).map { toUiNode(v.getChildAt(it), l, t) } else emptyList(),
        )
    }
}
