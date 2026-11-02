// Copyright 2026 wangzan101. Licensed under Apache-2.0.
// Ported from Mika; Jack AI adaptations documented in third-party/mika/README.md.
package com.wangzan101.mika.shell

import com.wangzan101.mika.cap.phone.shell.HelperProcess

/** 内置助手进程的入口:启动命令里写的是这个全名(见 HelperHandoff.HELPER_CLASS);实现在手机能力里。 */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) = HelperProcess.main(args)
}
