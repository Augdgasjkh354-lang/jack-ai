package com.jack.ai.vpn

/**
 * 转发组件（hev-socks5-tunnel，打包时从源码编译成 libhev-socks5-tunnel.so）：把 VPN 收到的流量交给本机的代理端口。
 * 组件在加载时按「com/jack/ai/vpn/TProxyService」找这几个方法，类名、包名、方法名都不能改。
 * 这一版没带上组件（打包时没编出来）或手机系统太老时 available 是 false，功能直接关掉，不会闪退。
 */
object TProxyService {
    val available: Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
        runCatching { System.loadLibrary("hev-socks5-tunnel"); true }.getOrDefault(false)

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    /** [发出的包, 发出的字节, 收到的包, 收到的字节]。 */
    @JvmStatic external fun TProxyGetStats(): LongArray
}
