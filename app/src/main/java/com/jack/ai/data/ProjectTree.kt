package com.jack.ai.data

import java.io.File

/**
 * 项目文件夹（Workspace 里的 p_<项目 id>）的文件树和起步模板，给项目页的「文件」用。
 * 点开的文件夹才往下列；藏起来的（. 开头的，比如旧版本 .versions、.git）不列。
 */
object ProjectTree {
    data class Entry(val file: File, val depth: Int, val rel: String) {
        val dir get() = file.isDirectory
    }

    /** 文件夹在前、按名字排；[open] 里的（相对路径）才展开。最多列 [max] 项，免得 node_modules 这类把界面撑爆。 */
    fun list(root: File, open: Set<String>, max: Int = 2000): List<Entry> {
        val out = ArrayList<Entry>()
        fun walk(dir: File, depth: Int, prefix: String) {
            val kids = dir.listFiles()?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
            for (f in kids) {
                if (out.size >= max) return
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                out += Entry(f, depth, rel)
                if (f.isDirectory && rel in open) walk(f, depth + 1, rel)
            }
        }
        walk(root, 0, "")
        return out
    }

    /** 名字能不能用：不能空、不能带斜杠这类、不能以点开头、不能跳出文件夹。 */
    fun okName(name: String): Boolean {
        val n = name.trim()
        return n.isNotEmpty() && n.length <= 80 && !n.startsWith(".") && n.none { it in "\\/:*?\"<>|\n\r\t" }
    }

    /** 新建文件或文件夹（[parent] 里），重名返回 null。 */
    fun create(parent: File, name: String, folder: Boolean): File? {
        if (!okName(name)) return null
        val f = File(parent, name.trim())
        if (f.exists()) return null
        parent.mkdirs()
        return if (folder) f.takeIf { it.mkdirs() } else f.takeIf { runCatching { it.createNewFile() }.getOrDefault(false) }
    }

    fun rename(f: File, name: String): File? {
        if (!okName(name)) return null
        val to = File(f.parentFile, name.trim())
        if (to.exists()) return null
        return to.takeIf { f.renameTo(it) }
    }

    /** 网页项目的入口：index.html（顶层优先，其次 public/、src/ 里的）。 */
    fun webEntry(root: File): File? = listOf("index.html", "public/index.html", "src/index.html", "dist/index.html")
        .map { File(root, it) }.firstOrNull { it.isFile }

    enum class Template(val label: String, val desc: String, val files: Map<String, String>) {
        WEB("网页", "index.html + style.css + script.js，能直接预览", mapOf(
            "index.html" to """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>我的网页</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main>
    <h1>你好</h1>
    <p>改一改 index.html、style.css、script.js，点「预览」看效果。</p>
    <button id="btn">点我</button>
  </main>
  <script src="script.js"></script>
</body>
</html>
""",
            "style.css" to """body { font-family: system-ui, sans-serif; margin: 0; padding: 24px; background: #f7f7f8; color: #1d1d1f; }
main { max-width: 640px; margin: 0 auto; }
button { padding: 10px 18px; border: 0; border-radius: 10px; background: #1d1d1f; color: #fff; font-size: 16px; }
""",
            "script.js" to """document.getElementById('btn').addEventListener('click', () => {
  alert('能用了！');
});
""",
        )),
        PYTHON("Python", "main.py + requirements.txt，在终端里 python main.py", mapOf(
            "main.py" to """def main():
    print("你好，Python")


if __name__ == "__main__":
    main()
""",
            "requirements.txt" to "",
            "README.md" to "# Python 项目\n\n- 运行：`python main.py`\n- 装库：写进 requirements.txt，再 `pip install -r requirements.txt`\n",
        )),
        NODE("Node.js", "package.json + index.js，在终端里 npm start", mapOf(
            "package.json" to """{
  "name": "my-app",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": { "start": "node index.js" }
}
""",
            "index.js" to "console.log('你好，Node');\n",
            "README.md" to "# Node.js 项目\n\n- 运行：`npm start`\n- 装库：`npm install <包名>`\n",
        ));

        /** 铺进文件夹；已经有的同名文件不覆盖。返回写了几个。 */
        fun apply(root: File): Int {
            root.mkdirs()
            var n = 0
            files.forEach { (name, text) ->
                val f = File(root, name)
                if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(text); n++ }
            }
            return n
        }
    }
}
