package com.jack.ai.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTeachTest {
    @Test
    fun recordsStepsAndCoalescesTyping() {
        var s = listOf<String>()
        s = PhoneTeach.append(s, PhoneTeach.opened("淘宝"))
        s = PhoneTeach.append(s, PhoneTeach.click("搜索")!!)
        s = PhoneTeach.append(s, PhoneTeach.click("搜索")!!)
        listOf("保", "保温", "保温杯").forEach { s = PhoneTeach.append(s, PhoneTeach.typed("搜索宝贝", it, false)!!) }
        s = PhoneTeach.append(s, PhoneTeach.typed("", "x", true)!!)
        assertEquals(listOf("打开「淘宝」", "点「搜索」", "在输入框「搜索宝贝」输入「保温杯」", "在密码框填密码（请用户自己填）"), s)
        assertNull(PhoneTeach.click("  "))
        assertTrue(PhoneTeach.request(s).contains("1. 打开「淘宝」"))
    }
}
