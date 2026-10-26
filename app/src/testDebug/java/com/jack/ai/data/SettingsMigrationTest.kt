package com.jack.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsMigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun proToFlashAndMonoStickAfterRestart() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("model", MODEL_PRO).putString("workModel", MODEL_PRO).putString("accent", "blue").commit()
        val first = Store(ctx).loadSettings()
        assertEquals(MODEL_FLASH, first.model)
        assertEquals("mono", first.accent)
        // 没改任何设置、再启动一次：还是换过之后的
        val again = Store(ctx).loadSettings()
        assertEquals(MODEL_FLASH, again.model)
        assertEquals(MODEL_FLASH, again.workModel)
        assertEquals("mono", again.accent)
        // 之后用户自己选回 Pro：不再动它
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("model", MODEL_PRO).commit()
        assertEquals(MODEL_PRO, Store(ctx).loadSettings().model)
    }

    @Test
    fun phoneFoldersSurviveRestart() {
        val s = Store(ctx).loadSettings().copy(phoneFolders = listOf(PhoneFolder("content://tree/a", "Download"), PhoneFolder("content://tree/b", "工作")))
        Store(ctx).saveSettings(s)
        assertEquals(s.phoneFolders, Store(ctx).loadSettings().phoneFolders)
    }

    @Test
    fun compressThresholdIsKeptWithinRange() {
        // 以前误拖到 50k 存下的旧键不再读
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("compressTokens", 50_000).commit()
        assertEquals(COMPRESS_MIN, Store(ctx).loadSettings().compressTokens)
        Store(ctx).saveSettings(Store(ctx).loadSettings().copy(compressTokens = 600_000))
        assertEquals(600_000, Store(ctx).loadSettings().compressTokens)
        Store(ctx).saveSettings(Store(ctx).loadSettings().copy(compressTokens = 5_000_000))
        assertEquals(COMPRESS_MAX, Store(ctx).loadSettings().compressTokens)
    }
}
