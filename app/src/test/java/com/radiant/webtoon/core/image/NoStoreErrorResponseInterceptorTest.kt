package com.radiant.webtoon.core.image

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoStoreErrorResponseInterceptorTest {

    @Test
    fun forbiddenResponseGetsNoStoreCacheControl() {
        val response = executeWithStatus(403)
        assertTrue(response.headers("Cache-Control").contains("no-store"))
        response.close()
    }

    @Test
    fun successfulResponseDoesNotGetNoStoreCacheControl() {
        val response = executeWithStatus(200)
        assertFalse(response.headers("Cache-Control").contains("no-store"))
        response.close()
    }

    private fun executeWithStatus(code: Int): Response {
        val client = OkHttpClient.Builder()
            .addInterceptor(NoStoreErrorResponseInterceptor())
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body("body".toResponseBody(null))
                    .build()
            }
            .build()
        return client.newCall(Request.Builder().url("https://example.com/image.jpg").build()).execute()
    }
}
