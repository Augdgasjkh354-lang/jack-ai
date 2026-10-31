package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDataTest {
    @Test
    fun masksVerificationCodes() {
        assertEquals("【淘宝】您的验证码是 ****，5 分钟内有效", PhoneData.mask("【淘宝】您的验证码是 384920，5 分钟内有效"))
        assertEquals("Your code is ****", PhoneData.mask("Your code is A7K9Q2"))
        // 不是验证码的通知不动
        assertEquals("您的快递已到菜鸟驿站，取件码 3-2-1045", PhoneData.mask("您的快递已到菜鸟驿站，取件码 3-2-1045"))
    }

    @Test
    fun whatGetsLogged() {
        assertTrue(PhoneData.keep("com.sankuai.meituan", "美团", "com.jack.ai", emptyList(), false, "外卖已送达", "请取餐"))
        assertFalse(PhoneData.keep("com.eg.android.AlipayGphone", "支付宝", "com.jack.ai", emptyList(), false, "付款成功", "¥12"))
        assertFalse(PhoneData.keep("com.tencent.mm", "微信", "com.jack.ai", emptyList(), false, "张三", "在吗"))
        assertFalse(PhoneData.keep("com.x", "X", "com.jack.ai", emptyList(), true, "下载中", "45%"))
        assertFalse(PhoneData.keep("com.x", "X", "com.jack.ai", listOf("com.x"), false, "t", "x"))
        assertFalse(PhoneData.keep("com.jack.ai", "Kavi", "com.jack.ai", emptyList(), false, "t", "x"))
    }

    @Test
    fun searchByAppTextAndTime() {
        val now = 10_000_000_000L
        val list = listOf(
            Notice(now - 3_600_000, "com.a", "菜鸟", "快递到了", "取件码 12-3"),
            Notice(now - 30 * 3_600_000L, "com.b", "日历", "开会", "明天 9 点"),
            Notice(now - 60_000, "com.b", "日历", "改期", "会议改到下午"),
        )
        assertEquals(listOf("改期", "快递到了"), PhoneData.search(list, "", "", 24, now).map { it.title })
        assertEquals(listOf("快递到了"), PhoneData.search(list, "取件", "", 24, now).map { it.title })
        assertEquals(listOf("改期", "开会"), PhoneData.search(list, "", "日历", 72, now).map { it.title })
        assertEquals("1 小时 5 分", PhoneData.dur(65 * 60_000L))
    }
}
