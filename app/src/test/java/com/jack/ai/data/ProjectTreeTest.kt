package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 项目文件夹的文件树、新建改名、模板。 */
class ProjectTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun listsFoldersFirstAndOnlyOpenOnes() {
        val root = tmp.newFolder("p")
        ProjectTree.Template.WEB.apply(root)
        ProjectTree.create(root, "src", folder = true)!!
        ProjectTree.create(root.resolve("src"), "app.js", folder = false)!!
        root.resolve(".versions").mkdirs()
        val closed = ProjectTree.list(root, emptySet()).map { it.rel }
        assertEquals(listOf("src", "index.html", "script.js", "style.css"), closed)
        val opened = ProjectTree.list(root, setOf("src"))
        assertEquals("src/app.js", opened[1].rel)
        assertEquals(1, opened[1].depth)
        assertEquals(root.resolve("index.html"), ProjectTree.webEntry(root))
    }

    @Test fun namesAreChecked() {
        val root = tmp.newFolder("q")
        assertFalse(ProjectTree.okName("../x"))
        assertFalse(ProjectTree.okName(".env"))
        assertFalse(ProjectTree.okName(" "))
        val f = ProjectTree.create(root, "a.py", false)!!
        assertNull(ProjectTree.create(root, "a.py", false))
        val g = ProjectTree.rename(f, "b.py")!!
        assertTrue(g.isFile && !f.exists())
        // 模板不覆盖已有的
        root.resolve("main.py").writeText("mine")
        assertEquals(2, ProjectTree.Template.PYTHON.apply(root))
        assertEquals("mine", root.resolve("main.py").readText())
    }
}
