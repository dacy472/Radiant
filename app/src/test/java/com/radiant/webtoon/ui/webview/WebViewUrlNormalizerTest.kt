package com.radiant.webtoon.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewUrlNormalizerTest {

    @Test
    fun apiUrlNormalizesToSiteOrigin() {
        assertEquals(
            "https://example.com/",
            WebViewUrlNormalizer.normalizeForCloudflareClearance(
                "https://example.com/api/v1/manga/list",
            ),
        )
    }

    @Test
    fun jsonUrlNormalizesToSiteOrigin() {
        assertEquals(
            "https://example.com/",
            WebViewUrlNormalizer.normalizeForCloudflareClearance(
                "https://example.com/data/chapters.json",
            ),
        )
    }

    @Test
    fun normalPageUrlIsUnchanged() {
        val url = "https://example.com/manga/foo/chapter-1"
        assertEquals(url, WebViewUrlNormalizer.normalizeForCloudflareClearance(url))
    }

    @Test
    fun apiUrlWithoutHostReturnsOriginal() {
        val url = "/api/v1/list"
        assertEquals(url, WebViewUrlNormalizer.normalizeForCloudflareClearance(url))
    }

    @Test
    fun preservesSchemeAndPort() {
        assertEquals(
            "http://localhost:8080/",
            WebViewUrlNormalizer.normalizeForCloudflareClearance(
                "http://localhost:8080/api/search.json",
            ),
        )
    }
}
