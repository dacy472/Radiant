package eu.kanade.tachiyomi.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Applies a modern desktop Chrome [User-Agent] and matching Client Hints when the outgoing
 * request does not already specify them. Extension-specific requests that set their own
 * [User-Agent] are left untouched.
 *
 * [userAgent] may be updated at runtime after a Cloudflare WebView bypass; Client Hints are
 * recomputed from the Chrome major version in the string when possible.
 */
class UserAgentInterceptor : Interceptor {

    @Volatile
    var userAgent: String = DEFAULT_USER_AGENT
        set(value) {
            field = value
            clientHints = clientHintsForUserAgent(value)
        }

    @Volatile
    private var clientHints: ClientHints = clientHintsForUserAgent(DEFAULT_USER_AGENT)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val existingUserAgent = original.header("User-Agent")?.trim()

        if (!existingUserAgent.isNullOrEmpty()) {
            return chain.proceed(original)
        }

        val request = original.newBuilder()
            .header("User-Agent", userAgent)
            .header("Sec-CH-UA", clientHints.secChUa)
            .header("Sec-CH-UA-Mobile", clientHints.secChUaMobile)
            .header("Sec-CH-UA-Platform", clientHints.secChUaPlatform)
            .build()
        return chain.proceed(request)
    }

    private data class ClientHints(
        val secChUa: String,
        val secChUaMobile: String,
        val secChUaPlatform: String,
    )

    companion object {
        private const val CHROME_MAJOR = "149"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$CHROME_MAJOR.0.0.0 Safari/537.36"

        private fun clientHintsForUserAgent(userAgent: String): ClientHints {
            val chromeMajor = CHROME_VERSION_REGEX.find(userAgent)?.groupValues?.get(1) ?: CHROME_MAJOR
            return ClientHints(
                secChUa = "\"Chromium\";v=\"$chromeMajor\", \"Google Chrome\";v=\"$chromeMajor\", \"Not_A Brand\";v=\"24\"",
                secChUaMobile = "?0",
                secChUaPlatform = "\"Windows\"",
            )
        }

        private val CHROME_VERSION_REGEX = Regex("""Chrome/(\d+)""")
    }
}
