package com.radiant.webtoon.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.sql.DriverManager

class MangaMetadataMergeTest {
    @Test
    fun refreshPreservesFavoriteAndDateAdded() {
        val existing = Manga(
            url = "/series/demo",
            source = "a",
            title = "Old",
            favorite = true,
            date_added = 111L,
            thumbnail_url = "old.png",
        )
        val fetched = Manga(
            url = "/series/demo",
            source = "a",
            title = "New",
            favorite = false,
            date_added = 999L,
            thumbnail_url = "new.png",
            last_update = 50L,
            initialized = true,
        )
        val merged = MangaMetadataMerge.merge(existing, fetched)
        assertEquals("New", merged.title)
        assertEquals("new.png", merged.thumbnail_url)
        assertEquals(true, merged.favorite)
        assertEquals(111L, merged.date_added)
        assertEquals(50L, merged.last_update)
    }

    @Test
    fun sqliteRollbackLeavesPriorRow() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().execute(
                "CREATE TABLE mangas (url TEXT, source TEXT, title TEXT, favorite INTEGER, date_added INTEGER, PRIMARY KEY(url, source))",
            )
            conn.autoCommit = false
            conn.createStatement().execute(
                "INSERT INTO mangas VALUES ('/series/demo','a','Demo',1,111)",
            )
            conn.commit()
            conn.autoCommit = false
            try {
                conn.createStatement().execute(
                    "INSERT INTO mangas VALUES ('/series/other','a','Other',1,222)",
                )
                error("injected write failure")
            } catch (_: Throwable) {
                conn.rollback()
            }
            conn.autoCommit = true
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM mangas")
            rs.next()
            assertEquals(1, rs.getInt(1))
            rs.close()
            val title = conn.createStatement().executeQuery("SELECT title FROM mangas")
            title.next()
            assertEquals("Demo", title.getString(1))
            assertFalse(title.next())
            title.close()
        }
    }
}
