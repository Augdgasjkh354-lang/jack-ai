package com.jack.ai.api

import android.content.Context
import com.jack.ai.data.ProxyCore
import com.jack.ai.data.ProxyNode
import com.jack.ai.data.Settings
import okhttp3.Authenticator
import okhttp3.Credentials
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Kavi 的网络分流：开了「Kavi 专用代理」时，国外的服务走 App 里的代理内核，国内的直连；
 * 每个连接器也可以单独指定。没开时完全是原来的样子（跟随系统）。
 * 只管走 OkHttp 的请求（模型、联网、连接器、12306……）；邮箱、小工具网页、Python 不在内。
 */
object Net {
    /** 一定直连的：国内服务和本机。按域名后缀匹配。 */
    val DOMESTIC = listOf(
        ".cn", "deepseek.com", "amap.com", "autonavi.com", "qq.com", "163.com", "126.com", "yeah.net", "aliyun.com",
        "aliyuncs.com", "alicdn.com", "baidu.com", "bdstatic.com", "volces.com", "volcengine.com", "bytedance.com",
        "xfyun.cn", "bigmodel.cn", "zhipuai.cn", "minimaxi.com", "stepfun.com",
        "jd.com", "taobao.com", "tmall.com", "weibo.com", "bilibili.com", "zhihu.com", "douyin.com", "xiaohongshu.com",
        "sina.com", "sohu.com", "ifeng.com", "dianping.com", "meituan.com", "ctrip.com", "12306.cn",
        // 电商和它们的图片、风控、支付域名：页面直连、这些却走代理的话，网站看到两个 IP，会断开或要求登录验证
        "1688.com", "alibaba.com", "alibaba-inc.com", "alipay.com", "alipayobjects.com", "mmstat.com", "tbcdn.cn",
        "taobaocdn.com", "alikunlun.com", "aliapp.org", "fliggy.com", "ele.me", "alipan.com", "aliyundrive.com",
        "360buyimg.com", "jd.hk", "jdpay.com", "jdcloud.com", "jdcloud-openapi.com", "pinduoduo.com", "yangkeduo.com",
        "pddpic.com", "vip.com", "suning.com", "dangdang.com", "poizon.com", "dewu.com",
        // 手机厂商、腾讯、字节、快手等的主站和图片
        "mi.com", "xiaomi.com", "huawei.com", "vmall.com", "hicloud.com", "dbankcloud.com", "honor.com", "oppo.com", "vivo.com",
        "tencent.com", "gtimg.com", "myqcloud.com", "tenpay.com", "wechat.com", "toutiao.com", "snssdk.com", "byteimg.com",
        "pstatp.com", "douyinpic.com", "douyinstatic.com", "ixigua.com", "kuaishou.com", "yximgs.com", "hdslb.com",
        "zhimg.com", "xhscdn.com", "youku.com", "iqiyi.com", "qiyipic.com", "douban.com", "doubanio.com",
        // 常用的资讯、工具、招聘、出行
        "csdn.net", "cnblogs.com", "jianshu.com", "360.com", "so.com", "sogou.com", "36kr.com", "huxiu.com", "eastmoney.com",
        "xueqiu.com", "cnki.net", "dingtalk.com", "yuque.com", "zhipin.com", "58.com", "ke.com", "lianjia.com", "anjuke.com",
        "qunar.com", "didiglobal.com", "unionpay.com", "95516.com", "hupu.com",
    )

    data class Config(
        val enabled: Boolean = false,
        val nodes: List<ProxyNode> = emptyList(),
        val selected: String = "",
        /** 加强保护：只挑加密强的节点自动选、加密 DNS、浏览器防 WebRTC 泄漏、按网站总走代理。关掉回到原来的做法。 */
        val hardened: Boolean = true,
    )

    @Volatile var config = Config()
        private set
    @Volatile private var appDir: File? = null
    @Volatile private var binary: File? = null

    /** App 启动、后台任务开始时调一次，告诉它内核在哪。 */
    fun init(ctx: Context) {
        if (binary != null) return
        setPaths(File(ctx.applicationInfo.nativeLibraryDir, "libsingbox.so"), File(ctx.noBackupFilesDir, "proxy"))
    }

    internal fun setPaths(bin: File, dir: File) {
        binary = bin
        appDir = dir
    }

    /** 测试用：直接给配置。 */
    internal fun setConfig(c: Config) { config = c }

    /** 设置变了（读出或保存设置时）就更新；关掉代理时顺手停掉内核。 */
    fun apply(s: Settings) {
        val c = Config(s.proxyEnabled && s.proxyNodes.isNotEmpty(), s.proxyNodes, s.proxyNode, s.proxyHardened)
        config = c
        if (!c.enabled && ProxyCore.isRunning) ProxyCore.stop()
    }

    /** 这个主机走不走代理。 */
    fun shouldProxy(host: String, c: Config = config): Boolean = c.enabled && foreign(host)

    /** 国外服务（不在直连名单里，也不是本机、局域网）。 */
    fun foreign(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.startsWith("127.") || h == "::1" || h.startsWith("10.") || h.startsWith("192.168.")) return false
        return DOMESTIC.none { d -> if (d.startsWith(".")) h.endsWith(d) else h == d || h.endsWith(".$d") }
    }

    /** 网络出错时告诉模型下一步怎么办：看代理开没开、是不是国外服务。host 不知道就填 null。 */
    fun nextStep(host: String?): String = when {
        config.enabled -> "下一步：稍后重试一次；还不行就请用户到 设置 → Kavi 专用代理 点「测试」看节点通不通，必要时换节点或更新订阅。"
        host != null && foreign(host) -> "下一步：$host 是国外服务，需要先打开 Kavi 专用代理（设置 → Kavi 专用代理），再重试。"
        else -> "下一步：检查手机网络后重试一次；连续失败就如实告诉用户网络有问题，不要反复重试。"
    }

    /** 把内核拉起来（已经在跑就直接返回）。失败时 ProxyCore.lastError 里有原因。 */
    fun ensureCore(c: Config = config): Boolean {
        val bin = binary ?: return false
        val dir = appDir ?: return false
        return ProxyCore.ensure(bin, dir, c.nodes, c.selected, c.hardened)
    }

    /** 本机一个没人听的端口：内核起不来时把请求指到这里，宁可失败也不偷偷直连。 */
    private val DEAD = InetSocketAddress("127.0.0.1", 9)

    /** 按规则分流（auto）；连接器指定了 direct / proxy 的，用 selectorFor。 */
    val selector: ProxySelector = selectorFor("auto")

    /**
     * route = auto：按规则；direct：直连（跟随系统）；proxy：走 Kavi 专用代理（没开或没节点时请求会失败，不偷偷直连）。
     */
    fun selectorFor(route: String): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            val proxy = when (route) {
                "direct" -> false
                "proxy" -> true
                else -> shouldProxy(host)
            }
            if (!proxy || host == "127.0.0.1" || host == "localhost") return (getDefault()?.select(uri)).orEmpty().ifEmpty { listOf(Proxy.NO_PROXY) }
            val c = config
            // 指定走代理但代理没开：照样指向代理（会失败并提示），不偷偷直连
            val ok = c.enabled && ensureCore(c)
            return listOf(Proxy(Proxy.Type.HTTP, if (ok) InetSocketAddress("127.0.0.1", ProxyCore.port) else DEAD))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
    }

    /** 代理端口要口令：被要求时（407）带上。 */
    val authenticator = Authenticator { _, response ->
        if (response.request.header("Proxy-Authorization") != null) null
        else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(ProxyCore.user, ProxyCore.pass)).build()
    }

    /** 报错时补一句：这个请求是不是走的代理、代理为什么没起来。 */
    fun hint(): String = when {
        !config.enabled -> ""
        else -> ProxyCore.lastError?.let { "（Kavi 专用代理：$it）" } ?: ""
    }
}
