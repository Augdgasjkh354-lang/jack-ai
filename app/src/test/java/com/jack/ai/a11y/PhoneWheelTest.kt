package com.jack.ai.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWheelTest {
    private fun item(n: Int, label: String, y: Int) = Element(n, "文字", label, intArrayOf(0, y - 20, 100, y + 20), emptyList(), false, false, false, false, null, true)

    @Test fun findsCurrentAndDirection() {
        val box = intArrayOf(0, 0, 100, 300)
        val items = listOf(item(1, "08", 50), item(2, "09", 150), item(3, "10", 250))
        val cur = PhoneWheel.current(items, box)!!
        assertEquals("09", cur.label)
        assertEquals(1, PhoneWheel.direction(items, cur, "10"))   // 看得到：在下面
        assertEquals(1, PhoneWheel.direction(items, cur, "23"))   // 看不到：比大小
        assertEquals(-1, PhoneWheel.direction(items, cur, "3"))
        assertTrue(PhoneWheel.matches("09", "9"))
        assertTrue(PhoneWheel.matches("9月", "9"))
        assertFalse(PhoneWheel.matches("9月", "9日"))
    }

    @Test fun chooserOnlyAllowsTickedAppsAndButtons() {
        assertTrue(PhoneWheel.isChooser("android", listOf("使用以下方式打开", "微信", "QQ")))
        assertFalse(PhoneWheel.isChooser("com.tencent.mm", listOf("仅此一次")))
        assertTrue(PhoneWheel.chooserTapOk("微信", listOf("微信")))
        assertTrue(PhoneWheel.chooserTapOk("仅此一次", emptyList()))
        assertFalse(PhoneWheel.chooserTapOk("支付宝", listOf("微信")))
    }
}
