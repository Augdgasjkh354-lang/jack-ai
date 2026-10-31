package com.jack.ai.a11y

/**
 * 操作其他 App 时的规矩（纯规则，单测）：
 * - 涉及钱的 App（支付宝、银行、证券、钱包……）和付款页面一律不碰，遇到就停下交给用户；
 * - 系统设置、安装器、权限弹窗、Kavi 自己也不碰（免得自己给自己开权限、点掉自己的确认框）；
 * - 只能操作用户勾过的 App；
 * - 发送、删除、提交、关注这类往外做事的按钮，点之前先问用户（完全授权时不问，事后报告）。
 */
object PhoneGuard {
    /** 涉及钱的 App：包名。 */
    private val MONEY_PACKAGES = setOf(
        "com.eg.android.AlipayGphone", "com.alipay.mobile.client", "com.unionpay", "com.jd.jrapp", "com.baidu.wallet",
        "com.icbc", "com.chinamworld.main", "com.android.bankabc", "com.chinamworld.bocmbci", "cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity",
        "com.bankcomm.Bankcomm", "com.ecitic.bank.mobile", "cn.com.spdb.mobilebank.per", "com.cib.cibmb", "com.pingan.paces.ccms",
        "com.cebbank.mobile.cemb", "com.cmbc.cc.mbank", "com.yitong.mbank.psbc", "com.webank.wemoney", "com.mybank.android.phone",
        "com.hexin.plat.android", "com.eastmoney.android.berlin", "com.eastmoney.android.fund", "cn.futu.trader", "com.tigerbrokers.stock",
        "com.huatai.htsc.zhangle", "com.gtja.junhong", "com.antfortune.wealth", "com.tencent.wemoney.app", "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.huawei.wallet", "com.mipay.wallet",
    )
    /** 包名或 App 名里带这些的也算（各家银行、证券太多，列不全）。 */
    private val MONEY_PKG_HINTS = listOf("bank", "alipay", "wallet", "securities", "stock", "fund", ".pay.", "finance")
    private val MONEY_LABEL = Regex("银行|证券|支付|钱包|金融|理财|基金|信用卡|股票|期货|炒股|贷|保险|云闪付|付款|PayPal|Wallet|Bank", RegexOption.IGNORE_CASE)

    /** 系统里不能让 Kavi 碰的：设置、安装器、权限弹窗、系统界面、Kavi 自己。 */
    private val SYSTEM_PACKAGES = setOf(
        "com.android.settings", "com.android.systemui", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.miui.securitycenter",
        "com.huawei.systemmanager", "com.coloros.safecenter", "com.vivo.permissionmanager", "com.miui.packageinstaller",
        "com.android.vending", "com.xiaomi.market", "com.huawei.appmarket", "com.heytap.market", "com.bbk.appstore",
    )

    /** 为什么不能碰这个 App（能碰就是 null）。self 是 Kavi 自己的包名。 */
    fun blocked(pkg: String, label: String, self: String): String? = when {
        pkg == self -> "这是 Kavi 自己"
        pkg in SYSTEM_PACKAGES || pkg.startsWith("com.android.settings") -> "系统设置、安装和权限相关的，Kavi 不碰"
        money(pkg, label) -> "涉及钱，Kavi 不碰"
        else -> null
    }

    fun money(pkg: String, label: String): Boolean {
        val p = pkg.lowercase()
        return pkg in MONEY_PACKAGES || MONEY_PKG_HINTS.any { it in p } || MONEY_LABEL.containsMatchIn(label)
    }

    /** 屏幕上出现这些，说明到了付款、转账、输密码的页面：停下交给用户。 */
    private val PAY_PAGE = Regex("支付密码|确认付款|确认支付|立即支付|付款码|收款码|免密支付|指纹支付|转账金额|确认转账|输入密码完成|请输入密码|安全键盘")

    /** 这一屏是不是付款相关的页面（返回认出来的那句，不是就 null）。 */
    fun payPage(texts: List<String>): String? = texts.firstNotNullOfOrNull { t -> PAY_PAGE.find(t)?.value }

    /** 点了就要花钱的按钮：一律不点，交给用户。 */
    private val MONEY_BUTTON = Regex("支付|付款|转账|充值|提现|购买|下单|结算|买单|打赏|红包|立即抢|开通会员|续费|订阅|确认订单|提交订单|去付款")

    /** 往外做事的按钮：点之前先问用户。 */
    private val OUTWARD_BUTTON = Regex(
        "发送|发布|发表|提交|确定|确认|删除|移除|清空|撤回|关注|取关|拉黑|举报|评论|回复|转发|分享|拨打|呼叫|通话|" +
            "同意|接受|拒绝|授权|允许|退出|注销|卸载|上传|邀请|加好友|添加到通讯录|申请|报名|预约|投诉|签收|send|post|submit|delete|confirm|share|reply",
        RegexOption.IGNORE_CASE,
    )

    enum class Risk { OK, ASK, MONEY }

    /** 比这长的标签不当按钮看。 */
    const val BUTTON_MAX = 16

    /**
     * 点这个按钮（标签是按钮上的字）有多大风险。只看像按钮的短标签：长的是列表里的一行内容
     * （比如聊天列表里「张三：[转账] 昨天」），点它只是打开，不算付钱、发送；真到了付款页面由 payPage 拦。
     */
    fun risk(label: String): Risk = when {
        label.isBlank() || label.trim().length > BUTTON_MAX -> Risk.OK
        MONEY_BUTTON.containsMatchIn(label) -> Risk.MONEY
        OUTWARD_BUTTON.containsMatchIn(label) -> Risk.ASK
        else -> Risk.OK
    }
}
