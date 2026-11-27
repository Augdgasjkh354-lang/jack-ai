package com.jack.ai.data.phone

import com.jack.ai.data.phone.BackgroundRun.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundRunTest {
    @Test fun mapsMakersAndSubBrands() {
        assertEquals(Vendor.XIAOMI, BackgroundRun.vendor("Xiaomi", "Redmi"))
        assertEquals(Vendor.XIAOMI, BackgroundRun.vendor("Xiaomi", "POCO"))
        assertEquals(Vendor.HONOR, BackgroundRun.vendor("HONOR", "HONOR"))
        // 华为时代的荣耀：厂商写华为、品牌写荣耀，按荣耀走（会再试华为的页面）
        assertEquals(Vendor.HONOR, BackgroundRun.vendor("HUAWEI", "HONOR"))
        assertEquals(Vendor.HUAWEI, BackgroundRun.vendor("HUAWEI", "HUAWEI"))
        assertEquals(Vendor.OPPO, BackgroundRun.vendor("OnePlus", "OnePlus"))
        assertEquals(Vendor.OPPO, BackgroundRun.vendor("realme", "realme"))
        assertEquals(Vendor.VIVO, BackgroundRun.vendor("vivo", "iQOO"))
        assertEquals(Vendor.SAMSUNG, BackgroundRun.vendor("samsung", "samsung"))
        assertEquals(Vendor.OTHER, BackgroundRun.vendor("Google", "google"))
    }

    @Test fun everyNamedVendorHasAPage() {
        Vendor.entries.filter { it != Vendor.OTHER }.forEach { assertTrue(it.name, BackgroundRun.vendorPages(it).isNotEmpty()) }
        assertTrue(BackgroundRun.vendorPages(Vendor.HONOR).any { it.packageName == "com.huawei.systemmanager" })
    }
}
