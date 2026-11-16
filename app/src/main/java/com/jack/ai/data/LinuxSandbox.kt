package com.jack.ai.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Linux 沙箱：一个 Alpine Linux（带 Python 3.12、Node 22、常用命令），用 PRoot 跑在 App 里，不用 root。
 * 对话文件夹挂在里面的 /workspace，命令只能碰到它和沙箱自己的系统。
 *
 * 系统包（约 58 MB）第一次用时下载：来自 Mika 仓库固定提交里的 rootfs.tar.xz，边下边算 SHA-256，对不上就删掉。
 * PRoot、载入器和启动器（libproot.so、libproot_loader.so、libmikarun.so）随 APK 带着。
 * 启动参数、/dev 只绑的那几个设备、/proc 替身文件都照 Mika 的做法（Apache-2.0），见 third-party/mika-sandbox。
 */
object LinuxSandbox {
    /** 系统包：Mika 65c65b0 里的 app/src/main/assets/runtime/rootfs.tar.xz（Alpine 3.22.7 + Python、Node 等）。 */
    const val VERSION = "3.22.7-250e9fbe32c8"
    const val URL = "https://raw.githubusercontent.com/wangzan101/Mika/65c65b0d3c205e77c2ffa9e084f40c6bb2a7316a/app/src/main/assets/runtime/rootfs.tar.xz"
    const val SHA256 = "250e9fbe32c8cfafb5aaf9864fe04fa3e5b2092e6524b2c611fb585c0521ba19"
    const val BYTES = 57_957_216L
    /** 解出来的普通文件总字节数和条目数（解包时的上限按它们定，多留一点余量）。 */
    private const val FILE_BYTES = 229_293_368L
    private const val ENTRIES = 6958

    const val OUTPUT_LIMIT = 256 * 1024
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val WORKSPACE = "/workspace"
    /** 启动器的退出码：超时 124，启动器自己出错 125。 */
    const val EXIT_TIMEOUT = 124
    const val EXIT_LAUNCHER = 125

    sealed interface Status {
        /** 这台手机用不了（不是 arm64，或者 APK 里没带沙箱程序）。 */
        data class Unsupported(val why: String) : Status
        data object NotInstalled : Status
        data class Installing(val step: String, val percent: Int) : Status
        data object Ready : Status
        data class Broken(val why: String) : Status
    }

    private val _status = MutableStateFlow<Status?>(null)
    /** 设置页看的状态；null 表示还没查过（调 refresh）。 */
    val status = _status.asStateFlow()
    private val lock = Mutex()

    private fun root(ctx: Context) = File(ctx.filesDir, "sandbox")
    fun rootfs(ctx: Context) = File(root(ctx), "rootfs")
    private fun marker(ctx: Context) = File(root(ctx), "installed")
    private fun download(ctx: Context) = File(root(ctx), "rootfs.tar.xz.part")
    private fun standIns(ctx: Context) = File(root(ctx), "proc")
    private fun tmp(ctx: Context) = File(ctx.cacheDir, "sandbox-tmp")
    fun lib(ctx: Context, name: String) = File(ctx.applicationInfo.nativeLibraryDir, name)

    fun supported(ctx: Context): String? = when {
        "arm64-v8a" !in Build.SUPPORTED_ABIS -> "这台手机不是 64 位 ARM，跑不了 Linux 沙箱"
        listOf("libmikarun.so", "libproot.so", "libproot_loader.so").any { !lib(ctx, it).isFile } -> "安装包里缺沙箱程序"
        else -> null
    }

    fun ready(ctx: Context): Boolean = supported(ctx) == null && runCatching { marker(ctx).readText().trim() == VERSION }.getOrDefault(false) &&
        File(rootfs(ctx), "bin/sh").let { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    fun refresh(ctx: Context): Status {
        if (_status.value is Status.Installing) return _status.value!!
        val s = supported(ctx)?.let { Status.Unsupported(it) } ?: if (ready(ctx)) Status.Ready
            else if (marker(ctx).exists()) Status.Broken("系统包不完整，请删掉重装") else Status.NotInstalled
        _status.value = s
        return s
    }

    /** 占了多少空间（系统包解开后约 230 MB）。 */
    fun usedBytes(ctx: Context): Long = runCatching {
        Files.walk(root(ctx).toPath()).use { s -> s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum() }
    }.getOrDefault(0L)

    /**
     * 安装：检查 → 下载（能接着上次没下完的继续）→ 核对 SHA-256 → 解包 → 配置。
     * 每一步都报进度；失败时状态是 Broken(原因) 并抛出。
     */
    suspend fun install(ctx: Context, http: okhttp3.OkHttpClient) = lock.withLock {
        withContext(Dispatchers.IO) {
            fun step(name: String, pct: Int) { _status.value = Status.Installing(name, pct) }
            try {
                supported(ctx)?.let { throw IOException(it) }
                val need = BYTES + FILE_BYTES + FILE_BYTES / 4
                val free = ctx.filesDir.usableSpace
                if (free < need) throw IOException("手机空间不够：要 ${need shr 20} MB，只剩 ${free shr 20} MB")
                step("检查沙箱程序", 1)
                selfTest(ctx)
                root(ctx).mkdirs()
                downloadVerified(ctx, http) { done -> step("下载系统包 ${done shr 20}/${BYTES shr 20} MB", 2 + (done * 70 / BYTES).toInt()) }
                step("解开系统包", 75)
                extract(ctx)
                step("配置", 95)
                configure(ctx)
                download(ctx).delete()
                marker(ctx).writeText(VERSION)
                _status.value = Status.Ready
            } catch (e: CancellationException) {
                _status.value = Status.Broken("安装被打断了，再点一次会接着下载")
                throw e
            } catch (e: Exception) {
                _status.value = Status.Broken(e.message ?: e.javaClass.simpleName)
                throw e
            }
        }
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** 在后台装（离开设置页也接着装）；已经在装就不重复开。 */
    fun startInstall(ctx: Context, http: okhttp3.OkHttpClient, onReady: () -> Unit = {}) {
        if (_status.value is Status.Installing) return
        val app = ctx.applicationContext
        _status.value = Status.Installing("准备", 0)
        // 失败时 install 已经把原因记成 Broken，别用 refresh 盖掉
        appScope.launch { runCatching { install(app, http) }.onSuccess { if (refresh(app) == Status.Ready) onReady() } }
    }

    /** 删掉整个沙箱（系统包、临时文件），对话文件夹不动。 */
    suspend fun remove(ctx: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            RootfsExtractor.deleteTree(root(ctx))
            RootfsExtractor.deleteTree(tmp(ctx))
            refresh(ctx)
        }
    }

    private fun selfTest(ctx: Context) {
        val p = ProcessBuilder(lib(ctx, "libmikarun.so").path, "--self-test").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IOException("沙箱程序没有响应") }
        if (p.exitValue() != 0 || out.lineSequence().firstOrNull()?.trim() != "mikarun ok") {
            throw IOException("沙箱程序在这台手机上起不来：" + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().ifEmpty { "退出码 ${p.exitValue()}" })
        }
    }

    /** 下载到 .part；已经下了一部分就用 Range 接着下。下完核对整个文件的 SHA-256，不对就删掉。 */
    private fun downloadVerified(ctx: Context, http: okhttp3.OkHttpClient, onProgress: (Long) -> Unit) {
        val part = download(ctx)
        if (part.length() > BYTES) part.delete()
        if (part.length() < BYTES) {
            val req = Request.Builder().url(URL).apply { if (part.length() > 0) header("Range", "bytes=${part.length()}-") }.build()
            http.newCall(req).execute().use { resp ->
                val append = resp.code == 206
                if (!resp.isSuccessful) throw IOException("下载系统包失败（${resp.code}），检查网络后再试")
                if (!append) part.delete()
                val body = resp.body ?: throw IOException("下载系统包失败：没有内容")
                java.io.FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = part.length()
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > BYTES) throw IOException("下载的系统包比预期大，已停止")
                            if (done - last > 512 * 1024) { last = done; onProgress(done) }
                        }
                    }
                }
            }
        }
        if (part.length() != BYTES || sha256(part) != SHA256) {
            part.delete()
            throw IOException("下载的系统包校验没通过（可能没下完或被改过），已删掉，请再试一次")
        }
    }

    internal fun sha256(f: File): String = f.inputStream().use { input ->
        val d = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        d.digest().joinToString("") { "%02x".format(it) }
    }

    /** 解到 rootfs.new，成功了再换上（旧的先挪开再删），中途失败把解了一半的删掉。 */
    private fun extract(ctx: Context) {
        val fresh = File(root(ctx), "rootfs.new")
        val old = File(root(ctx), "rootfs.old")
        RootfsExtractor.deleteTree(fresh)
        RootfsExtractor.deleteTree(old)
        try {
            XZInputStream(BufferedInputStream(download(ctx).inputStream(), 1 shl 16)).use { xz ->
                RootfsExtractor.extractTar(xz, fresh, maxBytes = FILE_BYTES + FILE_BYTES / 4 + (64L shl 20), maxEntries = ENTRIES + 1000)
            }
        } catch (e: Exception) {
            RootfsExtractor.deleteTree(fresh)
            throw IOException("解开系统包失败：${e.message}", e)
        }
        val dest = rootfs(ctx).toPath()
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) Files.move(dest, old.toPath(), StandardCopyOption.ATOMIC_MOVE)
        Files.move(fresh.toPath(), dest, StandardCopyOption.ATOMIC_MOVE)
        RootfsExtractor.deleteTree(old)
    }

    /** /dev 里指到 /proc 的几个链接（/dev 只绑下面几个字符设备，不绑整个 /dev：/dev/socket、binder 能绕过断网）。 */
    private val DEV_LINKS = linkedMapOf("fd" to "/proc/self/fd", "stdin" to "/proc/self/fd/0", "stdout" to "/proc/self/fd/1", "stderr" to "/proc/self/fd/2")
    private val DEVICES = listOf("/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/tty", "/dev/ptmx", "/dev/pts")

    private fun configure(ctx: Context) {
        val rootfs = rootfs(ctx)
        write(rootfs, "etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        writeDns(ctx)
        val dev = File(rootfs, "dev").toPath()
        Files.createDirectories(dev)
        for ((name, target) in DEV_LINKS) {
            val link = dev.resolve(name)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, java.nio.file.Paths.get(target))
        }
        // 安卓不让普通 App 读的 /proc 文件：生成格式对的静态替身，运行时绑过去（不然 top、ps、Python 的一些库会出错）
        val stand = standIns(ctx)
        RootfsExtractor.deleteTree(stand)
        stand.mkdirs()
        for ((name, content) in procStandIns()) {
            val readable = runCatching { File("/proc/$name").inputStream().use { it.read() }; true }.getOrDefault(false)
            if (!readable) File(stand, name).writeText(content)
        }
    }

    /** 写根文件系统里的一个文件：先删掉原来那一项（可能是链接），再写新的。 */
    private fun write(rootfs: File, rel: String, text: String) {
        val f = File(rootfs, rel)
        f.parentFile?.mkdirs()
        Files.deleteIfExists(f.toPath())
        f.writeText(text)
    }

    /** 每次跑命令前把手机当前网络的 DNS 写进去（换了网络也能解析域名）。 */
    private fun writeDns(ctx: Context) {
        val servers = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrNull().orEmpty().ifEmpty { listOf("223.5.5.5", "1.1.1.1") }
        write(rootfs(ctx), "etc/resolv.conf", servers.joinToString("") { "nameserver $it\n" })
    }

    private fun procStandIns(): Map<String, String> {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val bootSec = System.currentTimeMillis() / 1000
        return linkedMapOf(
            "stat" to buildString {
                append("cpu  0 0 0 0 0 0 0 0 0 0\n")
                for (i in 0 until cpus) append("cpu$i 0 0 0 0 0 0 0 0 0 0\n")
                append("intr 0\nctxt 0\nbtime $bootSec\nprocesses 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
            },
            "loadavg" to "0.00 0.00 0.00 1/1 1\n",
            "uptime" to "1.00 1.00\n",
            "version" to "Linux version ${System.getProperty("os.version") ?: "0"} (kavi@sandbox) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 0\nnr_inactive_anon 0\nnr_active_anon 0\nnr_inactive_file 0\nnr_active_file 0\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
        )
    }

    // ------------------------------------------------------------------ 跑命令

    /** 启动器 + PRoot 的命令行（照 Mika 的 ProcRunner）。 */
    internal fun argv(
        mikarun: String, proot: String, rootfs: String, workspace: String, standIns: List<Pair<String, String>>,
        timeoutSec: Int, network: Boolean, command: String, devices: List<String>,
    ): List<String> = buildList {
        add(mikarun)
        if (timeoutSec > 0) addAll(listOf("--timeout", timeoutSec.toString()))
        addAll(listOf("--net", if (network) "on" else "off"))
        add("--")
        add(proot)
        addAll(listOf("--kill-on-exit", "--link2symlink", "-0", "-r", rootfs))
        devices.forEach { addAll(listOf("-b", it)) }
        addAll(listOf("-b", "/proc", "-b", "/sys"))
        standIns.forEach { (file, name) -> addAll(listOf("-b", "$file:/proc/$name")) }
        addAll(listOf("-b", "$workspace:$WORKSPACE", "-w", WORKSPACE))
        addAll(listOf("/bin/sh", "-lc", command))
    }

    /** 环境变量先清空，只给这些。 */
    internal fun env(loader: String, tmp: String, tz: String): Map<String, String> = mapOf(
        "PROOT_LOADER" to loader, "PROOT_TMP_DIR" to tmp,
        "HOME" to "/root", "PATH" to PATH, "LANG" to "C.UTF-8", "TMPDIR" to "/tmp", "TZ" to tz,
        // pip 装的包留在对话文件夹外的系统里；别让它在手机上编译 C 扩展卡半天
        "PIP_DISABLE_PIP_VERSION_CHECK" to "1", "PIP_ROOT_USER_ACTION" to "ignore",
    )

    /**
     * 手机时区写成沙箱里的 TZ（照 Mika）：地区名原样给；固定偏移在 POSIX 写法里符号相反，整点的换成 Etc/GMT-8 这类名字。
     */
    internal fun timezone(zone: () -> ZoneId = ZoneId::systemDefault): String {
        val z = runCatching(zone).getOrNull() ?: return "UTC"
        val fixed = z as? ZoneOffset ?: z.id.takeIf { it.startsWith("GMT") || it.startsWith("UT") }?.let { z.normalized() as? ZoneOffset }
        val s = fixed?.totalSeconds ?: return z.id
        if (s == 0) return "UTC"
        if (s % 3600 == 0 && s / 3600 in -12..14) return "Etc/GMT" + (if (s > 0) "-" else "+") + abs(s / 3600)
        val a = abs(s)
        val hm = String.format(Locale.ROOT, "%02d%02d", a / 3600, a / 60 % 60)
        val posix = String.format(Locale.ROOT, "%02d:%02d", a / 3600, a / 60 % 60)
        return if (s > 0) "<+$hm>-$posix" else "<-$hm>$posix"
    }

    data class Result(val exitCode: Int, val output: String, val dropped: Long, val timedOut: Boolean)

    /**
     * 在沙箱里跑一条 shell 命令，工作目录是对话文件夹（/workspace）。超时由启动器管（整棵进程树一起杀）；
     * 协程被取消（用户按停止）时先礼貌结束，2 秒后还在就强杀。
     */
    suspend fun run(ctx: Context, workspace: File, command: String, timeoutSec: Int, network: Boolean = true): Result {
        if (!ready(ctx)) throw IOException("Linux 沙箱还没装好")
        workspace.mkdirs()
        tmp(ctx).mkdirs()
        writeDns(ctx)
        val stand = standIns(ctx).listFiles()?.sortedBy { it.name }?.map { it.path to it.name }.orEmpty()
        val cmd = argv(lib(ctx, "libmikarun.so").path, lib(ctx, "libproot.so").path, rootfs(ctx).path, workspace.canonicalPath, stand,
            timeoutSec, network, command, DEVICES.filter { File(it).exists() })
        val env = env(lib(ctx, "libproot_loader.so").path, tmp(ctx).path, timezone())
        val p = withContext(Dispatchers.IO + NonCancellable) {
            ProcessBuilder(cmd).redirectErrorStream(true).directory(workspace).apply { environment().clear(); environment().putAll(env) }.start()
        }
        // 读输出是阻塞的、取消打断不了：放到单独的协程里读，这边等它；被取消时先杀进程，流断了那边自然结束
        return kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.IO) {
                val (out, dropped) = collect(p.inputStream)
                // 启动器自己会在 timeout 秒时收场；这里多等一会儿兜底
                if (!p.waitFor(timeoutSec + 15L, TimeUnit.SECONDS)) p.destroyForcibly()
                val code = runCatching { p.exitValue() }.getOrDefault(EXIT_TIMEOUT)
                Result(code, String(out, Charsets.UTF_8), dropped, code == EXIT_TIMEOUT)
            }
            try {
                job.await()
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    p.destroy()
                    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
                }
                throw e
            }
        }
    }

    /** 读到结束；最多留 [limit] 字节，多出来的读掉丢弃并计数（不读完命令会卡在写输出上）。 */
    internal fun collect(input: InputStream, limit: Int = OUTPUT_LIMIT): Pair<ByteArray, Long> {
        val kept = java.io.ByteArrayOutputStream()
        var dropped = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val room = limit - kept.size()
            if (room > 0) kept.write(buf, 0, minOf(n, room))
            if (n > room) dropped += n - maxOf(room, 0)
        }
        return kept.toByteArray() to dropped
    }
}
