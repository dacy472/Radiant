package eu.kanade.tachiyomi.network.interceptor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 *
 * The site also ships catalogue entries with no `series_id` (unpublished rows). The released
 * extension (v1.6.3) declares that field non-null, so one bad row aborts the whole `browse.json`
 * / `latest.json` parse with "Field 'series_id' is required". Upstream keiyoushi now skips such
 * rows; we do the same here by dropping them from series arrays so the released APK keeps working.
 */
class FlameComicsJsonRepairInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!shouldRepair(request.url, response)) return response

        val body = response.body
        val contentType = body.contentType() ?: JSON_MEDIA_TYPE
        val raw = body.string()
        val repaired = repairFlameComicsJsonBody(raw) ?: return response.newBuilder()
            .body(raw.toResponseBody(contentType))
            .build()
        return response.newBuilder().body(repaired.toResponseBody(contentType)).build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Only touch JSON documents from the site. Reading an image body through `string()` and
         * re-encoding it corrupts the bytes, which is exactly what happened to
         * `cdn.flamecomics.xyz` page images ("BitmapFactory returned a null bitmap").
         */
        internal fun shouldRepair(url: okhttp3.HttpUrl, response: Response): Boolean {
            if (!url.host.contains("flamecomics", ignoreCase = true)) return false
            if (!response.isSuccessful) return false
            val contentType = response.body.contentType()
            if (contentType != null) {
                val isJson = contentType.subtype.contains("json", ignoreCase = true)
                return isJson
            }
            // No Content-Type: fall back to the Next.js data-route shape.
            return url.encodedPath.endsWith(".json", ignoreCase = true)
        }
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
            // Non-null in the released SeriesDto (v1.6.3); the series page omits popularityRank
            // and some rows omit last_edit/status/type/cover. Use neutral defaults: MAX_VALUE
            // sorts an unranked title last in "Popular" whether the field is Int or Int?.
            if ("popularityRank" !in fields || fields["popularityRank"] is JsonNull) {
                fields["popularityRank"] = JsonPrimitive(Int.MAX_VALUE)
            }
            if ("last_edit" !in fields || fields["last_edit"] is JsonNull) fields["last_edit"] = JsonPrimitive(0L)
            if ("status" !in fields || fields["status"] is JsonNull) fields["status"] = JsonPrimitive("Unknown")
            if ("type" !in fields || fields["type"] is JsonNull) fields["type"] = JsonPrimitive("")
            if ("cover" !in fields || fields["cover"] is JsonNull) fields["cover"] = JsonPrimitive("")
        }
        buildJsonObject {
            fields.forEach { (key, value) ->
                put(key, repairFlameComicsElement(value))
            }
        }
    }
    is JsonArray -> buildJsonArray {
        element
            .filterNot { it is JsonObject && isSeriesWithoutId(it) }
            .forEach { add(repairFlameComicsElement(it)) }
    }
    else -> element
}

private fun looksLikeFlameComicsSeries(fields: Map<String, JsonElement>): Boolean {
    if ("title" !in fields) return false
    return "slug" in fields || "id" in fields || "chapters" in fields || "description" in fields ||
        "series_id" in fields || "cover" in fields || "last_edit" in fields
}

/** Catalogue rows that cannot be opened: a series object whose `series_id` is absent or null. */
private fun isSeriesWithoutId(obj: JsonObject): Boolean {
    if ("title" !in obj) return false
    val isSeriesRow = "cover" in obj || "last_edit" in obj || "popularityRank" in obj || "status" in obj
    if (!isSeriesRow) return false
    val id = obj["series_id"]
    return id == null || id is JsonNull
}
