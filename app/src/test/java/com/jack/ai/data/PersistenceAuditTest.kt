package com.jack.ai.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 写到一半被杀：AtomicFile 保住旧版本，用户的模板不会变成空。并发写记忆见 MemoryConcurrencyTest。 */
@RunWith(RobolectricTestRunner::class)
class PersistenceAuditTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun halfWrittenStyleTemplatesSilentlyLoadAsEmpty() {
        val file = File(app.filesDir, "style_templates.json")
        val template = StyleTemplates.Template("公司汇报", "ppt")
        StyleTemplates.save(app, listOf(template))
        val full = file.readText()

        // 模拟新版本只写进临时文件一半，App 就被杀：还没换成正式文件。
        File(file.parentFile, file.name + ".tmp").writeText(full.take(full.length / 2))

        val loaded = StyleTemplates.load(app)
        assertTrue("半写文件不应让用户已有模板静默变成空列表", loaded.any { it.name == "公司汇报" })
    }
}
