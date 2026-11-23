package com.jack.ai.ui.photo

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.data.Store
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.endpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 修图里的「Kavi 配文」：看一眼这张图，给几句能直接写在图上的短句。 */
object PhotoCaption {
    private const val RULES = """You write short captions to put on top of a photo, in Chinese, for social media (朋友圈、小红书).
Look at the photo and give 6 different captions: each 4 to 14 characters, natural and specific to what's in the picture, varied in tone (warm, playful, poetic, plain). No hashtags, no emoji, no quotes.
Output only one JSON object: {"captions": ["...", "..."]}"""

    suspend fun suggest(ctx: Context, bmp: Bitmap): List<String> {
        val store = Store(ctx)
        val s = store.loadSettings()
        com.jack.ai.data.Quota.blocked(ctx, s)?.let { error(com.jack.ai.data.Quota.message(it)) }
        val ep = s.endpoint(s.model)?.takeIf { it.apiKey.isNotBlank() && it.vision } ?: s.backgroundEndpoint()?.takeIf { it.vision }
            ?: error("配文要能看图的模型：顶部换一个能看图的模型再试")
        val small = PhotoOps.scaleDown(bmp, 768)
        val jpg = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP)
        val raw = DeepSeekClient(store.usage("照片说明")).complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", RULES) },
                buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "给这张图配几句字。") })
                        add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) })
                    })
                },
            ),
            maxTokens = 600,
            jsonMode = true,
        )
        return parse(raw).ifEmpty { error("Kavi 这次没想出来，再点一次") }
    }

    internal fun parse(raw: String): List<String> = runCatching {
        val o = Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject
        (o["captions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { t -> t.isNotEmpty() && t.length <= 30 } }.take(8)
    }.getOrDefault(emptyList())
}
