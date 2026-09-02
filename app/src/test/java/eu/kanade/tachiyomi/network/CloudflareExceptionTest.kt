package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.network.interceptor.ConnectionResetRetryInterceptor
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketException

class CloudflareExceptionTest {

    @Test
    fun forRequestIncludesUrlAndHostMessage() {
        val request = Request.Builder().url("https://example.com/manga/foo").build()
        val exception = CloudflareException.forRequest(request)

        assertEquals("https://example.com/manga/foo", exception.url)
        assertEquals("Cloudflare verification required for example.com", exception.message)
    }

    @Test
    fun unwrapFindsNestedCause() {
        val root = CloudflareException.forRequest(
            Request.Builder().url("https://example.com/").build(),
        )
        val wrapped = RuntimeException("wrapper", root)

        assertNotNull(CloudflareException.unwrap(wrapped))
        assertEquals(root, CloudflareException.unwrap(wrapped))
    }

    @Test
    fun unwrapReturnsNullForUnrelatedErrors() {
        val error = SocketException("reset")
        assertNull(CloudflareException.unwrap(error))
        assertNull(CloudflareException.unwrap(IllegalStateException("other")))
    }
}
