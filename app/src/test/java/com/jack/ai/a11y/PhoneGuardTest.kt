package com.jack.ai.a11y

import com.jack.ai.a11y.PhoneGuard.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneGuardTest {
    private val self = "com.jack.ai"

    @Test
    fun moneyAndSystemAppsAreOffLimits() {
        assertNotNull(PhoneGuard.blocked("com.eg.android.AlipayGphone", "支付宝", self))
        assertNotNull(PhoneGuard.blocked("com.example.somebank", "某某银行", self))
        assertNotNull(PhoneGuard.blocked("com.xyz.app", "XX证券", self))
        assertNotNull(PhoneGuard.blocked("com.android.settings", "设置", self))
        assertNotNull(PhoneGuard.blocked("com.android.permissioncontroller", "权限", self))
        assertNotNull(PhoneGuard.blocked(self, "Kavi", self))
        assertNull(PhoneGuard.blocked("com.tencent.mm", "微信", self))
        assertNull(PhoneGuard.blocked("com.taobao.taobao", "淘宝", self))
        assertNull(PhoneGuard.blocked("com.ss.android.ugc.aweme", "抖音", self))
    }

    @Test
    fun buttonRisk() {
        assertEquals(Risk.MONEY, PhoneGuard.risk("立即支付"))
        assertEquals(Risk.MONEY, PhoneGuard.risk("转账"))
        assertEquals(Risk.MONEY, PhoneGuard.risk("提交订单 ¥23.00"))
        assertEquals(Risk.ASK, PhoneGuard.risk("发送"))
        assertEquals(Risk.ASK, PhoneGuard.risk("删除"))
        assertEquals(Risk.ASK, PhoneGuard.risk("关注"))
        assertEquals(Risk.OK, PhoneGuard.risk("搜索"))
        assertEquals(Risk.OK, PhoneGuard.risk("通讯录"))
        assertEquals(Risk.OK, PhoneGuard.risk(""))
        // 长的是列表里的一行内容，点开不算付钱、发送
        assertEquals(Risk.OK, PhoneGuard.risk("张三 [转账] 请确认收款 昨天 22:10"))
    }

    @Test
    fun payPages() {
        assertEquals("支付密码", PhoneGuard.payPage(listOf("返回", "请输入支付密码", "忘记密码")))
        assertNotNull(PhoneGuard.payPage(listOf("确认付款", "¥ 12.00")))
        assertNull(PhoneGuard.payPage(listOf("微信", "通讯录", "张三 [转账]", "发现")))
    }
}
