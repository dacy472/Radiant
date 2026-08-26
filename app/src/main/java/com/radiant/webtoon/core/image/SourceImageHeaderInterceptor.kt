package com.radiant.webtoon.core.image

import coil.intercept.Interceptor
import coil.request.ImageResult
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.source.Source
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor as OkHttpInterceptor
import okhttp3.Response

data class ImageSourceId(val id: String)

/**
 * Coil interceptor: attaches the extension [Source] headers (Referer, User-Agent)
 * to image requests. Matching is by explicit [ImageSourceId] tag first so CDN
 * hosts (e.g. 2xstorage.com) still get the origin site Referer.
 */
class SourceImageCoilInterceptor(
    private val extensionManager: ExtensionManager,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = (request.data as? String) ?: (request.data as? android.net.Uri)?.toString()
        val taggedId = request.tags.tag(ImageSourceId::class.java)?.id
            ?: request.headers[SourceImageHeaders.SOURCE_ID_HEADER]
        val source = resolveSource(taggedId, url)
        val resolved = SourceImageHeaders.withoutSourceId(
            SourceImageHeaders.resolve(request.headers, source),
        )

        if (headersMatch(request.headers, resolved)) {
            return chain.proceed(request)
        }

        val finalRequest = request.newBuilder()
            .headers(resolved)
            .apply { source?.id?.let { tag(ImageSourceId::class.java, ImageSourceId(it)) } }
            .build()
        return chain.proceed(finalRequest)
    }

    private fun resolveSource(sourceId: String?, url: String?): Source? {
        if (!sourceId.isNullOrBlank()) {
            extensionManager.getSource(sourceId)?.let { return it }
        }
        val host = url?.toHttpUrlOrNull()?.host ?: return null
        return extensionManager.installedExtensions.value.firstOrNull { source ->
            val sourceHost = SourceImageHeaders.originReferer(source.baseUrl)
                .toHttpUrlOrNull()?.host ?: return@firstOrNull false
            host.equals(sourceHost, ignoreCase = true) ||
                host.endsWith(".$sourceHost", ignoreCase = true) ||
                sourceHost.endsWith(".$host", ignoreCase = true)
        }
    }

    private fun headersMatch(current: Headers, resolved: Headers): Boolean {
        val currentClean = SourceImageHeaders.withoutSourceId(current)
        if (currentClean.size != resolved.size) return false
        for (i in 0 until resolved.size) {
            if (!currentClean.name(i).equals(resolved.name(i), ignoreCase = false) ||
                currentClean.value(i) != resolved.value(i)
            ) {
                return false
            }
        }
        return true
    }
}

/**
 * Last-line OkHttp interceptor on the image client. Strips the internal source-id
 * header and injects origin Referer / source User-Agent so CDN requests are never naked.
 */
class SourceImageOkHttpInterceptor(
    private val getSource: (String) -> Source?,
    private val installedSources: () -> List<Source>,
) : OkHttpInterceptor {

    constructor(extensionManager: ExtensionManager) : this(
        getSource = { extensionManager.getSource(it) },
        installedSources = { extensionManager.installedExtensions.value },
    )

    override fun intercept(chain: OkHttpInterceptor.Chain): Response {
        val request = chain.request()
        val sourceId = request.header(SourceImageHeaders.SOURCE_ID_HEADER)
        val source = sourceId?.let { getSource(it) }
            ?: matchHost(request.url.host)

        val builder = request.newBuilder()
            .removeHeader(SourceImageHeaders.SOURCE_ID_HEADER)
        if (request.header(SourceImageHeaders.REFERER) == null) {
            val referer = SourceImageHeaders.originReferer(source?.baseUrl.orEmpty())
            if (referer.isNotBlank()) {
                builder.addHeader(SourceImageHeaders.REFERER, referer)
            }
        }
        if (request.header(SourceImageHeaders.USER_AGENT) == null) {
            val ua = source?.let { SourceImageHeaders.from(it)[SourceImageHeaders.USER_AGENT] }
                ?.takeIf { it.isNotBlank() }
                ?: SourceImageHeaders.defaultNetworkUserAgent()
            if (ua.isNotBlank()) {
                builder.addHeader(SourceImageHeaders.USER_AGENT, ua)
            }
        }
        return chain.proceed(builder.build())
    }

    private fun matchHost(host: String): Source? {
        return installedSources().firstOrNull { source ->
            val sourceHost = SourceImageHeaders.originReferer(source.baseUrl)
                .toHttpUrlOrNull()?.host ?: return@firstOrNull false
            host.equals(sourceHost, ignoreCase = true) ||
                host.endsWith(".$sourceHost", ignoreCase = true)
        }
    }
}
