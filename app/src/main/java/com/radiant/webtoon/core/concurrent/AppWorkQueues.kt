package com.radiant.webtoon.core.concurrent

import kotlinx.coroutines.sync.Semaphore

/**
 * Named, bounded work queues so heavy operations run in controlled order
 * instead of unbounded parallel storms (Dex load, multi-source search, etc.).
 */
object AppWorkQueues {

    /** DexClassLoader + source instantiation — max 2 at a time. */
    val extensionLoad = Semaphore(2)

    /** Source HTTP (search / library update / detail) — max 2 at a time. */
    val sourceNetwork = Semaphore(2)

    /**
     * Image loads (covers + reader pages) — a handful in flight at once instead of the
     * old raw global mutex (1 at a time) or fully unbounded parallelism.
     */
    val imageFetch = Semaphore(3)

    suspend inline fun <T> withExtensionLoad(crossinline action: suspend () -> T): T {
        extensionLoad.acquire()
        try {
            return action()
        } finally {
            extensionLoad.release()
        }
    }

    suspend inline fun <T> withSourceNetwork(crossinline action: suspend () -> T): T {
        sourceNetwork.acquire()
        try {
            return action()
        } finally {
            sourceNetwork.release()
        }
    }

    suspend inline fun <T> withImageFetch(crossinline action: suspend () -> T): T {
        imageFetch.acquire()
        try {
            return action()
        } finally {
            imageFetch.release()
        }
    }
}
