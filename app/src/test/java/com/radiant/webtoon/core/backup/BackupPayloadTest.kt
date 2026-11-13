package com.radiant.webtoon.core.backup

import com.google.gson.Gson
import com.radiant.webtoon.core.database.Category
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.database.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadTest {
    private val gson = Gson()

    @Test
    fun `backup payload stores expected counts`() {
        val payload = BackupPayload(
            version = 2,
            generatedAt = 1L,
            library = listOf(
                Manga(url = "id", source = "src", title = "Title", thumbnail_url = "thumb")
            ),
            downloads = listOf(
                DownloadTask(
                    chapterId = "ch",
                    sourceId = "src",
                    webtoonId = "id",
                    webtoonTitle = "Title",
                    chapterTitle = "Ch 1"
                )
            ),
            categories = listOf(Category(id = 1, name = "Default", order = 0)),
            categoryMappings = emptyList()
        )
        assertEquals(1, payload.library.size)
        assertEquals(1, payload.downloads.size)
        assertEquals(1, payload.categories.size)
        assertEquals(0, payload.history.size)
        assertEquals(0, payload.extensionRepos.size)
        assertEquals(0, payload.chapters.size)
    }

    @Test
    fun omittedListsFromLegacyJsonBecomeEmpty() {
        val json = """
            {"version":1,"generatedAt":1,"library":[{"url":"u","source":"s","title":"T"}],
             "downloads":[],"categories":[],"categoryMappings":[]}
        """.trimIndent()
        val parsed = BackupParser.parse(json, gson)
        assertEquals(1, parsed.library.size)
        assertTrue(parsed.history.isEmpty())
        assertTrue(parsed.downloadedChapters.isEmpty())
        assertTrue(parsed.chapters.isEmpty())
        assertTrue(parsed.sourcePreferences.isEmpty())
    }

    @Test
    fun explicitNullListIsEmptyNotCrash() {
        val json = """
            {"version":4,"generatedAt":1,"library":[],"downloads":[],"categories":[],
             "categoryMappings":[],"downloadedChapters":null,"history":null}
        """.trimIndent()
        val parsed = BackupParser.parse(json, gson)
        assertTrue(parsed.downloadedChapters.isEmpty())
        assertTrue(parsed.history.isEmpty())
    }

    @Test
    fun v5RoundTripKeepsChapterProgressAndTypedPref() {
        val payload = BackupPayload(
            version = 5,
            generatedAt = 2L,
            library = listOf(Manga(url = "/series/demo", source = "a", title = "Demo")),
            downloads = emptyList(),
            categories = emptyList(),
            categoryMappings = emptyList(),
            chapters = listOf(
                Chapter(
                    url = "/chapter/1",
                    manga_id = "/series/demo",
                    name = "1",
                    read = true,
                    bookmark = true,
                    last_page_read = 8,
                    source = "a",
                ),
            ),
            sourcePreferences = listOf(
                SourcePreferencesBackup(
                    sourceId = "a",
                    prefs = listOf(
                        TypedPreference("toggle", "boolean", boolValue = true),
                        TypedPreference("wide", "long", longValue = 3_000_000_000L),
                        TypedPreference("numericString", "string", stringValue = "42"),
                    ),
                ),
            ),
        )
        val parsed = BackupParser.parse(gson.toJson(payload), gson)
        assertEquals(true, parsed.chapters.single().bookmark)
        assertEquals(8, parsed.chapters.single().last_page_read)
        assertEquals("boolean", parsed.sourcePreferences.single().prefs[0].type)
        assertEquals(3_000_000_000L, parsed.sourcePreferences.single().prefs[1].longValue)
        assertEquals("42", parsed.sourcePreferences.single().prefs[2].stringValue)
    }

    @Test
    fun typedCodecKeepsNumericStringAsString() {
        val encoded = TypedPreferenceCodec.encode("id", "123")!!
        assertEquals("string", encoded.type)
        assertEquals("123", encoded.stringValue)
    }

    @Test
    fun longGreaterThanIntIsUnrestorableAsInt() {
        val write = TypedPreferenceCodec.decodeWrite(
            TypedPreference("wide", "int", longValue = 3_000_000_000L),
        )
        assertTrue(write is TypedPreferenceCodec.Write.Unrestorable)
    }

    @Test
    fun longTypeStaysLong() {
        val write = TypedPreferenceCodec.decodeWrite(
            TypedPreference("wide", "long", longValue = 3_000_000_000L),
        )
        assertEquals(3_000_000_000L, (write as TypedPreferenceCodec.Write.LongVal).value)
    }
}
