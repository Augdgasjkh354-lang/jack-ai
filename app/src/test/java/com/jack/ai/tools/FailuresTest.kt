package com.jack.ai.tools

import com.jack.ai.data.Settings
import com.jack.ai.tools.Failures.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 失败归类：按代码能确定的信号分，判不准不加；同一类再出现说得更重；工具自己写了下一步的第一次不叠。 */
class FailuresTest {
    private val ok = ToolStatus.OK
    private val failed = ToolStatus.FAILED

    @Test
    fun softFailuresOnSuccessfulCalls() {
        assertEquals(Kind.EMPTY_SEARCH, Failures.classify("web_search", "没有搜到相关结果。", ok))
        assertEquals(Kind.UNREADABLE_PAGE, Failures.classify("read_webpage", "没能读取这个网页的内容。", ok))
        assertEquals(Kind.LOGIN, Failures.classify("browser", "⚠ 这一页是 taobao.com 的登录页，可能还没登录", ok))
        // 正常的结果不算
        assertNull(Failures.classify("web_search", "[1] 工业风扇报价\n链接：https://a.com\n" + "正文".repeat(50), ok))
        assertNull(Failures.classify("read_file", "第一行", ok))
    }

    @Test
    fun failedCallsByCause() {
        assertEquals(Kind.RATE_LIMIT, Failures.classify("web_search", "工具调用失败：HTTP 429 Too Many Requests", failed))
        assertEquals(Kind.BLOCKED, Failures.classify("read_webpage", "工具调用失败：HTTP 403 Forbidden", failed))
        assertEquals(Kind.NETWORK, Failures.classify("download_file", "工具调用失败：网络连接超时。下一步：……", failed))
        assertEquals(Kind.PERMISSION, Failures.classify("list_calendar_events", "工具调用失败：没有权限（日历）", failed))
        assertEquals(Kind.NOT_FOUND, Failures.classify("read_file", "工具调用失败：找不到文件「报价.xlsx」", failed))
        assertEquals(Kind.DECLINED, Failures.classify("send_email", "用户没有同意，这一步没做。", ToolStatus.REJECTED))
        assertNull(Failures.classify("run_python", "工具调用失败：出错了（ZeroDivisionError）", failed))
    }

    @Test
    fun alreadyHandledMessagesAreLeftAlone() {
        assertNull(Failures.classify("web_search", "你已经用完全相同的参数调用 web_search 3 次了", failed))
        assertNull(Failures.classify("create_excel", "已生成\n【核实失败】公式引用了不存在的表", failed))
    }

    @Test
    fun repeatsAreStrongerAndExistingNextStepsArentDoubled() {
        val s = Settings(userName = "Jack")
        val first = Failures.note(Kind.EMPTY_SEARCH, 1, "没有搜到相关结果。", s)
        val second = Failures.note(Kind.EMPTY_SEARCH, 2, "没有搜到相关结果。", s)
        assertTrue(first.contains("Rephrase"))
        assertTrue(second.contains("seen 2 times") && second.contains("Change approach"))
        // 网络错误本身带了「下一步」：第一次不叠，第二次提醒停下
        assertEquals("", Failures.note(Kind.NETWORK, 1, "网络连接超时。下一步：检查代理", s))
        assertTrue(Failures.note(Kind.NETWORK, 2, "网络连接超时。下一步：检查代理", s).contains("Stop making network calls"))
        // 名字填进去了
        assertTrue(Failures.note(Kind.DECLINED, 1, "", s).contains("Jack declined"))
        assertFalse(Failures.note(Kind.DECLINED, 1, "", s).contains("{user}"))
    }
}
