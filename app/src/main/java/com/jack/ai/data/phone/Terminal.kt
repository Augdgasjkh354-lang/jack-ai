package com.jack.ai.data.phone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * 用户终端：会话、输出缓冲和会话管理。
 * 移植自 Mika（https://github.com/wangzan101/Mika ，Apache-2.0，Copyright (c) 2026 Mika authors），
 * 提交 65c65b0d3c205e77c2ffa9e084f40c6bb2a7316a 的 terminal/TerminalSession.kt、TerminalHub.kt。
 * Jack AI modification：会话不按对话一一对应，可以开几个；不经过 Mika 的树锁和桥；Kavi 跑的命令也能记进终端里给用户看。
 */

/** 环形缓冲：留最近 [cap] 字节，界面重新打开时从这里把画面重建出来。 */
class TerminalRingBuffer(private val cap: Int) {
    private val buf = ByteArray(cap)
    private var start = 0
    private var size = 0

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size >= cap) {
            bytes.copyInto(buf, 0, bytes.size - cap)
            start = 0; size = cap
            return
        }
        val overflow = size + bytes.size - cap
        if (overflow > 0) { start = (start + overflow) % cap; size -= overflow }
        val end = (start + size) % cap
        val first = minOf(bytes.size, cap - end)
        bytes.copyInto(buf, end, 0, first)
        if (first < bytes.size) bytes.copyInto(buf, 0, first, bytes.size)
        size += bytes.size
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(size)
        val first = minOf(size, cap - start)
        buf.copyInto(out, 0, start, start + first)
        if (first < size) buf.copyInto(out, first, 0, size - first)
        return out
    }
}

/**
 * 一个终端：shell 进程（[shell] 为空时是只读的「Kavi 跑的命令」记录）、输出缓冲、输入。
 * 输出同时进环形缓冲（给重新打开的界面）和 [output]（给正在看的界面）。
 */
class TerminalSession internal constructor(
    val id: String,
    val title: String,
    /** 挂成 /workspace 的文件夹。 */
    val workspace: File,
    private val shell: LinuxSandbox.Shell?,
    scope: CoroutineScope,
) {
    enum class Phase { Running, Exited, Closed }

    private val _phase = MutableStateFlow(if (shell == null) Phase.Exited else Phase.Running)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val readOnly get() = shell == null

    private val ring = TerminalRingBuffer(1 shl 20)
    // 界面一时跟不上时读输出这边等着，不丢块（丢了屏上会缺一段，还可能切断转义序列或半个字）；没人在看时 emit 不等，只进缓冲
    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val output: SharedFlow<ByteArray> = _output

    private val shellClosed = AtomicBoolean()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "kavi-terminal-write").apply { isDaemon = true } }

    init {
        if (shell != null) scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16 * 1024)
            try {
                shell.output.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        val chunk = buf.copyOf(n)
                        ring.append(chunk)
                        _output.emit(chunk)
                    }
                }
            } catch (_: IOException) {
                // 关管道和进程退出、用户关掉会撞在一起，正常
            }
            shell.waitFor()
            _phase.update { if (it == Phase.Running) Phase.Exited else it }
            val bye = "\r\n\u001b[2m[已退出]\u001b[0m\r\n".toByteArray()
            ring.append(bye); _output.tryEmit(bye)
            release()
        }
    }

    /** 往缓冲和界面里写一段（只读的记录用）。 */
    internal fun echo(bytes: ByteArray) {
        ring.append(bytes)
        _output.tryEmit(bytes)
    }

    /**
     * 写进 shell 的字交给这个会话自己的线程按先后写，调用方不等：界面落后时沙箱那头不读输入，管道会写满，
     * 在主线程上直接写就卡住主线程，而收输出的也是主线程，两边互相等（照 Mika）。
     */
    fun writeBytes(bytes: ByteArray) {
        val sh = shell ?: return
        runCatching { writer.execute { runCatching { sh.input.write(bytes); sh.input.flush() } } }
    }

    fun resize(cols: Int, rows: Int) { shell?.resize(cols, rows) }

    fun snapshot(): ByteArray = ring.snapshot()

    fun close() {
        _phase.value = Phase.Closed
        release()
    }

    private fun release() {
        val sh = shell ?: return
        if (shellClosed.compareAndSet(false, true)) {
            writer.shutdownNow()
            kotlin.concurrent.thread(isDaemon = true, name = "kavi-terminal-close") { runCatching { sh.close() } }
        }
    }
}

/** 开着的终端，进程里一份。最多 [MAX] 个在跑；退出了的留着看输出，直到用户关掉。 */
object TerminalHub {
    const val MAX = 4
    /** Kavi 跑命令的记录那个只读终端的 id 前缀，后面跟文件夹名。 */
    private const val LOG = "kavi-log:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    suspend fun open(ctx: Context, title: String, workspace: File, cols: Int = 80, rows: Int = 24): TerminalSession {
        if (_sessions.value.count { it.phase.value == TerminalSession.Phase.Running } >= MAX)
            throw IOException("最多同时开 $MAX 个终端，先关掉一个")
        val shell = LinuxSandbox.openShell(ctx.applicationContext, workspace, cols, rows)
        val s = TerminalSession(com.jack.ai.data.newId(), title, workspace, shell, scope)
        _sessions.update { it + s }
        return s
    }

    fun close(id: String) {
        var gone: TerminalSession? = null
        _sessions.update { l -> gone = l.firstOrNull { it.id == id }; l.filterNot { it.id == id } }
        gone?.close()
    }

    /**
     * Kavi 在沙箱里跑了一条命令：记进这个文件夹的「Kavi」终端（只读），用户在终端页能看到它跑了什么、输出了什么。
     * 没开过终端页也记着（最多 1MB，旧的挤掉）。
     */
    fun logKavi(workspace: File, title: String, command: String, output: String, exitCode: Int) {
        val id = LOG + workspace.name
        val s = _sessions.value.firstOrNull { it.id == id } ?: TerminalSession(id, "Kavi · $title", workspace, null, scope).also { n -> _sessions.update { it + n } }
        val text = buildString {
            append("\u001b[36m$ \u001b[0m").append(command.replace("\n", "\r\n  ")).append("\r\n")
            append(output.replace("\r\n", "\n").replace("\n", "\r\n"))
            if (!output.endsWith("\n")) append("\r\n")
            if (exitCode != 0) append("\u001b[31m[退出码 $exitCode]\u001b[0m\r\n")
        }
        s.echo(text.toByteArray())
    }
}
