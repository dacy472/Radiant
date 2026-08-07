package com.radiant.webtoon.core.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes and coalesces in-flight [getMangaUpdate] work per manga so concurrent callers
 * (e.g. parallel detail + chapter fetches) do not mutate the same extension state at once.
 */
object MangaUpdateLocks {

    private val mutexes = ConcurrentHashMap<Long, Mutex>()
    private val inFlight = ConcurrentHashMap<Long, CompletableDeferred<Any?>>()

    fun lockKey(
        sourceId: Long,
        mangaUrl: String,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): Long {
        var hash = sourceId
        hash = 31 * hash + mangaUrl.hashCode()
        hash = 31 * hash + fetchDetails.hashCode()
        hash = 31 * hash + fetchChapters.hashCode()
        return hash.toLong()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> coalesce(
        sourceId: Long,
        mangaUrl: String,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        block: suspend () -> T,
    ): T {
        val key = lockKey(sourceId, mangaUrl, fetchDetails, fetchChapters)

        inFlight[key]?.let { existing ->
            if (existing.isActive) {
                return existing.await() as T
            }
        }

        val mutex = mutexes.getOrPut(key) { Mutex() }
        return mutex.withLock {
            inFlight[key]?.let { existing ->
                if (existing.isActive) {
                    return@withLock existing.await() as T
                }
            }

            val deferred = CompletableDeferred<Any?>()
            inFlight[key] = deferred
            try {
                val result = block()
                deferred.complete(result)
                result
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(key, deferred)
            }
        }
    }
}
