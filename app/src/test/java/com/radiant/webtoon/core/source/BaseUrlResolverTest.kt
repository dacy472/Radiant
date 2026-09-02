package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlResolverTest {

    @Test
    fun effectiveUsesDefaultWhenOverrideBlank() {
        assertEquals("https://example.com", BaseUrlResolver.effective("https://example.com", null))
        assertEquals("https://example.com", BaseUrlResolver.effective("https://example.com", "  "))
    }

    @Test
    fun effectiveUsesOverrideWhenPresent() {
        assertEquals(
            "https://mirror.example.com",
            BaseUrlResolver.effective("https://example.com", "https://mirror.example.com/"),
        )
    }

    @Test
    fun isValidOverrideAcceptsHttpAndHttps() {
        assertTrue(BaseUrlResolver.isValidOverride("https://mirror.com"))
        assertTrue(BaseUrlResolver.isValidOverride("http://mirror.com"))
        assertTrue(BaseUrlResolver.isValidOverride(""))
    }

    @Test
    fun isValidOverrideRejectsInvalidUrls() {
        assertFalse(BaseUrlResolver.isValidOverride("not a url"))
        assertFalse(BaseUrlResolver.isValidOverride("ftp://mirror.com"))
    }

    @Test
    fun rewriteUrlSwapsDefaultHostForOverrideHost() {
        val rewritten = BaseUrlResolver.rewriteUrl(
            url = "https://blocked.com/manga/foo",
            defaultBaseUrl = "https://blocked.com",
            effectiveBaseUrl = "https://mirror.com",
        )
        assertEquals("https://mirror.com/manga/foo", rewritten)
    }

    @Test
    fun rewriteUrlLeavesUnrelatedHostsUntouched() {
        val cdnUrl = "https://cdn.example.net/image.jpg"
        assertEquals(
            cdnUrl,
            BaseUrlResolver.rewriteUrl(
                url = cdnUrl,
                defaultBaseUrl = "https://blocked.com",
                effectiveBaseUrl = "https://mirror.com",
            ),
        )
    }

    @Test
    fun buildHostRewriteMapMapsDefaultToOverrideHost() {
        val adapter = TachiyomiSourceAdapter(
            tachiyomiSource = object : eu.kanade.tachiyomi.source.Source {
                override val id = 123L
                override val name = "Test"
            },
            defaultBaseUrl = "https://blocked.com",
            baseUrlOverride = "https://mirror.com",
        )
        val map = BaseUrlResolver.buildHostRewriteMap(
            sources = listOf(adapter),
            overrides = mapOf(adapter.id to "https://mirror.com"),
        )
        assertEquals(mapOf("blocked.com" to "mirror.com"), map)
    }
}
