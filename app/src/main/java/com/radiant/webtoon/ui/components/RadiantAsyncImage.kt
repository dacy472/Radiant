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
import com.radiant.webtoon.core.image.evictFromMemoryCache

/**
 * Disk-prioritized Coil image with aggressive in-memory eviction when the composable
 * leaves composition (e.g. scrolls off a virtualized reader or library grid).
 */
@Composable
fun RadiantAsyncImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
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

    val request = remember(url, headers, widthPx, heightPx, widthDp, heightDp, precision, scale, allowHardware, crossfade) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
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
