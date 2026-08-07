package com.radiant.webtoon.core.repository

import com.radiant.webtoon.core.concurrent.AppWorkQueues
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import androidx.room.withTransaction

/**
 * Repository that delegates all source operations to locally loaded extensions
 */
class WebtoonRepository(
    val appContext: android.content.Context,
    val extensionManager: ExtensionManager,
    val mangaDao: MangaDao,
    val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val downloadDao: DownloadDao,
    private val downloadedChapterDao: DownloadedChapterDao,
    val database: WebtoonDatabase,
) {
    private val categoryDao: CategoryDao get() = database.categoryDao()

    companion object {
        /** Must exceed Cloudflare bypass wait (~20s) + HTTP retry (~30s). */
        private const val SEARCH_TIMEOUT_MS = 90_000L

        /** Detail/chapter fetch — shorter than search since only one source is queried. */
        private const val DETAIL_TIMEOUT_MS = 60_000L

        /** Max parallel source searches during global search. */
        const val GLOBAL_SEARCH_CONCURRENCY = 4
    }

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
     * Fan-out search across sources with bounded concurrency ([AppWorkQueues.sourceNetwork]).
     * Prefer [searchAllStreaming] for UI.
     */
    suspend fun searchAll(query: String): GlobalSearchResponse = supervisorScope {
        val sources = extensionManager.getAllSources().filter { it.isPinned }
        val results = sources
            .chunked(GLOBAL_SEARCH_CONCURRENCY)
            .flatMap { batch ->
                batch.map { source ->
                    async(Dispatchers.IO) { searchSource(source, query) }
                }.awaitAll()
            }

        GlobalSearchResponse(results)
    }

    /**
     * Streaming global search — queries [GLOBAL_SEARCH_CONCURRENCY] pinned sources at a time.
     */
    fun searchAllStreaming(
        query: String,
        targetSources: List<Source> = extensionManager.getAllSources().filter { it.isPinned },
    ): Flow<SourceSearchResult> = kotlinx.coroutines.flow.channelFlow {
        if (targetSources.isEmpty()) return@channelFlow

        val semaphore = kotlinx.coroutines.sync.Semaphore(GLOBAL_SEARCH_CONCURRENCY)
        supervisorScope {
            targetSources.map { source ->
                launch(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        send(searchSource(source, query))
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
    }

    private suspend fun searchSource(source: com.radiant.webtoon.core.source.Source, query: String, page: Int = 1): SourceSearchResult {
        return try {
            val response = withTimeout(SEARCH_TIMEOUT_MS) {
                val mangasPage = source.fetchSearchManga(page, query, FilterList())
                PagedWebtoonResponse(
                    webtoons = mangasPage.mangas.map { it.toWebtoon() },
                    hasNextPage = mangasPage.hasNextPage
                )
            }
            SourceSearchResult(
                source.id, source.name, source.icon, source.lang, response.webtoons, null,
                hasNextPage = response.hasNextPage,
                currentPage = page,
                status = SourceSearchStatus.Success,
            )
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("GlobalSearch", "Timed out after ${SEARCH_TIMEOUT_MS / 1000}s: ${source.name}")
            SourceSearchResult(
                source.id, source.name, source.icon, source.lang, emptyList(),
                "Timed out after ${SEARCH_TIMEOUT_MS / 1000}s",
                status = SourceSearchStatus.Error,
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("GlobalSearch", "Search failed for ${source.name}", e)
            val message = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            SourceSearchResult(
                source.id, source.name, source.icon, source.lang, emptyList(), message,
                status = SourceSearchStatus.Error,
            )
        }
    }

    suspend fun searchSourceNextPage(sourceId: String, query: String, page: Int): SourceSearchResult? {
        val source = extensionManager.getSource(sourceId) ?: return null
        return AppWorkQueues.withSourceNetwork { searchSource(source, query, page) }
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

    suspend fun getWebtoonDetailAndChapters(sourceId: String, webtoonId: String): Pair<Webtoon, List<com.radiant.webtoon.core.model.Chapter>> =
        withTimeout(DETAIL_TIMEOUT_MS) {
            AppWorkQueues.withSourceNetwork {
                if (sourceId == com.radiant.webtoon.core.local.LocalSourceIds.LOCAL_SOURCE_ID) {
                    refreshLocalLibrary()
                }
                val source = extensionManager.getSource(sourceId)
                    ?: throw IllegalArgumentException("Source '$sourceId' not found.")
                // Single combined fetch — avoids concurrent getMangaUpdate on the same manga.
                val detail = source.getWebtoonDetail(webtoonId)
                Pair(detail, detail.chapters)
            }
        }

    suspend fun getChapterPages(sourceId: String, chapterId: String): List<String> =
        getChapterPagesResult(sourceId, chapterId).pages

    suspend fun getChapterPagesResult(sourceId: String, chapterId: String): com.radiant.webtoon.core.model.ChapterPagesResult =
        withContext(Dispatchers.IO) {
            AppWorkQueues.withSourceNetwork {
                val source = extensionManager.getSource(sourceId)
                    ?: throw IllegalArgumentException("Source '$sourceId' not found.")
                try {
                    source.getChapterPagesResult(chapterId)
                } catch (e: Exception) {
                    android.util.Log.e("RADIANT_ERROR", "Failed to fetch pages for $chapterId", e)
                    throw e
                }
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

    suspend fun getHistory(): List<History> = withContext(Dispatchers.IO) {
        historyDao.getAll()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.deleteAll()
    }

    // ─── Categories ───

    suspend fun getCategories(): List<Category> = withContext(Dispatchers.IO) {
        categoryDao.getAll()
    }

    suspend fun insertCategory(category: Category) = withContext(Dispatchers.IO) {
        categoryDao.insert(category)
    }

    suspend fun deleteCategory(category: Category) = withContext(Dispatchers.IO) {
        categoryDao.delete(category)
    }

    suspend fun getCategoryMappings(webtoonId: String, sourceId: String): List<LibraryCategoryCrossRef> =
        withContext(Dispatchers.IO) {
            categoryDao.getMappingsForWebtoon(webtoonId, sourceId)
        }

    suspend fun assignMangaToCategory(webtoonId: String, sourceId: String, categoryId: Int) =
        withContext(Dispatchers.IO) {
            categoryDao.addMapping(
                LibraryCategoryCrossRef(
                    webtoonId = webtoonId,
                    sourceId = sourceId,
                    categoryId = categoryId
                )
            )
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

    suspend fun markChapterReadLog(history: History, skipHistory: Boolean = false) = withContext(Dispatchers.IO) {
        chapterDao.updateRead(history.chapter_id, history.webtoon_id, true)
        if (!skipHistory) {
            historyDao.insert(history)
        }
    }

    suspend fun logReadingHistory(history: History, skipHistory: Boolean = false) = withContext(Dispatchers.IO) {
        if (!skipHistory) {
            historyDao.insert(history)
        }
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
    suspend fun deleteDownload(chapterId: String, sourceId: String) = withContext(Dispatchers.IO) {
        downloadDao.delete(chapterId, sourceId)
        downloadedChapterDao.delete(sourceId, chapterId)
    }
    suspend fun getDownloadTask(chapterId: String, sourceId: String) = withContext(Dispatchers.IO) { downloadDao.getByChapter(chapterId, sourceId) }

    suspend fun isChapterDownloaded(sourceId: String, chapterId: String): Boolean =
        withContext(Dispatchers.IO) { downloadedChapterDao.isChapterDownloaded(sourceId, chapterId) }

    suspend fun getDownloadedChapter(sourceId: String, chapterId: String): DownloadedChapter? =
        withContext(Dispatchers.IO) { downloadedChapterDao.getByChapter(sourceId, chapterId) }

    suspend fun getDownloadedChapterIds(sourceId: String, webtoonId: String): List<String> =
        withContext(Dispatchers.IO) { downloadedChapterDao.getDownloadedChapterIds(sourceId, webtoonId) }

    fun downloadedChaptersFlow(): Flow<List<DownloadedChapter>> = downloadedChapterDao.getAllFlow()

    fun downloadedChaptersForWebtoonFlow(sourceId: String, webtoonId: String) =
        downloadedChapterDao.getByWebtoonFlow(sourceId, webtoonId)

    suspend fun getDownloadedWebtoonKeys(): Set<String> = withContext(Dispatchers.IO) {
        downloadedChapterDao.getDownloadedWebtoonKeys().toSet()
    }

    suspend fun registerDownloadedChapter(
        sourceId: String,
        chapterId: String,
        webtoonId: String,
        webtoonTitle: String,
        chapterTitle: String,
        sourceName: String,
        localDirectory: String,
        pageCount: Int,
        fileSizeBytes: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        downloadedChapterDao.insert(
            DownloadedChapter(
                sourceId = sourceId,
                chapterId = chapterId,
                webtoonId = webtoonId,
                webtoonTitle = webtoonTitle,
                chapterTitle = chapterTitle,
                sourceName = sourceName,
                localDirectory = localDirectory,
                pageCount = pageCount,
                fileSizeBytes = fileSizeBytes,
            ),
        )
    }

    suspend fun getAllDownloadedChapters(): List<DownloadedChapter> =
        withContext(Dispatchers.IO) { downloadedChapterDao.getAll() }

    /** Deletes a completed chapter's on-disk files (SAF URI or plain file tree) plus its DB rows. */
    suspend fun deleteDownloadedChapter(chapterId: String, sourceId: String) = withContext(Dispatchers.IO) {
        val indexed = downloadedChapterDao.getByChapter(sourceId, chapterId)
        val task = downloadDao.getByChapter(chapterId, sourceId)
        val localPath = indexed?.localDirectory ?: task?.localPath
        if (localPath != null) {
            deleteDownloadFilesAtPath(localPath)
        } else if (task != null) {
            deleteDownloadFiles(task)
        }
        downloadDao.delete(chapterId, sourceId)
        downloadedChapterDao.delete(sourceId, chapterId)
    }

    /** Batch form of [deleteDownloadedChapter] for multi-select delete. */
    suspend fun deleteDownloadedChapters(chapters: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        for ((chapterId, sourceId) in chapters) {
            deleteDownloadedChapter(chapterId, sourceId)
        }
    }

    /** Deletes the on-disk file(s) for a [DownloadTask], without touching its DB row. */
    fun deleteDownloadFiles(task: DownloadTask) {
        val path = task.localPath ?: return
        deleteDownloadFilesAtPath(path)
    }

    private fun deleteDownloadFilesAtPath(path: String) {
        try {
            if (path.startsWith("content://")) {
                androidx.documentfile.provider.DocumentFile.fromSingleUri(appContext, android.net.Uri.parse(path))
                    ?.delete()
            } else {
                val file = java.io.File(path)
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebtoonRepository", "Failed to delete download files at $path", e)
        }
    }

    suspend fun refreshLocalLibrary() = withContext(Dispatchers.IO) {
        val app = appContext.applicationContext as? com.radiant.webtoon.WebtoonApplication
            ?: return@withContext
        app.localLibraryManager.refresh()
    }

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
