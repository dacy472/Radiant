package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rx.Observable
import java.util.concurrent.ConcurrentHashMap

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface Source {

    /**
     * Id for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Get the updated details for a manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return fetchMangaDetails(manga).toBlocking().first()
    }

    /**
     * Get all the available chapters for a manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return fetchChapterList(manga).toBlocking().first()
    }

    /**
     * Get the list of pages a chapter has.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).toBlocking().first()
    }

    /**
     * Modern combined suspend contract (mirrors current Mihon/Keiyoushi extensions-lib). Some
     * newer extensions fetch manga details and chapters together and override this directly
     * instead of the separate [getMangaDetails]/[getChapterList] methods above.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mutex = mangaUpdateMutexes.getOrPut(mangaUpdateLockKey(id, manga.url)) { Mutex() }
        return mutex.withLock {
            val updatedManga = if (fetchDetails) getMangaDetails(manga) else manga
            val updatedChapters = if (fetchChapters) getChapterList(manga) else chapters
            SMangaUpdate(updatedManga, updatedChapters)
        }
    }

    companion object {
        private val mangaUpdateMutexes = ConcurrentHashMap<Long, Mutex>()

        private fun mangaUpdateLockKey(sourceId: Long, mangaUrl: String): Long {
            var hash = sourceId
            hash = 31 * hash + mangaUrl.hashCode()
            return hash.toLong()
        }
    }

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getMangaDetails"))
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw Exception("Not implemented")

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getChapterList"))
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw Exception("Not implemented")

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPageList"))
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw Exception("Not implemented")
}
