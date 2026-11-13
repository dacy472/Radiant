package com.radiant.webtoon.core.domain.usecase

import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.History
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.database.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Exercises the migration rules against an in-memory [MigrationStore]. Room's real
 * `withTransaction` cannot run on the JVM here (no Room/Robolectric harness; the existing database
 * tests use plain sqlite-jdbc), so the fake emulates atomicity by snapshotting and restoring state
 * when the block throws — that verifies the use case relies on a single transaction and propagates
 * failures, not Room's own rollback.
 */
class MigrateWebtoonUseCaseTest {

    private val old = MangaIdentifier("/old-series", "src-old")
    private val new = MangaIdentifier("/new-series", "src-new")

    private class FakeStore : MigrationStore {
        val mangas = mutableMapOf<Pair<String, String>, Manga>()
        var chapters = mutableListOf<Chapter>()
        var history = mutableListOf<History>()
        val categories = mutableMapOf<Pair<String, String>, List<Int>>()
        var tracks = mutableListOf<Track>()
        var failOnFavoriteFor: Pair<String, String>? = null
        var transactions = 0

        private data class Snapshot(
            val mangas: Map<Pair<String, String>, Manga>,
            val chapters: List<Chapter>,
            val history: List<History>,
            val categories: Map<Pair<String, String>, List<Int>>,
            val tracks: List<Track>,
        )

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            transactions++
            val snap = Snapshot(mangas.toMap(), chapters.toList(), history.toList(), categories.toMap(), tracks.toList())
            try {
                return block()
            } catch (t: Throwable) {
                mangas.clear(); mangas.putAll(snap.mangas)
                chapters = snap.chapters.toMutableList()
                history = snap.history.toMutableList()
                categories.clear(); categories.putAll(snap.categories)
                tracks = snap.tracks.toMutableList()
                throw t
            }
        }

        override suspend fun getManga(url: String, sourceId: String) = mangas[url to sourceId]
        override suspend fun getChapters(mangaUrl: String, sourceId: String) =
            chapters.filter { it.manga_id == mangaUrl && it.source == sourceId }

        override suspend fun updateChapters(chapters: List<Chapter>) {
            chapters.forEach { upd ->
                val i = this.chapters.indexOfFirst { it.url == upd.url && it.manga_id == upd.manga_id && it.source == upd.source }
                check(i >= 0) { "update of missing chapter ${upd.url}" }
                this.chapters[i] = upd
            }
        }

        override suspend fun getHistory(mangaUrl: String, sourceId: String) =
            history.filter { it.webtoon_id == mangaUrl && it.source_id == sourceId }

        override suspend fun insertHistory(entries: List<History>) {
            entries.forEach { e ->
                history.removeAll { it.chapter_id == e.chapter_id && it.webtoon_id == e.webtoon_id && it.source_id == e.source_id }
                history += e
            }
        }

        override suspend fun getCategoryIds(mangaUrl: String, sourceId: String) =
            categories[mangaUrl to sourceId].orEmpty()

        override suspend fun replaceCategories(mangaUrl: String, sourceId: String, categoryIds: List<Int>) {
            categories[mangaUrl to sourceId] = categoryIds.distinct()
        }

        override suspend fun getTracks(mangaUrl: String, sourceId: String) =
            tracks.filter { it.mangaUrl == mangaUrl && it.mangaSource == sourceId }

        override suspend fun insertTracks(tracks: List<Track>) {
            tracks.forEach { t ->
                this.tracks.removeAll { it.mangaUrl == t.mangaUrl && it.mangaSource == t.mangaSource && it.trackerId == t.trackerId }
                this.tracks += t
            }
        }

        override suspend fun setFavorite(mangaUrl: String, sourceId: String, favorite: Boolean, dateAdded: Long) {
            if (failOnFavoriteFor == (mangaUrl to sourceId)) throw IllegalStateException("boom")
            val key = mangaUrl to sourceId
            mangas[key] = mangas.getValue(key).copy(favorite = favorite, date_added = dateAdded)
        }
    }

    private fun seeded(): FakeStore = FakeStore().apply {
        mangas[old.url to old.sourceId] = Manga(old.url, old.sourceId, "Old Title", favorite = true, date_added = 1000L)
        mangas[new.url to new.sourceId] = Manga(new.url, new.sourceId, "New Title", thumbnail_url = "thumb", favorite = false)
        chapters += listOf(
            Chapter("o1", old.url, "Chapter 1", read = true, bookmark = true, last_page_read = 5, date_fetch = 11, chapter_number = 1f, source = old.sourceId),
            Chapter("o2", old.url, "Chapter 2", read = true, chapter_number = 2f, source = old.sourceId),
            Chapter("o9", old.url, "Chapter 9", chapter_number = 9f, source = old.sourceId),
            Chapter("n1", new.url, "Ep 1", chapter_number = 1f, source = new.sourceId),
            Chapter("n2", new.url, "Ep 2", chapter_number = 2f, source = new.sourceId),
            Chapter("n3", new.url, "Ep 3", chapter_number = 3f, source = new.sourceId),
        )
        history += History("o1", old.url, old.sourceId, "Chapter 1", "Old Title", null, read_at = 777L)
        categories[old.url to old.sourceId] = listOf(1, 2, 2)
        categories[new.url to new.sourceId] = listOf(7)
        tracks += Track(old.url, old.sourceId, "anilist", "42", "Old Title", 2.0, "READING")
    }

    @Test
    fun copiesProgressAndFlipsFavorites() = runBlocking {
        val store = seeded()
        val result = MigrateWebtoonUseCase(store)(old, new)

        assertEquals(MigrationResult(matchedCount = 2, totalOld = 3, totalNew = 3, unmatchedOld = 1), result)
        assertEquals(1, store.transactions)

        val n1 = store.chapters.first { it.url == "n1" }
        assertTrue(n1.read); assertTrue(n1.bookmark); assertEquals(5, n1.last_page_read); assertEquals(11L, n1.date_fetch)
        assertEquals("Ep 1", n1.name)
        assertTrue(store.chapters.first { it.url == "n2" }.read)
        val n3 = store.chapters.first { it.url == "n3" }
        assertFalse(n3.read) // unmatched new chapter untouched

        val newManga = store.mangas.getValue(new.url to new.sourceId)
        assertTrue(newManga.favorite)
        assertEquals(1000L, newManga.date_added)
        val oldManga = store.mangas.getValue(old.url to old.sourceId)
        assertFalse(oldManga.favorite)
        // old rows are kept (no hard delete)
        assertEquals(3, store.getChapters(old.url, old.sourceId).size)
    }

    @Test
    fun replacesNewCategoriesWithOldWithoutDuplicatesAndClearsOld() = runBlocking {
        val store = seeded()
        MigrateWebtoonUseCase(store)(old, new)

        assertEquals(listOf(1, 2), store.categories[new.url to new.sourceId])
        assertEquals(emptyList<Int>(), store.categories[old.url to old.sourceId])
    }

    @Test
    fun copiesHistoryForMatchedChaptersAndTracking() = runBlocking {
        val store = seeded()
        MigrateWebtoonUseCase(store)(old, new)

        val copied = store.history.single { it.source_id == new.sourceId }
        assertEquals("n1", copied.chapter_id)
        assertEquals(new.url, copied.webtoon_id)
        assertEquals("Ep 1", copied.chapter_title)
        assertEquals("New Title", copied.webtoon_title)
        assertEquals("thumb", copied.webtoon_thumbnail)
        assertEquals(777L, copied.read_at)

        val track = store.tracks.single { it.mangaSource == new.sourceId }
        assertEquals(new.url, track.mangaUrl)
        assertEquals("42", track.remoteId)
    }

    @Test
    fun optionsCanKeepOldEntryAndSkipCopies() = runBlocking {
        val store = seeded()
        MigrateWebtoonUseCase(store)(
            old, new,
            MigrationOptions(copyChapterProgress = false, copyHistory = false, copyCategories = false,
                copyTracking = false, removeOldFromLibrary = false),
        )
        assertFalse(store.chapters.first { it.url == "n1" }.read)
        assertEquals(listOf(7), store.categories[new.url to new.sourceId])
        assertTrue(store.history.none { it.source_id == new.sourceId })
        assertTrue(store.tracks.none { it.mangaSource == new.sourceId })
        assertTrue(store.mangas.getValue(old.url to old.sourceId).favorite)
        assertEquals(listOf(1, 2, 2), store.categories[old.url to old.sourceId]) // untouched
        assertTrue(store.mangas.getValue(new.url to new.sourceId).favorite)
    }

    @Test
    fun failureRollsEverythingBack() = runBlocking {
        val store = seeded()
        store.failOnFavoriteFor = old.url to old.sourceId // fails at the very last step
        val before = store.chapters.toList()

        try {
            MigrateWebtoonUseCase(store)(old, new)
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }

        assertEquals(before, store.chapters)
        assertFalse(store.mangas.getValue(new.url to new.sourceId).favorite)
        assertTrue(store.mangas.getValue(old.url to old.sourceId).favorite)
        assertEquals(listOf(7), store.categories[new.url to new.sourceId])
        assertEquals(listOf(1, 2, 2), store.categories[old.url to old.sourceId])
        assertTrue(store.history.none { it.source_id == new.sourceId })
        assertTrue(store.tracks.none { it.mangaSource == new.sourceId })
    }

    @Test
    fun missingEntriesAndSelfMigrationAreRejected() = runBlocking {
        val store = seeded()
        store.mangas.remove(new.url to new.sourceId)
        try {
            MigrateWebtoonUseCase(store)(old, new); fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("New webtoon not found"))
        }
        try {
            MigrateWebtoonUseCase(store)(old, old); fail("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertTrue(store.mangas.getValue(old.url to old.sourceId).favorite)
    }
}
