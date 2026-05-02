package com.radiant.webtoon.core.repository

import com.radiant.webtoon.core.database.*
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.model.*
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import androidx.room.withTransaction

/**
 * Repository that delegates all source operations to locally loaded extensions
 */
class WebtoonRepository(
    val extensionManager: ExtensionManager,
    val mangaDao: MangaDao,
    val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val downloadDao: DownloadDao,
    private val database: WebtoonDatabase
) {
    private val globalSearchSemaphore = Semaphore(5)

    // ─── Source operations (delegated to local Source instances) ───

    suspend fun getPopular(sourceId: String, page: Int): PagedWebtoonResponse = withContext(Dispatchers.IO) {
        val source = extensionManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source '$sourceId' not found.")
        source.fetchPopularManga(page)
    }

    suspend fun search(sourceId: String, query: String, page: Int): PagedWebtoonResponse = withContext(Dispatchers.IO) {
        val source = extensionManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source '$sourceId' not found.")
        val mangasPage = source.fetchSearchManga(page, query, FilterList())
        PagedWebtoonResponse(
            webtoons = mangasPage.mangas.map { it.toWebtoon() },
            hasNextPage = mangasPage.hasNextPage
        )
    }

    /**
     * Fan-out search across ALL installed sources in parallel.
     */
    suspend fun searchAll(query: String): GlobalSearchResponse = supervisorScope {
        val sources = extensionManager.getAllSources()
        val results = sources.map { source ->
            async(Dispatchers.IO) {
                searchSource(source, query)
            }
        }.awaitAll()

        GlobalSearchResponse(results)
    }

    /**
     * Streaming version of searchAll.
     */
    fun searchAllStreaming(query: String, targetSources: List<Source> = extensionManager.getAllSources()): kotlinx.coroutines.flow.Flow<SourceSearchResult> = kotlinx.coroutines.flow.channelFlow {
        val sources = targetSources
        
        // Wrap in supervisorScope so one source failure doesn't cancel others,
        // and provides a scope for 'launch'
        supervisorScope {
            sources.forEach { source ->
                launch(Dispatchers.IO) {
                    globalSearchSemaphore.withPermit {
                        val result = searchSource(source, query)
                        send(result)
                    }
                }
            }
        }
    }

    private suspend fun searchSource(source: com.radiant.webtoon.core.source.Source, query: String): SourceSearchResult {
        return try {
            val response = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                try {
                    val mangasPage = source.fetchSearchManga(1, query, FilterList())
                    PagedWebtoonResponse(
                        webtoons = mangasPage.mangas.map { it.toWebtoon() },
                        hasNextPage = mangasPage.hasNextPage
                    )
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }
            if (response == null) {
                SourceSearchResult(source.id, source.name, source.icon, source.lang, emptyList(), "Timeout")
            } else {
                SourceSearchResult(source.id, source.name, source.icon, source.lang, response.webtoons, null)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SourceSearchResult(source.id, source.name, source.icon, source.lang, emptyList(), e.localizedMessage)
        }
    }

    suspend fun getWebtoonDetail(sourceId: String, webtoonId: String): Webtoon = withContext(Dispatchers.IO) {
        val source = extensionManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source '$sourceId' not found.")
        try {
            source.getWebtoonDetail(webtoonId)
        } catch (e: Exception) {
            android.util.Log.e("RADIANT_ERROR", "Failed to fetch $webtoonId from $sourceId", e)
            throw e
        }
    }

    /**
     * Fetch manga details and chapter list concurrently.
     */
    suspend fun getWebtoonDetailAndChapters(sourceId: String, webtoonId: String): Pair<Webtoon, List<com.radiant.webtoon.core.model.Chapter>> = coroutineScope {
        val source = extensionManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source '$sourceId' not found.")

        val detailDeferred = async(Dispatchers.IO) { source.getMangaDetails(webtoonId) }
        val chaptersDeferred = async(Dispatchers.IO) { source.getChapterList(webtoonId) }
        
        val detail = detailDeferred.await()
        val chapters = chaptersDeferred.await()
        
        Pair(detail, chapters)
    }

    suspend fun getChapterPages(sourceId: String, chapterId: String): List<String> = withContext(Dispatchers.IO) {
        val source = extensionManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source '$sourceId' not found.")
        try {
            source.getChapterPages(chapterId)
        } catch (e: Exception) {
            android.util.Log.e("RADIANT_ERROR", "Failed to fetch pages for $chapterId", e)
            throw e
        }
    }

    // ─── Manga / Library operations (local Room DB) ───

    suspend fun getLibrary(): List<Manga> = withContext(Dispatchers.IO) { 
        mangaDao.getFavorites() 
    }

    suspend fun addToLibrary(webtoon: Webtoon, sourceId: String) = withContext(Dispatchers.IO) {
        val manga = Manga(
            url = webtoon.id,
            source = sourceId,
            title = webtoon.title,
            thumbnail_url = webtoon.thumbnail,
            status = webtoon.status,
            author = webtoon.author,
            favorite = true,
            date_added = System.currentTimeMillis()
        )
        mangaDao.insert(manga)
    }

    suspend fun removeFromLibrary(url: String, source: String) = withContext(Dispatchers.IO) {
        mangaDao.updateFavorite(url, source, false, 0)
    }

    suspend fun isInLibrary(url: String, source: String): Boolean = withContext(Dispatchers.IO) {
        mangaDao.getManga(url, source)?.favorite == true
    }

    fun getHistoryFlow(): Flow<List<History>> = historyDao.getAllFlow()

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.deleteAll()
    }

    // ─── Chapter tracking operations ───

    suspend fun getChaptersForManga(mangaId: String): List<Chapter> = withContext(Dispatchers.IO) {
        chapterDao.getChapters(mangaId)
    }

    suspend fun insertChaptersList(chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        chapterDao.insertAll(chapters)
    }

    suspend fun saveChaptersToDb(chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        chapterDao.insertAll(chapters)
    }

    suspend fun markChapterReadLog(history: History) = withContext(Dispatchers.IO) {
        chapterDao.updateRead(history.chapter_id, history.webtoon_id, true)
        historyDao.insert(history)
    }

    suspend fun markChapterRead(url: String, mangaId: String, isRead: Boolean = true) = withContext(Dispatchers.IO) {
        chapterDao.updateRead(url, mangaId, isRead)
        if (!isRead) {
            historyDao.delete(url)
        }
    }

    fun getRecentUpdates(): Flow<List<UpdateItem>> {
        return chapterDao.getRecentUpdates()
    }

    suspend fun updateLastPageRead(url: String, mangaId: String, page: Int) = withContext(Dispatchers.IO) {
        chapterDao.updateLastPageRead(url, mangaId, page)
    }

    /**
     * Atomically migrates a manga from one source/URL to another.
     */
    suspend fun executeMigration(
        oldUrl: String,
        oldSource: String,
        newUrl: String,
        newSource: String,
        newThumbnail: String,
        newChapters: List<Chapter>
    ) {
        database.withTransaction {
            // 1. Delete old chapters
            chapterDao.deleteByManga(oldUrl)
            
            // 2. Insert new synced chapters
            chapterDao.insertAll(newChapters)
            
            // 3. Update the Manga pointer (URL, Source, Thumbnail)
            mangaDao.updateMangaMigrationData(oldUrl, oldSource, newSource, newUrl, newThumbnail)
        }
    }

    // ─── Download operations (local Room DB) ───

    suspend fun getDownloads(): List<DownloadTask> = withContext(Dispatchers.IO) { downloadDao.getAll() }
    suspend fun getActiveDownloads(): List<DownloadTask> = withContext(Dispatchers.IO) { downloadDao.getActiveQueue() }
    suspend fun getNextPendingDownload(): DownloadTask? = withContext(Dispatchers.IO) { downloadDao.getNextPending() }
    suspend fun addDownload(task: DownloadTask) = withContext(Dispatchers.IO) { downloadDao.insert(task) }
    suspend fun updateDownload(task: DownloadTask) = withContext(Dispatchers.IO) { downloadDao.update(task) }
    suspend fun deleteDownload(chapterId: String, sourceId: String) = withContext(Dispatchers.IO) { downloadDao.delete(chapterId, sourceId) }
    suspend fun getDownloadTask(chapterId: String, sourceId: String) = withContext(Dispatchers.IO) { downloadDao.getByChapter(chapterId, sourceId) }

    private fun SManga.toWebtoon(): Webtoon {
        return Webtoon(
            id = url,
            title = title,
            thumbnail = thumbnail_url ?: "",
            url = url,
            status = statusToString(status),
            author = author,
            description = description,
            genres = genre?.split(", ")?.map { it.trim() } ?: emptyList()
        )
    }

    private fun statusToString(status: Int): String {
        return when (status) {
            SManga.ONGOING -> "Ongoing"
            SManga.COMPLETED -> "Completed"
            SManga.LICENSED -> "Licensed"
            SManga.PUBLISHING_FINISHED -> "Publishing Finished"
            SManga.CANCELLED -> "Cancelled"
            SManga.ON_HIATUS -> "On Hiatus"
            else -> "Unknown"
        }
    }
}
