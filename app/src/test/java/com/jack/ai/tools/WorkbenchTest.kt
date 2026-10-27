package com.jack.ai.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.Conversation
import com.jack.ai.data.FreeBudget
import com.jack.ai.data.Reader
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WorkbenchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val ui = object : ToolUi {
        override fun started(label: String) {}
        override fun finished(label: String, record: SearchRecord) {}
        override fun attach(file: File) {}
        override suspend fun confirm(title: String, body: String, action: String) = false
    }

    @Test fun onlyFreeTimeMayDeleteItsOwnFilesWithoutConfirmation() = runBlocking {
        val host = BackgroundToolHost(app)
        fun session(id: String) = ToolSession(host, Settings(), Conversation(id = id), null, emptyList(), ui)
        fun args(name: String) = buildJsonObject { put("names", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(name)) }) }
        val free = session(Reader.CONV_ID).apply { freeBudget = FreeBudget(1.0, 0.0, 0.0) { "" } }
        free.wsDir.mkdirs()
        val own = File(free.wsDir, "自己的笔记.txt").apply { writeText("想法") }
        assertTrue(Reader.workbench(app).any { it.name == own.name })
        free.deleteFiles(args(own.name))
        assertFalse(own.exists())
        val other = session("other-workbench-test").apply { freeBudget = FreeBudget(1.0, 0.0, 0.0) { "" } }
        other.wsDir.mkdirs()
        val userFile = File(other.wsDir, "用户文件.txt").apply { writeText("内容") }
        other.deleteFiles(args(userFile.name))
        assertTrue(userFile.exists())
        userFile.delete()
        Unit
    }
}
