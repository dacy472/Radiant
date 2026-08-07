package com.radiant.webtoon.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterPagesResultTest {

    @Test
    fun cloudflareBlockedWhenEmptyAnd403() {
        val result = ChapterPagesResult(
            pages = emptyList(),
            httpStatus = 403,
            chapterUrl = "https://example.com/chapter/1",
        )
        assertTrue(result.isCloudflareBlocked())
    }

    @Test
    fun cloudflareBlockedWhenEmptyAnd503() {
        val result = ChapterPagesResult(
            pages = emptyList(),
            httpStatus = 503,
        )
        assertTrue(result.isCloudflareBlocked())
    }

    @Test
    fun notBlockedWhenPagesPresent() {
        val result = ChapterPagesResult(
            pages = listOf("https://cdn.example.com/1.jpg"),
            httpStatus = 403,
        )
        assertFalse(result.isCloudflareBlocked())
    }

    @Test
    fun notBlockedForEmpty404() {
        val result = ChapterPagesResult(
            pages = emptyList(),
            httpStatus = 404,
        )
        assertFalse(result.isCloudflareBlocked())
    }
}
