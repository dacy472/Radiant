package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareInterceptorRefererTest {

    @Test
    fun cdnChallengeKeepsImageUrlAndMangaReferer() {
        val cdn =
            "https://2xstorage.com/data/abc/page-01.webp?token=xyz"
        val mangaReferer = "https://manhwa18.cc/webtoon/foo/chapter-1"
        val request = Request.Builder()
            .url(cdn)
            .header("Referer", mangaReferer)
            .header("User-Agent", "TestUA")
            .build()

        assertEquals(cdn, request.url.toString())
        assertFalse(request.url.host.contains("manhwa18"))
        assertEquals(mangaReferer, CloudflareInterceptor.challengeRefererFor(request))
        assertEquals("2xstorage.com", request.url.host)
    }

    @Test
    fun missingRefererFallsBackToRequestOrigin() {
        val request = Request.Builder()
            .url("https://manhwa18.cc/manga/foo")
            .build()
        assertEquals(
            "https://manhwa18.cc/",
            CloudflareInterceptor.challengeRefererFor(request),
        )
    }

    @Test
    fun originRefererNeverUsesCdnPath() {
        val origin = CloudflareInterceptor.originReferer(
            "https://2xstorage.com/data/abc/page-01.webp",
        )
        assertEquals("https://2xstorage.com/", origin)
        assertTrue(origin.endsWith("/"))
        assertFalse(origin.contains("page-01"))
    }
}
