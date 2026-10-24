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
}
