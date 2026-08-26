package com.radiant.webtoon.core.image

import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Headers

class SourceImageHeadersTest {

    @Test
    fun originRefererUsesSiteHostWithTrailingSlash() {
        assertEquals("https://manhwa18.cc/", SourceImageHeaders.originReferer("https://manhwa18.cc"))
        assertEquals("https://manhwa18.cc/", SourceImageHeaders.originReferer("https://manhwa18.cc/manga/foo"))
        assertEquals("https://manhwa18.cc/", SourceImageHeaders.originReferer("manhwa18.cc"))
    }

    @Test
    fun originRefererIsNotTheCdnHost() {
        val origin = SourceImageHeaders.originReferer("https://manhwa18.cc")
        assertFalse(origin.contains("2xstorage.com"))
        assertEquals("https://manhwa18.cc/", origin)
    }

    @Test
    fun injectsRefererWhenSourceOmitsIt() {
        val headers = mutableMapOf("User-Agent" to "Mozilla/5.0 (Radiant)")
        SourceImageHeaders.ensureReferer(headers, "https://manhwa18.cc")
        assertEquals("https://manhwa18.cc/", headers["Referer"])
        assertEquals("Mozilla/5.0 (Radiant)", headers["User-Agent"])
    }

    @Test
    fun keepsExtensionRefererWhenPresent() {
        val headers = mutableMapOf("Referer" to "https://manhwa18.cc/chapter/1")
        SourceImageHeaders.ensureReferer(headers, "https://other-site.com")
        assertEquals("https://manhwa18.cc/chapter/1", headers["Referer"])
    }

    @Test
    fun explicitHeadersWinButRefererIsFilled() {
        val resolved = SourceImageHeaders.resolve(
            explicit = mapOf("User-Agent" to "Ext-UA/1.0"),
            source = null,
        )
        assertEquals("Ext-UA/1.0", resolved["User-Agent"])
        assertFalse(SourceImageHeaders.hasHeader(resolved, "Referer"))
    }

    @Test
    fun emptyOrNullHeadersStillGetWebViewUserAgent() {
        val resolved = SourceImageHeaders.resolve(
            explicit = emptyMap(),
            source = null,
            defaultUserAgent = UserAgentInterceptor.FALLBACK_USER_AGENT,
        )
        assertTrue(SourceImageHeaders.hasHeader(resolved, SourceImageHeaders.USER_AGENT))
        assertEquals(UserAgentInterceptor.FALLBACK_USER_AGENT, resolved[SourceImageHeaders.USER_AGENT])
    }

    @Test
    fun resolveKeepsMultipleValuesForTheSameHeaderName() {
        val explicit = Headers.Builder()
            .add("Cookie", "session=abc")
            .add("Cookie", "theme=dark")
            .build()
        val resolved = SourceImageHeaders.resolve(
            explicit = explicit,
            source = null,
            defaultUserAgent = UserAgentInterceptor.FALLBACK_USER_AGENT,
        )
        assertEquals(listOf("session=abc", "theme=dark"), resolved.values("Cookie"))
        assertEquals(2, resolved.values("Cookie").size)
    }
}
