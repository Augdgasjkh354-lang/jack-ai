package com.jack.ai.features

import android.app.Application
import android.net.Uri
import com.jack.ai.AskWidget
import com.jack.ai.UiState
import com.jack.ai.api.ApiException
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 设置、其他模型服务、余额。 */
class SettingsFeature(core: AppCore, private val client: DeepSeekClient) : Feature(core) {
    fun updateSettings(transform: (Settings) -> Settings) {
        val before = state.value.settings
        state.update { it.copy(settings = transform(it.settings)) }
        val after = state.value.settings
        store.saveSettings(after)
        if (before.dreamEnabled != after.dreamEnabled) Dreamer.schedule(app, after.dreamEnabled)
    }

    /** 获取某个服务的模型列表，返回 (可用的地址, [模型 id 和价格])。 */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Pair<String, List<Pair<String, com.jack.ai.data.Price?>>> =
        client.listModels(baseUrl, apiKey)

    /** 查其他服务的余额；这个服务不支持查询时返回 null。 */
    suspend fun providerBalance(p: Provider): String? = try {
        client.providerBalance(p.baseUrl, p.apiKey)
    } catch (e: ApiException) {
        e.message ?: "查询失败"
    } catch (e: IOException) {
        "网络连接失败"
    }

    fun guessModel(id: String, price: com.jack.ai.data.Price? = null) =
        ProviderModel(id, vision = guessVision(id), reasoning = guessReasoning(id), price = price)

    fun saveProvider(p: Provider) {
        updateSettings { s ->
            val list = if (s.providers.any { it.id == p.id }) s.providers.map { if (it.id == p.id) p else it } else s.providers + p
            fixModel(s.copy(providers = list))
        }
    }

    fun deleteProvider(id: String) = updateSettings { s -> fixModel(s.copy(providers = s.providers.filterNot { it.id == id })) }

    /** 当前选的模型没了（服务删了、模型取消勾选），换成第一个能用的。 */
    internal fun fixModel(s: Settings): Settings {
        val choices = s.modelChoices()
        return if (choices.any { it.key == s.model } || choices.isEmpty()) s else s.copy(model = choices.first().key)
    }

    suspend fun checkBalance(): String {
        val s = state.value.settings
        if (s.apiKey.isBlank()) return "请先填写 API Key"
        return try {
            "余额：" + client.balance(s.baseUrl, s.apiKey)
        } catch (e: ApiException) {
            e.message ?: "查询失败"
        } catch (e: IOException) {
            "网络连接失败"
        }
    }
}
