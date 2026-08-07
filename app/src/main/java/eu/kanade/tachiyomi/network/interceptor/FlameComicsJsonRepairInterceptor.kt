package eu.kanade.tachiyomi.network.interceptor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Flame Comics API responses sometimes omit required kotlinx.serialization fields
 * (altTitles, tags, views). Inject defaults before the extension parses JSON.
 */
class FlameComicsJsonRepairInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val host = request.url.host
        if (!host.contains("flamecomics", ignoreCase = true)) return response

        val body = response.body
        val contentType = body.contentType() ?: JSON_MEDIA_TYPE
        val raw = body.string()
        val repaired = repairFlameComicsJsonBody(raw) ?: raw
        return response.newBuilder().body(repaired.toResponseBody(contentType)).build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private val flameJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

internal fun repairFlameComicsJsonBody(raw: String): String? {
    if (raw.isBlank() || (raw[0] != '{' && raw[0] != '[')) return null
    return try {
        flameJson.encodeToString(
            JsonElement.serializer(),
            repairFlameComicsElement(flameJson.parseToJsonElement(raw)),
        )
    } catch (_: Exception) {
        null
    }
}

private fun repairFlameComicsElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> {
        val fields = element.toMutableMap()
        if (looksLikeFlameComicsSeries(fields)) {
            if ("altTitles" !in fields) fields["altTitles"] = JsonArray(emptyList())
            if ("tags" !in fields) fields["tags"] = JsonArray(emptyList())
            if ("views" !in fields) fields["views"] = JsonPrimitive(0)
        }
        buildJsonObject {
            fields.forEach { (key, value) ->
                put(key, repairFlameComicsElement(value))
            }
        }
    }
    is JsonArray -> buildJsonArray {
        element.forEach { add(repairFlameComicsElement(it)) }
    }
    else -> element
}

private fun looksLikeFlameComicsSeries(fields: Map<String, JsonElement>): Boolean {
    if ("title" !in fields) return false
    return "slug" in fields || "id" in fields || "chapters" in fields || "description" in fields
}
