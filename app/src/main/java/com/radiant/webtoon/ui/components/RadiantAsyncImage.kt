package com.radiant.webtoon.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.image.SourceImageHeaders
import com.radiant.webtoon.core.image.evictFromMemoryCache
import com.radiant.webtoon.core.image.toOkHttpHeaders
import com.radiant.webtoon.core.source.Source
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.WebViewUserAgentProvider
import okhttp3.Headers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Disk-prioritized Coil image with aggressive in-memory eviction when the composable
 * leaves composition (e.g. scrolls off a virtualized reader or library grid).
 *
 * Pass [headers] from the extension manager (Referer, User-Agent, etc.) so CDN hotlink
 * protection and Cloudflare challenges succeed. When omitted, headers are derived from
 * [manga], [source], or [sourceId] via [SourceImageHeaders].
 */
@Composable
fun RadiantAsyncImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    manga: Manga? = null,
    source: Source? = null,
    sourceId: String? = null,
    widthPx: Int? = null,
    heightPx: Int? = null,
    widthDp: Int? = null,
    heightDp: Int? = null,
    precision: Precision = Precision.INEXACT,
    scale: Scale = Scale.FIT,
    contentScale: ContentScale = ContentScale.Crop,
    allowHardware: Boolean = true,
    crossfade: Boolean = true,
    evictFromMemoryOnDispose: Boolean = true,
    loading: @Composable (() -> Unit)? = null,
    error: @Composable (() -> Unit)? = null,
) {
    RadiantAsyncImage(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier,
        headers = headers.toOkHttpHeaders().takeIf { headers.isNotEmpty() },
        manga = manga,
        source = source,
        sourceId = sourceId,
        widthPx = widthPx,
        heightPx = heightPx,
        widthDp = widthDp,
        heightDp = heightDp,
        precision = precision,
        scale = scale,
        contentScale = contentScale,
        allowHardware = allowHardware,
        crossfade = crossfade,
        evictFromMemoryOnDispose = evictFromMemoryOnDispose,
        loading = loading,
        error = error,
    )
}

@Composable
fun RadiantAsyncImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    headers: Headers? = null,
    manga: Manga? = null,
    source: Source? = null,
    sourceId: String? = null,
    widthPx: Int? = null,
    heightPx: Int? = null,
    widthDp: Int? = null,
    heightDp: Int? = null,
    precision: Precision = Precision.INEXACT,
    scale: Scale = Scale.FIT,
    contentScale: ContentScale = ContentScale.Crop,
    allowHardware: Boolean = true,
    crossfade: Boolean = true,
    evictFromMemoryOnDispose: Boolean = true,
    loading: @Composable (() -> Unit)? = null,
    error: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    val effectiveSourceId = sourceId ?: manga?.source ?: source?.id
    val resolvedSource = source ?: remember(effectiveSourceId) {
        lookupSource(context, effectiveSourceId)
    }

    val request = remember(url, headers, resolvedSource, effectiveSourceId, widthPx, heightPx, widthDp, heightDp, precision, scale, allowHardware, crossfade) {
        val defaultUa = runCatching {
            Injekt.get<NetworkHelper>().defaultUserAgentProvider()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: WebViewUserAgentProvider.getDefault(context)
        val resolvedHeaders = SourceImageHeaders.resolve(
            explicit = headers ?: Headers.headersOf(),
            source = resolvedSource,
            defaultUserAgent = defaultUa,
        )
        ImageRequest.Builder(context)
            .data(url)
            .headers(resolvedHeaders)
            .apply {
                if (!effectiveSourceId.isNullOrBlank()) {
                    tag(com.radiant.webtoon.core.image.ImageSourceId::class.java, com.radiant.webtoon.core.image.ImageSourceId(effectiveSourceId))
                    addHeader(com.radiant.webtoon.core.image.SourceImageHeaders.SOURCE_ID_HEADER, effectiveSourceId)
                }
                when {
                    widthPx != null || heightPx != null -> {
                        val width = widthPx?.let { Dimension.Pixels(it) } ?: Dimension.Undefined
                        val height = heightPx?.let { Dimension.Pixels(it) } ?: Dimension.Undefined
                        size(width, height)
                    }
                    widthDp != null && heightDp != null -> size(widthDp, heightDp)
                }
            }
            .precision(precision)
            .scale(scale)
            .allowHardware(allowHardware)
            .crossfade(crossfade)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    if (evictFromMemoryOnDispose) {
        DisposableEffect(request) {
            onDispose { imageLoader.evictFromMemoryCache(request) }
        }
    }

    when {
        loading != null || error != null -> {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = contentDescription,
                imageLoader = imageLoader,
                modifier = modifier,
                contentScale = contentScale,
                loading = loading?.let { { it() } },
                error = error?.let { { it() } },
                success = { SubcomposeAsyncImageContent() },
            )
        }
        else -> {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                imageLoader = imageLoader,
                modifier = modifier,
                contentScale = contentScale,
            )
        }
    }
}

private fun lookupSource(context: android.content.Context, sourceId: String?): Source? {
    if (sourceId.isNullOrBlank()) return null
    return (context.applicationContext as? com.radiant.webtoon.WebtoonApplication)
        ?.extensionManager
        ?.getSource(sourceId)
}
