package eu.kanade.tachiyomi.network.interceptor

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class RateLimitInterceptorTest {

    @Test
    fun queuesSameHostPastBurst() {
        val interceptor = RateLimitInterceptor(permits = 1, period = 300.milliseconds)
        val times = Collections.synchronizedList(mutableListOf<Long>())
        val client = clientWith(interceptor, times)

        val workers = (1..2).map { index ->
            thread(name = "rate-limit-$index") {
                client.newCall(Request.Builder().url("https://cdn.example/img$index.jpg").build())
                    .execute()
                    .close()
            }
        }
        workers.forEach { it.join(5_000) }

        times.sort()
        assertTrue("expected 2 paced calls, got ${times.size}", times.size == 2)
        val span = times.last() - times.first()
        assertTrue("same-host calls should wait ~one period, span=${span}ms", span >= 250)
    }

    @Test
    fun differentHostsDoNotShareABucket() {
        val interceptor = RateLimitInterceptor(permits = 1, period = 400.milliseconds)
        val times = Collections.synchronizedList(mutableListOf<Long>())
        val client = clientWith(interceptor, times)

        val a = thread {
            client.newCall(Request.Builder().url("https://site.example/page").build()).execute().close()
        }
        val b = thread {
            client.newCall(Request.Builder().url("https://cdn.example/page.jpg").build()).execute().close()
        }
        a.join(2_000)
        b.join(2_000)

        times.sort()
        assertTrue(times.size == 2)
        val span = times.last() - times.first()
        assertTrue("independent hosts should not wait on each other, span=${span}ms", span < 250)
    }

    @Test
    fun hostFilterIgnoresOtherDomains() {
        val interceptor = RateLimitInterceptor(
            host = "slow.example",
            permits = 1,
            period = 400.milliseconds,
        )
        val times = Collections.synchronizedList(mutableListOf<Long>())
        val client = clientWith(interceptor, times)

        val workers = (1..2).map {
            thread {
                client.newCall(Request.Builder().url("https://fast.example/img$it").build())
                    .execute()
                    .close()
            }
        }
        workers.forEach { it.join(2_000) }
        times.sort()
        assertTrue(times.size == 2)
        assertTrue(times.last() - times.first() < 250)
    }

    @Test
    fun perHostConcurrencyCapsAtThree() {
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(PerHostConcurrencyInterceptor(3))
            .addInterceptor { chain ->
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                Thread.sleep(80)
                inFlight.decrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val workers = (1..8).map { index ->
            thread(name = "host-cap-$index") {
                client.newCall(Request.Builder().url("https://cdn.example/img$index.jpg").build())
                    .execute()
                    .close()
            }
        }
        workers.forEach { it.join(8_000) }
        assertTrue("peak in-flight should be 3, was ${peak.get()}", peak.get() == 3)
    }

    private fun clientWith(
        interceptor: RateLimitInterceptor,
        times: MutableList<Long>,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                times.add(System.currentTimeMillis())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
    }
}
