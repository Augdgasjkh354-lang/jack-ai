package com.jack.ai.ui

import com.wangzan101.mika.core.cap.web.CapturedResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/** 只从同网站的商品/结果列表提取名称、价格、店铺、链接；其他接口数据不交给模型。 */
internal object BrowserNetworkRows {
    private val json = Json { ignoreUnknownKeys = true }
    private val names = listOf("skuName", "productName", "productTitle", "goodsName", "goodsTitle",
        "itemName", "itemTitle", "skuTitle", "subject", "product_title", "goods_name", "item_name", "title", "name")
    private val prices = listOf("finalPrice", "salePrice", "jdPrice", "skuPrice", "itemPrice",
        "goodsPrice", "discountPrice", "displayPrice", "priceValue", "priceText",
        "promotionPrice", "priceInfo", "price_info", "price")
    private val sellers = listOf("shopName", "storeName", "sellerName", "shopTitle",
        "shop_name", "store_name", "seller_name", "companyName", "shop", "store")
    private val links = listOf("detailUrl", "productUrl", "itemUrl", "goodsUrl", "product_url",
        "item_url", "detail_url", "href", "link", "url")
    private val blocked = Regex("(^|[/._-])(auth|login|password|token|account|profile|"
        + "checkout|orders?|payments?|pay|cart|addresses?|userinfo|users?|members?)([/._-]|$)",
        RegexOption.IGNORE_CASE)
    private data class Row(val name: String, val price: String, val seller: String, val link: String)
    private data class Candidate(val rows: List<Row>, val prices: Int, val recency: Int)

    fun table(responses: List<CapturedResponse>, pageUrl: String): JsonObject? {
        val pageHost = host(pageUrl)
        if (pageHost.isEmpty()) return null
        val sameSite = BrowserNetWatch.base(pageHost)
        var best: Candidate? = null
        for ((index, response) in responses.withIndex()) {
            if (response.state != CapturedResponse.State.DONE || (response.status ?: 0) !in 200..299 ||
                response.bodyTruncated || response.body.isNullOrBlank() ||
                response.type !in setOf("XHR", "Fetch") || !isJson(response.mime, response.body) ||
                BrowserNetWatch.base(host(response.url)) != sameSite || blocked.containsMatchIn(path(response.url))) continue
            val parsed = runCatching { json.parseToJsonElement(response.body) }.getOrNull() ?: continue
            val candidates = ArrayList<List<Row>>()
            scan(parsed, 0, candidates)
            for (rows in candidates) {
                val withPrice = rows.count { it.price.isNotBlank() }
                val candidate = Candidate(rows, withPrice, index)
                val old = best
                if (old == null ||
                    (candidate.prices > old.prices && candidate.rows.size >= old.rows.size / 2) ||
                    (candidate.rows.size > old.rows.size && candidate.prices >= old.prices) ||
                    (candidate.rows.size == old.rows.size && candidate.prices == old.prices &&
                        candidate.recency > old.recency)) best = candidate
            }
        }
        val rows = best?.rows ?: return null
        return buildJsonObject {
            put("tables", buildJsonArray {
                add(buildJsonObject {
                    put("headers", buildJsonArray {
                        listOf("名称", "价格", "店铺", "链接").forEach { add(JsonPrimitive(it)) }
                    })
                    put("rows", buildJsonArray {
                        rows.forEach { row ->
                            add(buildJsonArray {
                                listOf(row.name, row.price, row.seller, row.link).forEach { add(JsonPrimitive(it)) }
                            })
                        }
                    })
                })
            })
            put("lists", buildJsonArray { })
        }
    }

    private fun scan(node: JsonElement, depth: Int, found: MutableList<List<Row>>) {
        if (depth > 8 || found.size >= 30) return
        when (node) {
            is JsonArray -> {
                val rows = node.take(300).mapNotNull { (it as? JsonObject)?.let(::row) }
                    .distinctBy { it.name + "\u0000" + it.price + "\u0000" + it.seller }
                if (rows.size >= 2) found += rows
                // 继续找嵌套的 items/skuList，但不扫描上千条无关对象。
                node.take(20).forEach { scan(it, depth + 1, found) }
            }
            is JsonObject -> node.values.take(30).forEach { scan(it, depth + 1, found) }
            else -> Unit
        }
    }

    private fun row(o: JsonObject): Row? {
        val name = text(o, names).take(120)
        if (name.isBlank()) return null
        val price = text(o, prices).take(60)
        val seller = text(o, sellers).take(80)
        val link = text(o, links).take(220).takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
        if (price.isBlank() && seller.isBlank() && link.isBlank()) return null
        return Row(name, price, seller, link)
    }

    private fun text(o: JsonObject, keys: List<String>): String {
        val normalized = o.entries.associate { it.key.lowercase().replace("_", "") to it.value }
        for (key in keys) {
            val v = normalized[key.lowercase().replace("_", "")] ?: continue
            val value = when (v) {
                is JsonPrimitive -> v.content
                is JsonObject -> listOf("value", "amount", "price", "text", "current").firstNotNullOfOrNull { k ->
                    (v[k] as? JsonPrimitive)?.content
                }.orEmpty()
                else -> ""
            }.replace(Regex("\\s+"), " ").trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun isJson(mime: String?, body: String): Boolean {
        val m = mime.orEmpty().lowercase()
        val first = body.trimStart().firstOrNull()
        return ("json" in m || m.startsWith("text/plain")) && (first == '{' || first == '[')
    }

    private fun host(url: String) = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    private fun path(url: String) = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
}
