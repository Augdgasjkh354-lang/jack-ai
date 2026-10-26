package com.jack.ai

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.jack.ai.data.Store
import com.jack.ai.data.WeChat
import java.util.concurrent.Executors

/**
 * 读微信的通知（要用户在系统设置里给「通知使用权」）。设置里没打开「微信消息交给 Kavi」时什么都不做。
 * 只拿私聊消息，攒起来交给 [WeChat]。
 */
class WeChatListener : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
    /** 同一条通知更新时正文没变（比如只是重新弹了一下）就不算新消息。 */
    private val lastText = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WeChat.PACKAGE) return
        val n = sbn.notification ?: return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val at = sbn.postTime
        val key = sbn.key
        worker.execute {
            runCatching {
                if (lastText[key] == text) return@runCatching
                if (lastText.size > 500) lastText.clear()
                lastText[key] = text.orEmpty()
                val s = Store(this).loadSettings()
                if (!s.wechatWatch) return@runCatching
                WeChat.parse(title, text, at, WeChat.skipSet(s))?.let { WeChat.add(this, it) }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        /** 用户有没有给 Kavi「通知使用权」。 */
        fun granted(ctx: Context) = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

        /** 系统重新绑定监听（有的手机改完设置不会自己连上）。 */
        fun rebind(ctx: Context) {
            if (android.os.Build.VERSION.SDK_INT >= 24) runCatching { requestRebind(ComponentName(ctx, WeChatListener::class.java)) }
        }
    }
}
