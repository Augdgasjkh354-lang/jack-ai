package com.jack.ai.data.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台运行：定时任务、后台操作手机、远程连接都要 Kavi 在后台活着。
 * 两道关：一是系统的省电限制（原生安卓都有，能直接弹窗申请放行）；
 * 二是国产手机自己的「自启动」「后台管理」，系统接口管不着，只能把人带到那一页自己打开。
 * 思路参考 DSH Go（只借思路，代码自己写）。
 */
object BackgroundRun {

    /** 手机品牌：决定带去哪一页、怎么说。 */
    enum class Vendor(val label: String, val steps: String) {
        XIAOMI("小米 / 红米", "找到 Kavi，打开「自启动」；再到「省电策略」选「无限制」。"),
        HUAWEI("华为", "在「应用启动管理」里找到 Kavi，关掉「自动管理」，在弹窗里把「允许自启动」「允许后台活动」都打开。"),
        HONOR("荣耀", "在「应用启动管理」里找到 Kavi，关掉「自动管理」，在弹窗里把「允许自启动」「允许后台活动」都打开。"),
        OPPO("OPPO / 一加 / 真我", "找到 Kavi，打开「允许自启动」；再到「耗电管理」里打开「允许后台运行」。"),
        VIVO("vivo / iQOO", "在「后台高耗电」或「自启动」里找到 Kavi，打开开关。"),
        SAMSUNG("三星", "在「后台使用限制」里确认 Kavi 不在「休眠应用」「深度休眠应用」里。"),
        OTHER("这台手机", "找到「电池」或「后台」相关的设置，允许 Kavi 在后台运行。"),
    }

    fun vendor(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Vendor {
        val m = "$manufacturer $brand".lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco").any { it in m } -> Vendor.XIAOMI
            "honor" in m -> Vendor.HONOR
            "huawei" in m -> Vendor.HUAWEI
            listOf("oppo", "oneplus", "realme").any { it in m } -> Vendor.OPPO
            listOf("vivo", "iqoo").any { it in m } -> Vendor.VIVO
            "samsung" in m -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    /** 各家「自启动 / 后台管理」那一页，按顺序试，系统版本不同类名不同；都打不开就退到 App 详情页。 */
    fun vendorPages(v: Vendor): List<ComponentName> = when (v) {
        Vendor.XIAOMI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Vendor.HUAWEI -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Vendor.HONOR -> listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        Vendor.OPPO -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        Vendor.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Vendor.SAMSUNG -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Vendor.OTHER -> emptyList()
    }

    /** 系统省电限制放行了没有。 */
    fun unrestricted(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(true)

    /** 申请放行省电限制：先弹系统的「允许在后台运行」窗口，弹不出来就打开省电设置的列表。 */
    fun askUnrestricted(ctx: Context) {
        // 正式版清单里删了这个权限：没有权限时系统窗口一闪就关，直接走省电设置列表
        val canAsk = ctx.packageManager.checkPermission(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ctx.packageName) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        if (canAsk && start(ctx, ask)) return
        if (start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetails(ctx)
    }

    /** 打开这台手机的自启动 / 后台管理页；返回 false 表示没找到专门的那页，退到了 App 详情页。 */
    fun openVendorPage(ctx: Context): Boolean {
        for (c in vendorPages(vendor())) {
            if (start(ctx, Intent().setComponent(c))) return true
        }
        openAppDetails(ctx)
        return false
    }

    private fun openAppDetails(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
    }

    /** 打开页面；厂商页没导出、或者这个系统版本没有时会抛异常，吃掉当作打不开。 */
    private fun start(ctx: Context, i: Intent): Boolean = runCatching {
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)
}
