package com.wangzan101.mika.cap.phone

import android.content.Context
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent

/** Jack AI 的宿主适配：Mika 的助手交接和配对引擎共用这一份实例。 */
class PhoneServices private constructor(context: Context) {
    val hub = HelperHub(context)
    // 不额外修改系统授权；这里只接入无线调试和后台屏幕。
    val shellManager = BuiltinShellManager(context, hub) {
        RecoveryConsent(declined = RecoveryCapability.entries.toSet())
    }

    companion object {
        @Volatile private var instance: PhoneServices? = null
        fun of(context: Context): PhoneServices = instance ?: synchronized(this) {
            instance ?: PhoneServices(context.applicationContext).also { instance = it }
        }
    }
}
