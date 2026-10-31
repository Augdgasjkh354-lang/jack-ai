package com.jack.ai.tools

import com.jack.ai.data.PhoneData
import com.jack.ai.data.SearchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 手机数据（phone_data）：notifications 查通知、contacts 查联系人、location 大概在哪、usage 各 App 用了多久。每样要用户在设置里开了才行。 */
internal suspend fun ToolSession.phoneData(args: JsonObject): String {
    val action = args["action"].text().trim()
    fun off(what: String) = "「$what」没开。需要的话请用户到 设置 → 手机数据 打开。"
    val label = "手机数据：" + when (action) {
        "notifications" -> "看通知" + args["query"].text().takeIf { it.isNotBlank() }?.let { "「${it.take(20)}」" }.orEmpty()
        "contacts" -> "找联系人「${args["query"].text().take(20)}」"
        "location" -> "看在哪"
        "usage" -> "看使用时长"
        else -> action
    }
    return runTool(label) {
        val text = withContext(Dispatchers.IO) {
            when (action) {
                "notifications" -> if (!settings.noticeWatch) off("通知") else {
                    val hours = args["hours"].text().toIntOrNull()?.coerceIn(1, 72) ?: 24
                    val hits = PhoneData.search(PhoneData.notices(app), args["query"].text(), args["app"].text(), hours)
                    if (hits.isEmpty()) "最近 $hours 小时没有对得上的通知（只从打开「通知」之后开始记，最多三天；验证码已遮掉）。"
                    else "最近 $hours 小时的通知（新的在前，共 ${hits.size} 条，验证码已遮掉）：\n" + PhoneData.format(hits)
                }
                "contacts" -> when {
                    !settings.contactsOn -> off("联系人")
                    !PhoneData.canContacts(app) -> "没有读通讯录的权限。请用户到 设置 → 手机数据 把「联系人」关掉再打开，按提示允许。"
                    else -> PhoneData.contacts(app, args["query"].text())
                }
                "location" -> when {
                    !settings.locationOn -> off("位置")
                    !PhoneData.canLocate(app) -> "没有定位权限。请用户到 设置 → 手机数据 把「位置」关掉再打开，按提示允许。"
                    else -> PhoneData.location(app)
                }
                "usage" -> when {
                    !settings.usageOn -> off("使用时长")
                    !PhoneData.canUsage(app) -> "没有「使用情况访问权限」。请用户到 设置 → 手机数据 →「使用时长」点「去授权」。"
                    else -> PhoneData.usage(app, args["days"].text().toIntOrNull()?.coerceIn(1, 7) ?: 1)
                }
                else -> "action 要填 notifications、contacts、location 或 usage。"
            }
        }
        text to SearchRecord(label)
    }
}
