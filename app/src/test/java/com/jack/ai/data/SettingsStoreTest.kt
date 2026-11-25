package com.jack.ai.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 设置是一项一项手写存取的：新加的字段忘了写，改完当场有效、App 一重开就丢（底栏、字体就出过这事）。 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun tabsFontAndOnceLostTogglesSurviveARestart() {
        val store = Store(app)
        store.saveSettings(store.loadSettings().copy(
            tabBar = listOf("memory", "files"), fontChoice = "serif",
            bizStarts = false, linuxSandbox = true, quietStart = 22, quietEnd = 7, dsVoice = true, dsVoiceId = "alex",
        ))
        val back = Store(app).loadSettings()
        assertEquals(listOf("memory", "files"), back.tabBar)
        assertEquals("serif", back.fontChoice)
        assertEquals(false, back.bizStarts)
        assertEquals(true, back.linuxSandbox)
        assertEquals(22, back.quietStart)
        assertEquals(7, back.quietEnd)
        assertEquals(true, back.dsVoice)
        assertEquals("alex", back.dsVoiceId)
    }

    @Test
    fun emptyTabBarIsKeptAndNullMeansDefault() {
        val store = Store(app)
        store.saveSettings(store.loadSettings().copy(tabBar = emptyList()))
        assertEquals(emptyList<String>(), Store(app).loadSettings().tabBar)
        store.saveSettings(store.loadSettings().copy(tabBar = null))
        assertNull(Store(app).loadSettings().tabBar)
    }
}

/** 不用 Robolectric：只读源码和反射，跑得快，也不受模拟器启动的影响。 */
class SettingsFieldsStoredTest {
    /** 每个设置字段都要在 Store 里读、写各出现一次；故意不存的列在这里。 */
    @Test
    fun everySettingsFieldIsSavedAndLoaded() {
        val notStored = setOf("developerPrompt", "workDeveloperPrompt") // 底层提示词固定用内置的
        val src = File(System.getProperty("user.dir"), "src/main/java/com/jack/ai/data/Store.kt").readText()
        val fields = Settings::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
        val missing = fields.filter { it !in notStored && !(Regex("""\b$it\s*=""").containsMatchIn(src) && Regex("""\bs\.$it\b""").containsMatchIn(src)) }
        assertTrue("这些设置没写进 Store 的存取：$missing", missing.isEmpty())
    }
}
