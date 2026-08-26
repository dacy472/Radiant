package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareChallengeDetectorTest {

    @Test
    fun ignores403WithCfRayOnly() {
        val response = response(
            code = 403,
            headers = mapOf("cf-ray" to "abc123", "Server" to "cloudflare"),
            body = "<html></html>",
        )
        assertFalse(CloudflareChallengeDetector.isChallengeResponse(response))
        response.close()
    }

    @Test
    fun detects403WithChallengeErrorTitle() {
        val response = response(
            code = 403,
            headers = mapOf("Server" to "cloudflare"),
            body = "<html><div id=\"challenge-error-title\">Enable JavaScript</div></html>",
        )
        assertTrue(CloudflareChallengeDetector.isChallengeResponse(response))
        response.close()
    }

    @Test
    fun detects503WithChallengeHtml() {
        val response = response(
            code = 503,
            body = "<html><title>Just a moment...</title></html>",
        )
        assertTrue(CloudflareChallengeDetector.isChallengeResponse(response))
        response.close()
    }

    @Test
    fun ignores200OkPage() {
        val response = response(
            code = 200,
            body = "<html><img src=\"page.jpg\"></html>",
        )
        assertFalse(CloudflareChallengeDetector.isChallengeResponse(response))
        response.close()
    }

    @Test
    fun hasClearanceCookieRequiresToken() {
        assertTrue(
            CloudflareChallengeDetector.hasClearanceCookie("cf_clearance=abc; __cf_bm=xyz"),
        )
        assertFalse(
            CloudflareChallengeDetector.hasClearanceCookie("__cf_bm=xyz"),
        )
    }

    @Test
    fun newClearanceIgnoresStaleCookie() {
        assertFalse(
            CloudflareChallengeDetector.hasNewClearance(
                "cf_clearance=old",
                previousValue = "old",
            ),
        )
        assertTrue(
            CloudflareChallengeDetector.hasNewClearance(
                "cf_clearance=fresh",
                previousValue = "old",
            ),
        )
    }

    private fun response(
        code: Int,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ) = okhttp3.Response.Builder()
        .request(Request.Builder().url("https://example.com/chapter").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .apply {
            headers.forEach { (name, value) -> header(name, value) }
        }
        .body(body.toResponseBody())
        .build()
}
