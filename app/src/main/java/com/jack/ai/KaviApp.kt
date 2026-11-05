package com.jack.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.jack.ai.ui.BrowserCdp

/** 进程一起来就装上闪退记录：后台服务、定时任务、无障碍这些不经过主界面的入口闪退了也记得到。 */
class KaviApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = BrowserCdp.foreground()
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        CrashLog.install(this)
        // 上次攒下的闪退报告：后台发到 GitHub Issues。只在主进程发（别的进程也会走到这里，免得重复发）；单元测试里不发
        val main = android.os.Build.VERSION.SDK_INT < 28 || getProcessName() == packageName
        if (!main || android.os.Build.FINGERPRINT == "robolectric") return
        // 上次是在底层崩掉或卡死被系统杀的（网页组件常这样）：补记下来，主界面打开时弹框、稍后上报
        runCatching { CrashLog.collectExits(this) }
        if (android.os.Build.VERSION.SDK_INT >= 31 && com.jack.ai.data.Store(this).loadSettings().phoneBackground) {
            com.wangzan101.mika.cap.phone.PhoneServices.of(this).shellManager.start()
        }
        // 思路来自 deepseek-ai/deepseek-harness packages/goal/goal-round-driver/，MIT。
        com.jack.ai.data.Goals.recover(this)
        Thread {
            runCatching { CrashLog.upload(this, com.jack.ai.data.Store(this).loadSettings().githubToken) }
        }.apply { isDaemon = true; name = "crash-upload" }.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) BrowserCdp.background()
    }
}
