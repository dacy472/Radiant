package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRefsTest {

    @Test
    fun `deferred ref round-trips page url containing colons`() {
        val ref = PageRefs.deferred(7, "https://example.com/chapter/1?page=8:x")
        assertTrue(PageRefs.isDeferred(ref))
        assertEquals(
            PageRefs.Deferred(7, "https://example.com/chapter/1?page=8:x"),
            PageRefs.parseDeferred(ref),
        )
    }

    @Test
    fun `plain urls are not deferred`() {
        assertNull(PageRefs.parseDeferred("https://cdn.example.com/1.jpg"))
        assertFalse(PageRefs.isDeferred("file:///data/1.jpg"))
    }

    @Test
    fun `malformed deferred refs are rejected`() {
        assertNull(PageRefs.parseDeferred("radiant-page:"))
        assertNull(PageRefs.parseDeferred("radiant-page:abc:/p/1"))
        assertNull(PageRefs.parseDeferred("radiant-page:3:"))
    }

    @Test
    fun `remote covers http and deferred but not local files`() {
        assertTrue(PageRefs.isRemote("https://cdn.example.com/1.jpg"))
        assertTrue(PageRefs.isRemote("HTTP://cdn.example.com/1.jpg"))
        assertTrue(PageRefs.isRemote(PageRefs.deferred(0, "/chapter/1/page/1")))
        assertFalse(PageRefs.isRemote("file:///storage/1.jpg"))
        assertFalse(PageRefs.isRemote("content://downloads/1"))
    }
}
