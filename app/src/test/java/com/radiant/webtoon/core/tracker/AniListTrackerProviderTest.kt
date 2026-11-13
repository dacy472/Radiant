package com.radiant.webtoon.core.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AniListTrackerProviderTest {
    @Test
    fun graphqlErrorsSurfaceFromHttp200() = runBlocking {
        val provider = AniListTrackerProvider(clientReturning("""{"errors":[{"message":"rate limited"}]}"""))
        val error = runCatching { provider.search("solo") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("rate limited", error?.message)
    }

    @Test
    fun failedSyncDoesNotBumpLastChapter() {
        assertEquals(4.0, TrackerSyncAck.nextLastChapter(4.0, 12, syncSucceeded = false), 0.0)
        assertEquals(12.0, TrackerSyncAck.nextLastChapter(4.0, 12, syncSucceeded = true), 0.0)
        assertEquals(12.0, TrackerSyncAck.nextLastChapter(12.0, 3, syncSucceeded = true), 0.0)
    }

    @Test
    fun mainDispatcherCallerIsNotBlockedByOkHttpExecute() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                jsonResponse(chain.request(), """{"data":{"Page":{"media":[]}}}""")
            }
            .build()
        val provider = AniListTrackerProvider(client)
        val blocked = AtomicBoolean(true)
        val search = async(Dispatchers.Default) {
            provider.search("x")
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        blocked.set(false)
        release.countDown()
        withTimeout(2_000) { search.await() }
        assertFalse(blocked.get())
    }

    private fun clientReturning(body: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> jsonResponse(chain.request(), body) }
            .build()
    }

    private fun jsonResponse(request: okhttp3.Request, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
