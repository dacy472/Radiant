@file:Keep
@file:JvmName("RequestsKt")
package eu.kanade.tachiyomi.network

import androidx.annotation.Keep
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody

val DEFAULT_CACHE_CONTROL: CacheControl = CacheControl.Builder().build()
val DEFAULT_HEADERS: Headers = Headers.Builder().build()

@JvmOverloads
fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).headers(headers).cacheControl(cache).build()

@JvmOverloads
fun GET(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).headers(headers).cacheControl(cache).build()

@JvmOverloads
fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = FormBody.Builder().build(),
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).post(body).headers(headers).cacheControl(cache).build()

@JvmOverloads
fun POST(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = FormBody.Builder().build(),
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).post(body).headers(headers).cacheControl(cache).build()

@JvmOverloads
fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = FormBody.Builder().build(),
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).put(body).headers(headers).cacheControl(cache).build()

@JvmOverloads
fun PUT(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = FormBody.Builder().build(),
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).put(body).headers(headers).cacheControl(cache).build()

@JvmOverloads
fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = FormBody.Builder().build(),
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).delete(body).headers(headers).cacheControl(cache).build()

@JvmOverloads
fun DELETE(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = FormBody.Builder().build(),
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request = Request.Builder().url(url).delete(body).headers(headers).cacheControl(cache).build()
