package com.jack.ai

import android.app.Application

/** 进程一起来就装上闪退记录：后台服务、定时任务、无障碍这些不经过主界面的入口闪退了也记得到。 */
class KaviApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // 上次攒下的闪退报告：后台发到 GitHub Issues。只在主进程发（别的进程也会走到这里，免得重复发）；单元测试里不发
        val main = android.os.Build.VERSION.SDK_INT < 28 || getProcessName() == packageName
        if (!main || android.os.Build.FINGERPRINT == "robolectric") return
        if (android.os.Build.VERSION.SDK_INT >= 31 && com.jack.ai.data.Store(this).loadSettings().phoneBackground) {
            com.wangzan101.mika.cap.phone.PhoneServices.of(this).shellManager.start()
        }
        Thread {
            runCatching { CrashLog.upload(this, com.jack.ai.data.Store(this).loadSettings().githubToken) }
        }.apply { isDaemon = true; name = "crash-upload" }.start()
    }
}
