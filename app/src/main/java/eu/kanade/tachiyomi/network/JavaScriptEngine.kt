package eu.kanade.tachiyomi.network

import android.content.Context
import app.cash.zipline.EngineApi
import app.cash.zipline.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mihon-compatible sandbox for evaluating JavaScript inside extensions and host helpers.
 * Backed by Zipline's 16 KB-aligned QuickJS native library. Provided to extensions via
 * `Injekt.get<JavaScriptEngine>()` (registered in ExtensionCompatModule).
 *
 * @since extensions-lib 1.4
 */
@OptIn(EngineApi::class)
@Suppress("UNUSED", "UNCHECKED_CAST")
class JavaScriptEngine(@Suppress("UNUSED_PARAMETER") context: Context? = null) {

    /**
     * Evaluate arbitrary JavaScript and return a primitive result (String, Number, etc.).
     */
    suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.IO) {
        evaluateBlocking(script)
    }

    /**
     * Blocking variant for use inside extension [pageListParse] and other synchronous paths.
     */
    fun <T> evaluateBlocking(script: String): T {
        QuickJs.create().use { quickJs ->
            return quickJs.evaluate(script) as T
        }
    }

    /**
     * Evaluate JS that resolves to a JSON array of image URL strings.
     */
    fun evaluateImageUrlList(script: String): List<String> {
        val result = evaluateBlocking<Any?>(script) ?: return emptyList()
        return when (result) {
            is Array<*> -> result.filterIsInstance<String>().filter { it.startsWith("http") }
            is List<*> -> result.filterIsInstance<String>().filter { it.startsWith("http") }
            is String -> PageListJsDecryptor.parseUrlListFromJson(result)
            else -> emptyList()
        }
    }
}
