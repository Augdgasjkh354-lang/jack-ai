package com.jack.ai.a11y

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.Store
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundPhoneTest {
    private fun window(pkg: String): AccessibilityWindowInfo {
        val node = AccessibilityNodeInfo.obtain().apply { packageName = pkg }
        return AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(it).setRoot(node)
        }
    }

    @Test fun readsOnlyTheRequestedDisplay() {
        val service = Robolectric.buildService(KaviAccessibility::class.java).create().get()
        val shadow = shadowOf(service)
        shadow.setWindowsOnDisplay(0, listOf(window("main.app")))
        shadow.setWindowsOnDisplay(7, listOf(window("job.app")))
        shadow.setWindowsOnDisplay(8, listOf(window("other.app")))
        assertEquals("job.app", service.foreground(7))
        assertEquals("other.app", service.foreground(8))
        assertNull(service.foreground(9))
        assertNull(service.snapshot(9))
    }

    @Test fun gesturesKeepTheirDisplayAndMainScreenRemainsDefault() = runBlocking {
        val service = Robolectric.buildService(KaviAccessibility::class.java).create().get()
        val shadow = shadowOf(service)
        val background = async(start = CoroutineStart.UNDISPATCHED) { service.tap(100, 200, displayId = 7) }
        val first = shadow.gesturesDispatched.last()
        assertEquals(7, first.description().displayId)
        first.callback().onCompleted(first.description())
        assertTrue(background.await())
        val main = async(start = CoroutineStart.UNDISPATCHED) { service.tap(100, 200) }
        val second = shadow.gesturesDispatched.last()
        assertEquals(0, second.description().displayId)
        second.callback().onCompleted(second.description())
        assertTrue(main.await())
        assertTrue(shadow.globalActionsPerformed.isEmpty())
    }

    @Test fun backgroundModeIsOptInAndSurvivesSettingsReload() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val store = Store(ctx)
        assertFalse(store.loadSettings().phoneBackground)
        store.saveSettings(store.loadSettings().copy(phoneBackground = true))
        assertTrue(Store(ctx).loadSettings().phoneBackground)
        store.saveSettings(store.loadSettings().copy(phoneBackground = false))
        assertFalse(Store(ctx).loadSettings().phoneBackground)
    }
}
