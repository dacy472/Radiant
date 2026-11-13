package com.radiant.webtoon.core.image

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ImageFetchRetryTest {

    @Test
    fun `retryable covers io errors 429 and 5xx only`() {
        assertTrue(ImageFetchRetry.isRetryable(IOException("timeout")))
        assertTrue(ImageFetchRetry.isRetryable(IOException("HTTP error 429")))
        assertTrue(ImageFetchRetry.isRetryable(IOException("HTTP 503")))
        assertTrue(ImageFetchRetry.isRetryable(RuntimeException("HTTP 502: Bad Gateway")))
        assertFalse(ImageFetchRetry.isRetryable(IOException("HTTP error 403")))
        assertFalse(ImageFetchRetry.isRetryable(IOException("HTTP 404")))
        assertFalse(ImageFetchRetry.isRetryable(IllegalStateException("bad state")))
    }

    @Test
    fun `run retries transient failures until success`() = runTest {
        var calls = 0
        val result = ImageFetchRetry.run {
            calls++
            if (calls < 3) throw IOException("HTTP 500")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `run gives up immediately on non retryable errors`() = runTest {
        var calls = 0
        try {
            ImageFetchRetry.run<Unit> {
                calls++
                throw IOException("HTTP error 403")
            }
            fail("expected failure")
        } catch (e: IOException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `run stops after max attempts`() = runTest {
        var calls = 0
        try {
            ImageFetchRetry.run<Unit>(maxAttempts = 2) {
                calls++
                throw IOException("reset")
            }
            fail("expected failure")
        } catch (e: IOException) {
            assertEquals(2, calls)
        }
    }
}
