package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareCookiePropagationTest {

    @Test
    fun cdnHostIsNotSameSiteAsMangaOrigin() {
        assertFalse(
            CloudflareCookiePropagation.sameCookieSite("manhwa18.cc", "2xstorage.com"),
        )
        assertTrue(
            CloudflareCookiePropagation.sameCookieSite("www.manhwa18.cc", "cdn.manhwa18.cc"),
        )
    }

    @Test
    fun mergesClearanceOntoExistingCdnCookies() {
        val origin = "https://manhwa18.cc/chapter/1".toHttpUrl()
        val clearance = Cookie.parse(origin, "cf_clearance=solved-token; Path=/")!!
        val merged = CloudflareCookiePropagation.mergeCookieHeader(
            existing = "session=abc",
            extra = listOf(clearance),
        )
        assertTrue(merged!!.contains("session=abc"))
        assertTrue(merged.contains("cf_clearance=solved-token"))
    }

    @Test
    fun doesNotOverwriteCookieAlreadyOnRequest() {
        val origin = "https://manhwa18.cc/".toHttpUrl()
        val clearance = Cookie.parse(origin, "cf_clearance=new; Path=/")!!
        val merged = CloudflareCookiePropagation.mergeCookieHeader(
            existing = "cf_clearance=existing",
            extra = listOf(clearance),
        )
        assertEquals("cf_clearance=existing", merged)
    }

    @Test
    fun filtersOnlyCloudflareCookies() {
        val origin = "https://manhwa18.cc/".toHttpUrl()
        val cookies = listOf(
            Cookie.parse(origin, "session=keep; Path=/")!!,
            Cookie.parse(origin, "cf_clearance=token; Path=/")!!,
            Cookie.parse(origin, "__cf_bm=bot; Path=/")!!,
        )
        val cf = CloudflareCookiePropagation.cloudflareOnly(cookies)
        assertEquals(listOf("cf_clearance", "__cf_bm"), cf.map { it.name })
    }
}
