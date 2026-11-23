package com.jack.ai.data.integrations

/**
 * GPT 按 OpenAI 官方 API 标准价（美元 / 百万 token）算一次请求值多少钱，给统计页看 ChatGPT 套餐划不划算。
 * 来源：developers.openai.com/api/docs/pricing（2026-11-23 查的 Standard 档）。输入超过 272K 按长上下文价。
 * 思考的 token 算在输出里（API 也是这么收的）；缓存写入费不算（自动缓存不收）。
 */
object GptApiPrice {
    /** 短上下文的输入、命中缓存的输入、输出；长上下文的三个（没有就和短的一样）。 */
    data class Rate(val input: Double, val cached: Double, val output: Double,
                    val longInput: Double = input, val longCached: Double = cached, val longOutput: Double = output)

    const val LONG_CONTEXT = 272_000

    private val RATES = mapOf(
        "gpt-6.2-astra" to Rate(8.0, 0.40, 40.0, 16.0, 0.80, 60.0),
        "gpt-6.2-sol" to Rate(2.0, 0.10, 10.0, 4.0, 0.20, 15.0),
        "gpt-6-astra" to Rate(10.0, 1.0, 50.0, 20.0, 2.0, 75.0),
        "gpt-6.1-sol" to Rate(2.0, 0.10, 10.0, 4.0, 0.20, 15.0),
        "gpt-6.1-terra" to Rate(1.0, 0.05, 5.0, 2.0, 0.10, 7.5),
        "gpt-6-luna" to Rate(0.10, 0.01, 0.50, 0.20, 0.02, 0.75),
        "gpt-6-sol" to Rate(2.0, 0.20, 10.0, 4.0, 0.40, 15.0),
        "gpt-5.6-sol" to Rate(4.0, 0.40, 20.0, 8.0, 0.80, 30.0),
        "gpt-5.6-terra" to Rate(2.0, 0.20, 12.0, 4.0, 0.40, 18.0),
        "gpt-5.6-luna" to Rate(0.20, 0.02, 1.20, 0.40, 0.04, 1.80),
        "gpt-5.5" to Rate(5.0, 0.50, 30.0, 10.0, 1.0, 45.0),
        "gpt-5.4" to Rate(2.5, 0.25, 15.0, 5.0, 0.50, 22.5),
        "gpt-5.4-mini" to Rate(0.75, 0.075, 4.5),
        "gpt-5.4-nano" to Rate(0.20, 0.02, 1.25),
        "gpt-5.3-codex" to Rate(1.75, 0.175, 14.0),
        "gpt-5.2" to Rate(1.75, 0.175, 14.0),
        "gpt-5.1" to Rate(1.25, 0.125, 10.0),
        "gpt-5" to Rate(1.25, 0.125, 10.0),
        "gpt-5-mini" to Rate(0.25, 0.025, 2.0),
        "gpt-5-nano" to Rate(0.05, 0.005, 0.40),
    )

    /** 认模型：先认全名，再认最长的「名字-」前缀（gpt-5.4-mini-2026xxxx 这类带日期的）。 */
    fun rateOf(model: String): Rate? {
        val m = model.lowercase().substringAfterLast('/')
        RATES[m]?.let { return it }
        return RATES.entries.filter { m.startsWith(it.key + "-") }.maxByOrNull { it.key.length }?.value
    }

    /** 这次请求按 API 价值多少美元；不认识的模型返回 null。 */
    fun usd(model: String, input: Int, cached: Int, output: Int): Double? {
        val r = rateOf(model) ?: return null
        val long = input > LONG_CONTEXT
        val hit = cached.coerceIn(0, input)
        return ((input - hit) * (if (long) r.longInput else r.input) +
            hit * (if (long) r.longCached else r.cached) +
            output * (if (long) r.longOutput else r.output)) / 1_000_000.0
    }
}
