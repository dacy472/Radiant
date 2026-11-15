package eu.kanade.tachiyomi.network.interceptor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserAgentInterceptorTest {

    @Test
    fun injectsConfiguredUserAgentWhenMissing() {
        val interceptor = UserAgentInterceptor("WebView-UA/1.0")
        val captured = capture(interceptor, Request.Builder().url("https://example.com/").build())
        assertEquals("WebView-UA/1.0", captured.header("User-Agent"))
    }

    @Test
    fun keepsExtensionUserAgentSoClearanceStaysBound() {
        val interceptor = UserAgentInterceptor("WebView-UA/1.0")
        val captured = capture(
            interceptor,
            Request.Builder()
                .url("https://example.com/")
                .header("User-Agent", "CustomExt/1.0")
                .build(),
        )
        assertEquals("CustomExt/1.0", captured.header("User-Agent"))
    }

    @Test
    fun doesNotAttachClientHints() {
        val interceptor = UserAgentInterceptor("WebView-UA/1.0")
        val captured = capture(interceptor, Request.Builder().url("https://example.com/").build())
        assertNull(captured.header("Sec-CH-UA"))
        assertNull(captured.header("Accept"))
    }

    @Test
    fun pinsUpdatedUserAgentAfterCloudflareBypass() {
        val interceptor = UserAgentInterceptor { "Settings-UA/1.0" }
        interceptor.userAgent = "WebView-Clearance-UA/2.0"
        val captured = capture(interceptor, Request.Builder().url("https://example.com/").build())
        assertEquals("WebView-Clearance-UA/2.0", captured.header("User-Agent"))
    }

    private fun capture(
        interceptor: okhttp3.Interceptor,
        request: Request,
    ): Request {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                seen = chain.request()
                throw java.io.IOException("stop")
            }
            .build()
        try {
            client.newCall(request).execute()
        } catch (_: java.io.IOException) {
            // expected
        }
        return seen ?: error("interceptor did not run")
    }
}
