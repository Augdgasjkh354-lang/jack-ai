package com.jack.ai

/**
 * 正式版（上应用商店）还是开发版：同一份代码，打包时定（见 app/build.gradle.kts 的 productFlavors）。
 * 正式版去掉：浏览器、操作其他 App（无障碍）、后台手机助手、Kavi 专用代理（VPN）、Linux 沙箱、读通知和微信消息、
 * 使用时长、App 内自更新（商店里更新）、默认浏览器。代码还在，只是不给工具、不显示入口、清单里不声明。
 */
object Edition {
    val STORE: Boolean = BuildConfig.STORE

    /** 这些功能正式版里没有。 */
    val browser get() = !STORE
    val phoneControl get() = !STORE
    val proxy get() = !STORE
    val linux get() = !STORE
    val notices get() = !STORE
    val usage get() = !STORE
    val selfUpdate get() = !STORE

    /** 正式版里没有的功能在设置里一律关着。 */
    fun limit(s: com.jack.ai.data.Settings): com.jack.ai.data.Settings = if (!STORE) s else s.copy(
        phoneApps = emptyList(), floatBall = false, remotePhone = false, phoneBackground = false,
        proxyEnabled = false, proxyAppsOn = false,
        linuxSandbox = false,
        noticeWatch = false, usageOn = false, wechatWatch = false,
        autoUpdate = false, linksInApp = false,
    )
}
