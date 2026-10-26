package com.jack.ai.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jack.ai.api.Net
import com.jack.ai.data.ProxyCore
import com.jack.ai.data.Store
import java.io.File

/**
 * 按 App 分流：用户选中的 App 的流量经系统 VPN 交给转发组件，再进 Kavi 专用代理的内核（全部走节点）；
 * 没选的 App（抖音之类）照常用手机网络，Kavi 自己也不在 VPN 里（它本来就用代理端口，也免得绕回自己）。
 */
class KaviVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var watching = false
    private var port = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Thread { shutdown("已关闭") ; stopSelf() }.start()
            return START_NOT_STICKY
        }
        Thread { runCatching { start() }.onFailure { shutdown(it.message ?: "启动失败"); stopSelf() } }.start()
        return START_STICKY
    }

    @Synchronized
    private fun start() {
        if (tun != null) return
        val s = Store(this).loadSettings()
        Net.init(this)
        Net.apply(s)
        if (!TProxyService.available) throw IllegalStateException("这一版没带上分流组件（或系统低于 Android 10）")
        if (!Net.config.enabled) throw IllegalStateException("先打开 Kavi 专用代理并导入节点")
        val apps = installed(this, s.proxyApps)
        if (apps.isEmpty()) throw IllegalStateException("还没选要走代理的 App")
        if (!Net.ensureCore()) throw IllegalStateException("代理内核没起来：" + (ProxyCore.lastError ?: "原因不明"))
        val b = Builder().setSession("Kavi 专用代理").setMtu(MTU)
            .addAddress(V4, 32).addRoute("0.0.0.0", 0)
            .addAddress(V6, 128).addRoute("::", 0)
            .addDnsServer(DNS)
        apps.forEach { runCatching { b.addAllowedApplication(it) } }
        b.setMetered(false)
        val fd = b.establish() ?: throw IllegalStateException("系统没让建 VPN（可能没授权，或别的 VPN 正开着）")
        tun = fd
        launch(fd)
        state = "运行中：${apps.size} 个 App 走代理"
        running = true
        watch()
    }

    /** 写好配置，把转发组件接到 VPN 和现在的代理端口上。 */
    private fun launch(fd: ParcelFileDescriptor) {
        port = ProxyCore.port
        val cfg = File(noBackupFilesDir, "proxy/tunnel.yml").apply { parentFile?.mkdirs(); writeText(config(ProxyCore.port, ProxyCore.user, ProxyCore.pass)) }
        if (!TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)) throw IllegalStateException("转发组件没启动起来")
    }

    /** 盯着内核：关了代理就停；内核重启换了端口就把转发组件接到新端口。 */
    private fun watch() {
        watching = true
        Thread {
            while (watching) {
                Thread.sleep(3000)
                if (!watching) break
                runCatching {
                    Net.apply(Store(this).loadSettings())
                    if (!Net.config.enabled) { shutdown("Kavi 专用代理关了，分流也停了"); stopSelf(); return@Thread }
                    if (!ProxyCore.isRunning) Net.ensureCore()
                    val fd = tun
                    if (fd != null && ProxyCore.isRunning && ProxyCore.port != port) {
                        TProxyService.TProxyStopService()
                        launch(fd)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun shutdown(why: String) {
        watching = false
        if (TProxyService.available) runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        running = false
        state = why
    }

    /** 用户在系统里断开了，或者别的 VPN 顶掉了。 */
    override fun onRevoke() {
        shutdown("被系统或别的 VPN 断开了")
        stopSelf()
    }

    override fun onDestroy() {
        shutdown(if (running) "已停止" else state)
        super.onDestroy()
    }

    companion object {
        const val STOP = "com.jack.ai.vpn.STOP"
        const val MTU = 8500
        const val V4 = "198.18.0.1"
        const val V6 = "fc00::1"
        /** 转发组件自己答 DNS（假地址），真正的域名交给内核在节点那边解析，不漏到本地网络。 */
        const val DNS = "198.18.0.2"

        var running by mutableStateOf(false)
            private set
        /** 给设置页看的一句状态。 */
        var state by mutableStateOf("")
            private set

        /** 转发组件的配置：本机代理端口（带口令）、UDP 走 SOCKS5、DNS 用映射地址。 */
        fun config(port: Int, user: String, pass: String): String = """
            tunnel:
              mtu: $MTU
              ipv4: $V4
              ipv6: '$V6'
            socks5:
              port: $port
              address: 127.0.0.1
              udp: 'udp'
              username: '$user'
              password: '$pass'
            mapdns:
              address: $DNS
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              task-stack-size: 81920
              log-level: warn
        """.trimIndent() + "\n"

        /** 选中的 App 里还装着的（卸载了的跳过；Kavi 自己不放进去）。 */
        fun installed(ctx: Context, pkgs: List<String>): List<String> = pkgs.distinct().filter { p ->
            p != ctx.packageName && runCatching { ctx.packageManager.getApplicationInfo(p, 0) }.isSuccess
        }

        /** 启动前要先问用户要 VPN 授权：返回要弹的系统界面，已经授权过返回 null。 */
        fun consent(ctx: Context): Intent? = prepare(ctx)

        fun start(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, KaviVpnService::class.java)) }.onFailure { state = "启动不了：${it.message}" }
        }

        fun stop(ctx: Context) {
            if (!running) return
            runCatching { ctx.startService(Intent(ctx, KaviVpnService::class.java).setAction(STOP)) }
        }
    }
}
