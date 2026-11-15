package com.radiant.webtoon.core.image

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/** Disk-first image cache sizing for webtoon pages and cover thumbnails. */
object RadiantImageLoader {
    const val MEMORY_CACHE_MAX_SIZE_PERCENT = 0.10
    const val DISK_CACHE_MAX_SIZE_BYTES = 256L * 1024 * 1024

    fun diskCacheDir(context: Context) = context.cacheDir.resolve("image_cache")

    fun build(
        context: Context,
        okHttpClient: OkHttpClient,
        configure: ImageLoader.Builder.() -> Unit = {},
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            // Ignore CDN Cache-Control (no-store, max-age=0) so covers stay on disk for Library/History.
            .respectCacheHeaders(false)
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(3))
            .decoderDispatcher(Dispatchers.Default.limitedParallelism(3))
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(MEMORY_CACHE_MAX_SIZE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDir(context))
                    .maxSizeBytes(DISK_CACHE_MAX_SIZE_BYTES)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .apply(configure)
            .build()
    }
}

/** Remove a decoded bitmap from memory while leaving the disk entry intact. */
fun ImageLoader.evictFromMemoryCache(request: ImageRequest) {
    request.memoryCacheKey?.let { key -> memoryCache?.remove(key) }
}
