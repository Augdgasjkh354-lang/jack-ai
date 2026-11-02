package com.jack.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RecordRecoverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun skipsOldAndDeliveredRecordingsAndClearsBrokenOnes() {
        val staging = File(app.filesDir, "staging")
        // 老版本录的（没有 name.txt）：那时已经交出去了，不动
        val legacy = File(staging, "rec-1").apply { mkdirs(); File(this, "录音 1月1日 10.00.m4a").writeText("x") }
        // 已经拼好交出去的
        val delivered = File(staging, "rec-2").apply { mkdirs(); File(this, "name.txt").writeText("录音 a.m4a"); File(this, ".done").createNewFile() }
        // 被杀时只录了没收尾的一段：打不开，清掉
        val broken = File(staging, "rec-3").apply { mkdirs(); File(this, "name.txt").writeText("录音 b.m4a"); File(this, "part-001.m4a").writeText("坏的") }
        assertTrue(RecordService.recover(app).isEmpty())
        assertTrue(legacy.exists() && delivered.exists())
        assertFalse(broken.exists())
    }
}
