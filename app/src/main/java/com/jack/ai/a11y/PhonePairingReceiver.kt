package com.jack.ai.a11y

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 在系统的配对码窗口还开着时，从通知栏直接回填六位码。 */
class PhonePairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STOP) { BackgroundPhone.current.value?.stop(); return }
        if (intent.action != PAIR) return
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString() ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val engine = PhoneServices.of(context).shellManager.engine
                engine.startPairing(code)
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val text = if (engine.status.value == BuiltinShellStatus.Running) "后台手机助手已连接" else "配对未完成，请回到 Kavi 设置查看状态或重试"
                manager.notify(NOTICE, NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle(text).setAutoCancel(true).build())
            } finally { pending.finish() }
        }
    }

    companion object {
        const val STOP = "com.jack.ai.phone.STOP"
        private const val PAIR = "com.jack.ai.phone.PAIR"
        private const val CODE = "pairing-code"
        private const val NOTICE = 6102
        private const val CHANNEL = "phone-pairing"

        fun show(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "无线调试配对", NotificationManager.IMPORTANCE_HIGH))
            val input = RemoteInput.Builder(CODE).setLabel("输入六位配对码").build()
            val reply = PendingIntent.getBroadcast(context, NOTICE, Intent(context, PhonePairingReceiver::class.java).setAction(PAIR), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            manager.notify(NOTICE, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Kavi 后台屏幕配对")
                .setContentText("打开「使用配对码配对设备」，保持窗口打开，在这条通知里填六位码")
                .setStyle(NotificationCompat.BigTextStyle().bigText("打开无线调试 → 使用配对码配对设备。保持配对窗口打开，下拉通知栏，点「输入配对码」。"))
                .setOngoing(true).addAction(NotificationCompat.Action.Builder(0, "输入配对码", reply).addRemoteInput(input).build()).build())
        }
    }
}
