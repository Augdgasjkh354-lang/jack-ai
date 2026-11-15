package com.jack.ai.api

import com.jack.ai.data.Endpoint
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * 主模型自动换备用：主模型还没回一个字就报错（服务器忙、限流、没余额、Key 失效、网络连不上），
 * 按顺序换备用模型重发。换成功后「粘住」一阵：这段时间直接先用那个备用，省得每次都先撞一次主模型；
 * 过了这段时间再回去试主模型。已经开始回答再出错的，不换（换了会接不上）。
 *
 * 思路参考 Mika 的主模型备援链（Apache-2.0），Kavi 自己的实现。
 */
object Failover {
    /** 换到备用以后，多久内都先用备用。 */
    const val STICKY_MS = 10L * 60 * 1000

    private class Stuck(val key: String, val until: Long)
    private val stuck = java.util.concurrent.ConcurrentHashMap<String, Stuck>()

    @Volatile internal var now: () -> Long = System::currentTimeMillis

    /** 这次按什么顺序试：主模型 + 备用；最近换过的那个备用排最前。 */
    fun order(ep: Endpoint): List<Endpoint> {
        val all = listOf(ep.copy(fallbacks = emptyList())) + ep.fallbacks
        if (all.size == 1) return all
        val s = stuck[ep.key]?.takeIf { it.until > now() } ?: run { stuck.remove(ep.key); return all }
        val first = all.firstOrNull { it.key == s.key } ?: return all
        return listOf(first) + all.filter { it !== first }
    }

    /** 这次是 [used] 答上的：不是主模型就粘住它，是主模型就放开。 */
    fun succeeded(ep: Endpoint, used: Endpoint) {
        if (used.key == ep.key) stuck.remove(ep.key) else stuck[ep.key] = Stuck(used.key, now() + STICKY_MS)
    }

    /** 这种错换个模型可能就好了。请求本身有问题的（400、422，比如太长）换了也一样，不换。 */
    fun worthSwitching(e: Throwable): Boolean = when (e) {
        is CancellationException -> false
        is ApiException -> e.code == 0 || e.code in setOf(401, 402, 403, 404, 408, 409, 429) || e.code >= 500 ||
            e.serviceCode.isNotEmpty()
        is IOException -> true
        else -> false
    }

    internal fun reset() = stuck.clear()
}
