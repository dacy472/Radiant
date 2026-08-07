package com.radiant.webtoon.core.source

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MangaUpdateLocksTest {

    @Test
    fun coalesceRunsConcurrentCallersOnce() = runBlocking {
        val executions = AtomicInteger(0)

        coroutineScope {
            val first = async {
                MangaUpdateLocks.coalesce(
                    sourceId = 42L,
                    mangaUrl = "/manga/test",
                    fetchDetails = true,
                    fetchChapters = true,
                ) {
                    delay(50)
                    executions.incrementAndGet()
                    "ok"
                }
            }
            val second = async {
                delay(10)
                MangaUpdateLocks.coalesce(
                    sourceId = 42L,
                    mangaUrl = "/manga/test",
                    fetchDetails = true,
                    fetchChapters = true,
                ) {
                    executions.incrementAndGet()
                    "ok"
                }
            }

            assertEquals("ok", first.await())
            assertEquals("ok", second.await())
        }

        assertEquals(1, executions.get())
    }

    @Test
    fun coalesceUsesDifferentKeysForDifferentFetchModes() = runBlocking {
        val executions = AtomicInteger(0)

        coroutineScope {
            val details = async {
                MangaUpdateLocks.coalesce(1L, "/manga/a", fetchDetails = true, fetchChapters = false) {
                    executions.incrementAndGet()
                    "details"
                }
            }
            val chapters = async {
                MangaUpdateLocks.coalesce(1L, "/manga/a", fetchDetails = false, fetchChapters = true) {
                    executions.incrementAndGet()
                    "chapters"
                }
            }

            assertEquals("details", details.await())
            assertEquals("chapters", chapters.await())
        }

        assertEquals(2, executions.get())
    }
}
