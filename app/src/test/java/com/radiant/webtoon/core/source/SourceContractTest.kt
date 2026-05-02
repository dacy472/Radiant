package com.radiant.webtoon.core.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceContractTest {
    @Test
    fun `default capabilities are conservative`() {
        val caps = SourceCapabilities()
        assertTrue(caps.supportsSearch)
        assertFalse(caps.supportsNsfw)
        assertFalse(caps.supportsFilters)
    }

    @Test
    fun `picsum source exposes search and latest`() {
        val source = PicsumSource()
        assertTrue(source.capabilities.supportsSearch)
        assertTrue(source.capabilities.supportsLatest)
    }
}
