package com.jack.ai

/** 按 Android debuggerd 的 tombstone.proto 读取崩溃线程，避免把其他线程的库名当成堆栈。 */
internal object NativeTombstone {
    fun frames(bytes: ByteArray): List<String> = runCatching {
        var tid = 0L
        var signalName = ""
        var codeName = ""
        fields(bytes) { n, number, data ->
            when (n) {
                6 -> tid = number ?: 0L
                10 -> if (data != null) fields(data) { k, _, v ->
                    when (k) {
                        2 -> signalName = v?.toString(Charsets.UTF_8).orEmpty()
                        4 -> codeName = v?.toString(Charsets.UTF_8).orEmpty()
                    }
                }
            }
        }
        var threadName = ""
        val backtrace = ArrayList<String>()
        fields(bytes) { n, _, entry ->
            if (n != 16 || entry == null) return@fields
            var key = -1L
            var thread: ByteArray? = null
            fields(entry) { k, v, data ->
                when (k) {
                    1 -> key = v ?: -1L
                    2 -> thread = data
                }
            }
            if (key != tid || thread == null) return@fields
            backtrace.clear()
            fields(thread!!) { k, _, v ->
                when (k) {
                    2 -> threadName = v?.toString(Charsets.UTF_8).orEmpty().take(80)
                    4 -> if (v != null && backtrace.size < 20) backtrace += frame(v, backtrace.size)
                }
            }
        }
        buildList {
            if (signalName.isNotBlank()) add(signalName + if (codeName.isBlank()) "" else " ($codeName)")
            if (tid != 0L) add("崩溃线程 tid=$tid" + if (threadName.isBlank()) "" else " ($threadName)")
            addAll(backtrace)
        }
    }.getOrDefault(emptyList())

    private fun frame(bytes: ByteArray, index: Int): String {
        var pc = 0L
        var file = ""
        var function = ""
        var offset = 0L
        fields(bytes) { n, value, data ->
            when (n) {
                1 -> pc = value ?: 0L
                4 -> function = data?.toString(Charsets.UTF_8).orEmpty().take(120)
                5 -> offset = value ?: 0L
                6 -> file = data?.toString(Charsets.UTF_8).orEmpty().substringAfterLast('/').take(120)
            }
        }
        val symbol = if (function.isBlank()) "" else " ($function" + if (offset == 0L) ")" else "+0x${offset.toString(16)})"
        return "#${index.toString().padStart(2, '0')} pc ${pc.toString(16).padStart(8, '0')} ${file.ifBlank { "<unknown>" }}$symbol"
    }

    /** 只读必要的 protobuf 字段；其余 wire type 直接跳过，不引入整套 protobuf 依赖。 */
    private fun fields(bytes: ByteArray, onField: (Int, Long?, ByteArray?) -> Unit) {
        var pos = 0
        fun varint(): Long {
            var out = 0L
            for (i in 0 until 10) {
                require(pos < bytes.size) { "tombstone 截断" }
                val b = bytes[pos++].toInt() and 0xff
                if (i == 9) require(b <= 1) { "tombstone varint 错误" }
                out = out or ((b and 0x7f).toLong() shl (7 * i))
                if (b and 0x80 == 0) return out
            }
            error("tombstone varint 过长")
        }
        while (pos < bytes.size) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            require(number > 0) { "tombstone 字段错误" }
            when ((tag and 7).toInt()) {
                0 -> onField(number, varint(), null)
                1, 5 -> {
                    val size = if (tag and 7L == 1L) 8 else 4
                    require(size <= bytes.size - pos) { "tombstone 截断" }
                    pos += size
                }
                2 -> {
                    val len = varint()
                    require(len >= 0 && len <= (bytes.size - pos).toLong()) { "tombstone 截断" }
                    val end = pos + len.toInt()
                    onField(number, null, bytes.copyOfRange(pos, end))
                    pos = end
                }
                else -> error("tombstone wire type 错误")
            }
        }
    }
}
