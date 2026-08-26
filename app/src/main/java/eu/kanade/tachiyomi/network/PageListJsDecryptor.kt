package eu.kanade.tachiyomi.network

import com.radiant.webtoon.core.debug.PageListDebugLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Evaluates obfuscated inline JavaScript from chapter pages to recover image URL lists.
 */
object PageListJsDecryptor {

    private val json = Json { ignoreUnknownKeys = true }

    private val OBFUSCATION_MARKERS = listOf(
        "eval(",
        "Function(",
        "atob(",
        "btoa(",
        "decodeURIComponent",
        "decrypt",
        "unscramble",
        "__NEXT_DATA__",
        "cf_chl",
        "obfuscat",
    )

    private val URL_REGEX = Regex("""https?://[^\s"'<>\\]+?\.(?:jpg|jpeg|png|webp)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

    /**
     * Scan chapter HTML for obfuscated scripts and evaluate them in [engine].
     */
    fun decryptFromHtml(html: String, engine: JavaScriptEngine): List<String> {
        val document = try {
            Jsoup.parse(html)
        } catch (e: Exception) {
            PageListDebugLogger.logParseFailure("jsoup_parse_html", e, html)
            return emptyList()
        }
        val nextDataUrls = decryptFromNextData(document, engine)
        if (nextDataUrls.isNotEmpty()) return nextDataUrls

        val scripts = document.select("script").mapNotNull { element ->
            element.data().takeIf { it.isNotBlank() } ?: element.html().takeIf { it.isNotBlank() }
        }

        for (script in scripts) {
            if (OBFUSCATION_MARKERS.none { marker -> script.contains(marker, ignoreCase = true) }) {
                continue
            }
            val urls = decryptFromScript(script, engine)
            if (urls.isNotEmpty()) return urls
        }

        val regexUrls = URL_REGEX.findAll(html).map { it.value }.distinct().toList()
        if (regexUrls.isEmpty()) {
            PageListDebugLogger.logStep(
                step = "decryptFromHtml_no_urls",
                responseBody = html,
                strategy = "regex_fallback",
                extra = mapOf("scriptCount" to scripts.size.toString()),
            )
        }
        return regexUrls
    }

    /**
     * Evaluate a single obfuscated script block and extract image URLs from the result.
     */
    fun decryptFromScript(script: String, engine: JavaScriptEngine): List<String> {
        for ((index, candidate) in buildEvaluationCandidates(script).withIndex()) {
            try {
                val urls = engine.evaluateImageUrlList(candidate)
                if (urls.isNotEmpty()) return urls
            } catch (e: Exception) {
                PageListDebugLogger.logJsEvalFailure(candidate, e, "evaluateImageUrlList#$index")
            }

            try {
                val raw = engine.evaluateBlocking<String>(candidate)
                val urls = parseUrlListFromJson(raw) + URL_REGEX.findAll(raw).map { it.value }
                if (urls.isNotEmpty()) return urls.distinct()
            } catch (e: Exception) {
                PageListDebugLogger.logJsEvalFailure(candidate, e, "evaluateBlocking#$index")
            }
        }
        PageListDebugLogger.logStep(
            step = "decryptFromScript_exhausted",
            strategy = "buildEvaluationCandidates",
            extra = mapOf("scriptLength" to script.length.toString()),
        )
        return emptyList()
    }

    /**
     * Decrypt an encrypted payload using helper functions defined in the chapter HTML scripts.
     */
    fun decryptPayload(encryptedPayload: String, html: String, engine: JavaScriptEngine): List<String> {
        val escaped = escapeForJsString(encryptedPayload)
        val scripts = Jsoup.parse(html).select("script").mapNotNull { it.data().takeIf { s -> s.isNotBlank() } }
        val prelude = scripts.joinToString("\n")

        val attempts = listOf(
            """$prelude; JSON.stringify(decrypt("$escaped"))""",
            """$prelude; JSON.stringify(decryptImages("$escaped"))""",
            """$prelude; JSON.stringify(getImages("$escaped"))""",
            """$prelude; JSON.stringify((function(){var p="$escaped"; return typeof decrypt==="function"?decrypt(p):p;})())""",
        )

        for ((index, attempt) in attempts.withIndex()) {
            try {
                val urls = engine.evaluateImageUrlList(attempt)
                if (urls.isNotEmpty()) return urls
            } catch (e: Exception) {
                PageListDebugLogger.logJsEvalFailure(attempt, e, "decryptPayload#$index")
            }
        }
        return decryptFromHtml(html, engine)
    }

    fun parseUrlListFromJson(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            when (val element = json.parseToJsonElement(trimmed)) {
                is JsonArray -> element.flatMap { extractUrlsFromJson(it) }
                is JsonObject -> extractUrlsFromJson(element)
                is JsonPrimitive -> if (element.isString && element.content.startsWith("http")) {
                    listOf(element.content)
                } else {
                    emptyList()
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            PageListDebugLogger.logParseFailure("parseUrlListFromJson", e, raw)
            emptyList()
        }
    }

    internal fun extractEncryptedImagesField(jsonBody: String): String? {
        return try {
            val root = json.parseToJsonElement(jsonBody)
            findEncryptedImagesValue(root)
        } catch (e: Exception) {
            PageListDebugLogger.logParseFailure("extractEncryptedImagesField", e, jsonBody)
            null
        }
    }

    private fun decryptFromNextData(document: Document, engine: JavaScriptEngine): List<String> {
        val nextData = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val urls = parseUrlListFromJson(nextData).flatMap { url ->
            if (url.startsWith("http")) listOf(url) else emptyList()
        }
        if (urls.isNotEmpty()) return urls.distinct()

        // Some Next.js pages embed encrypted blobs beside the JSON — evaluate adjacent scripts.
        val siblingScripts = document.select("script").mapNotNull { it.data().takeIf { s -> s.isNotBlank() } }
        for (script in siblingScripts) {
            val combined = "var __NEXT_DATA__ = $nextData;\n$script"
            val decrypted = decryptFromScript(combined, engine)
            if (decrypted.isNotEmpty()) return decrypted
        }
        return emptyList()
    }

    private fun findEncryptedImagesValue(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                element["images"]?.let { images ->
                    if (images is JsonPrimitive && images.isString && images.content.length > 40) {
                        return images.content
                    }
                }
                element.values.forEach { child ->
                    findEncryptedImagesValue(child)?.let { return it }
                }
            }
            is JsonArray -> element.forEach { child ->
                findEncryptedImagesValue(child)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    private fun extractUrlsFromJson(element: JsonElement): List<String> {
        return when (element) {
            is JsonPrimitive -> {
                if (!element.isString) return emptyList()
                val content = element.content
                when {
                    content.startsWith("http") && looksLikeImageUrl(content) -> listOf(content)
                    content.startsWith("{") || content.startsWith("[") -> parseUrlListFromJson(content)
                    else -> emptyList()
                }
            }
            is JsonArray -> element.flatMap { extractUrlsFromJson(it) }
            is JsonObject -> {
                val direct = listOfNotNull(
                    element["url"]?.jsonPrimitive?.takeIf { it.isString }?.content,
                    element["imageUrl"]?.jsonPrimitive?.takeIf { it.isString }?.content,
                    element["src"]?.jsonPrimitive?.takeIf { it.isString }?.content,
                    element["name"]?.jsonPrimitive?.takeIf { it.isString }?.content,
                ).firstOrNull()
                if (!direct.isNullOrBlank() && direct.startsWith("http")) {
                    listOf(direct)
                } else {
                    element.values.flatMap { extractUrlsFromJson(it) }
                }
            }
            else -> emptyList()
        }
    }

    private fun looksLikeImageUrl(url: String): Boolean {
        return url.contains(".jpg", ignoreCase = true) ||
            url.contains(".jpeg", ignoreCase = true) ||
            url.contains(".png", ignoreCase = true) ||
            url.contains(".webp", ignoreCase = true)
    }

    private fun buildEvaluationCandidates(script: String): List<String> {
        val trimmed = script.trim()
        return listOf(
            """(function(){ try { var __result = (function(){ $trimmed })(); if (Array.isArray(__result)) return JSON.stringify(__result); if (typeof __result === 'string') return __result; if (typeof images !== 'undefined') return JSON.stringify(images); if (typeof pages !== 'undefined') return JSON.stringify(pages); return '[]'; } catch(e) { return '[]'; } })()""",
            """(function(){ try { $trimmed; if (typeof images !== 'undefined') return JSON.stringify(images); if (typeof pages !== 'undefined') return JSON.stringify(pages); return '[]'; } catch(e) { return '[]'; } })()""",
            trimmed,
        )
    }

    private fun escapeForJsString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
