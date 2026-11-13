package com.radiant.webtoon.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class ChapterHistoryIdentityMigrationTest {

    @Test
    fun v13MigrationPreservesFavoriteChapterAndCategory() {
        withMemoryDb { conn ->
            conn.createV13LibrarySchema()
            conn.exec(
                """
                INSERT INTO mangas (url, source, title, favorite, date_added, initialized, last_update)
                VALUES ('/series/demo', 'source-a', 'Demo', 1, 111, 1, 222)
                """.trimIndent(),
            )
            conn.exec(
                """
                INSERT INTO categories (id, name, `order`, flags) VALUES (1, 'Drama', 0, 0)
                """.trimIndent(),
            )
            conn.exec(
                """
                INSERT INTO library_categories_m2m (webtoonId, sourceId, categoryId)
                VALUES ('/series/demo', 'source-a', 1)
                """.trimIndent(),
            )
            conn.exec(
                """
                INSERT INTO chapters (
                    url, manga_id, name, scanlator, read, bookmark, last_page_read,
                    date_fetch, date_upload, chapter_number, source
                ) VALUES (
                    '/chapter/1', '/series/demo', 'Ch 1', NULL, 1, 1, 7,
                    10, 20, 1.0, 'source-a'
                )
                """.trimIndent(),
            )
            conn.exec(
                """
                INSERT INTO history (
                    chapter_id, webtoon_id, source_id, chapter_title, webtoon_title,
                    webtoon_thumbnail, read_at
                ) VALUES (
                    '/chapter/1', '/series/demo', 'source-a', 'Ch 1', 'Demo', NULL, 99
                )
                """.trimIndent(),
            )

            migrateToV14(conn)

            val chapter = conn.queryRow(
                "SELECT read, bookmark, last_page_read, source FROM chapters WHERE url = '/chapter/1'",
            )
            assertEquals(1, chapter!!["read"])
            assertEquals(1, chapter["bookmark"])
            assertEquals(7, chapter["last_page_read"])
            assertEquals("source-a", chapter["source"])

            val mappingCount = conn.queryLong(
                "SELECT COUNT(*) FROM library_categories_m2m WHERE webtoonId = '/series/demo'",
            )
            assertEquals(1, mappingCount)

            val historySource = conn.queryString(
                "SELECT source_id FROM history WHERE chapter_id = '/chapter/1'",
            )
            assertEquals("source-a", historySource)
        }
    }

    @Test
    fun twoSourcesCanShareRelativeChapterPathsAfterMigration() {
        withMemoryDb { conn ->
            conn.createV13LibrarySchema()
            conn.exec(
                """
                INSERT INTO mangas (url, source, title, favorite, date_added, initialized, last_update)
                VALUES
                ('/series/demo', 'source-a', 'Demo A', 1, 1, 1, 1),
                ('/series/demo', 'source-b', 'Demo B', 1, 1, 1, 1)
                """.trimIndent(),
            )
            // v13 PK is (url, manga_id), so only one chapter row can exist before migration.
            conn.exec(
                """
                INSERT INTO chapters (
                    url, manga_id, name, scanlator, read, bookmark, last_page_read,
                    date_fetch, date_upload, chapter_number, source
                ) VALUES (
                    '/chapter/1', '/series/demo', 'A ch1', NULL, 1, 1, 4,
                    0, 0, 1.0, 'source-a'
                )
                """.trimIndent(),
            )

            migrateToV14(conn)

            conn.exec(
                """
                INSERT INTO chapters (
                    url, manga_id, name, scanlator, read, bookmark, last_page_read,
                    date_fetch, date_upload, chapter_number, source
                ) VALUES (
                    '/chapter/1', '/series/demo', 'B ch1', NULL, 0, 0, 0,
                    0, 0, 1.0, 'source-b'
                )
                """.trimIndent(),
            )
            conn.exec(
                """
                INSERT INTO history (
                    chapter_id, webtoon_id, source_id, chapter_title, webtoon_title,
                    webtoon_thumbnail, read_at
                ) VALUES
                ('/chapter/1', '/series/demo', 'source-a', 'A ch1', 'Demo A', NULL, 10),
                ('/chapter/1', '/series/demo', 'source-b', 'B ch1', 'Demo B', NULL, 11)
                """.trimIndent(),
            )

            val a = conn.queryRow(
                "SELECT read, bookmark, last_page_read FROM chapters WHERE source = 'source-a' AND url = '/chapter/1'",
            )!!
            val b = conn.queryRow(
                "SELECT read, bookmark, last_page_read FROM chapters WHERE source = 'source-b' AND url = '/chapter/1'",
            )!!
            assertEquals(1, a["read"])
            assertEquals(1, a["bookmark"])
            assertEquals(4, a["last_page_read"])
            assertEquals(0, b["read"])
            assertEquals(0, b["bookmark"])
            assertEquals(0, b["last_page_read"])

            assertEquals(
                2,
                conn.queryLong("SELECT COUNT(*) FROM history WHERE chapter_id = '/chapter/1'"),
            )
            assertEquals(
                1,
                conn.queryLong("SELECT COUNT(*) FROM chapters WHERE source = 'source-a' AND manga_id = '/series/demo'"),
            )
            assertEquals(
                1,
                conn.queryLong("SELECT COUNT(*) FROM chapters WHERE source = 'source-b' AND manga_id = '/series/demo'"),
            )
        }
    }

    @Test
    fun v9ToV10CreatesTracksWithoutDroppingManga() {
        withMemoryDb { conn ->
            conn.exec(
                """
                CREATE TABLE mangas (
                    url TEXT NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL,
                    favorite INTEGER NOT NULL, PRIMARY KEY(url, source)
                )
                """.trimIndent(),
            )
            conn.exec("INSERT INTO mangas VALUES ('/m', 's', 'Title', 1)")
            conn.exec(DatabaseMigrations.CREATE_TRACKS.trimIndent())
            assertEquals(1, conn.queryLong("SELECT COUNT(*) FROM mangas"))
            conn.exec(
                """
                INSERT INTO tracks VALUES ('/m', 's', 'anilist', '9', 'Title', 1.0, 'READING')
                """.trimIndent(),
            )
            assertEquals(1, conn.queryLong("SELECT COUNT(*) FROM tracks"))
        }
    }

    @Test
    fun v10ToV13CreatesDownloadIndexWithoutDroppingTracks() {
        withMemoryDb { conn ->
            conn.exec(DatabaseMigrations.CREATE_TRACKS.trimIndent())
            conn.exec("INSERT INTO tracks VALUES ('/m', 's', 'anilist', '9', 'Title', 1.0, 'READING')")
            conn.exec(DatabaseMigrations.CREATE_UPCOMING_RELEASES.trimIndent())
            conn.exec(DatabaseMigrations.CREATE_DOWNLOADED_CHAPTERS.trimIndent())
            assertEquals(1, conn.queryLong("SELECT COUNT(*) FROM tracks"))
            assertTrue(conn.tableExists("upcoming_releases"))
            assertTrue(conn.tableExists("downloaded_chapters"))
        }
    }

    @Test
    fun v14ToV15AddsPageOffsetAndKeepsProgress() {
        withMemoryDb { conn ->
            conn.exec(DatabaseMigrations.CREATE_CHAPTERS_V14.trimIndent())
            conn.exec(
                """
                INSERT INTO chapters (
                    url, manga_id, name, scanlator, read, bookmark, last_page_read,
                    date_fetch, date_upload, chapter_number, source
                ) VALUES (
                    '/chapter/5', '/series/demo', 'Ch 5', NULL, 0, 0, 12,
                    0, 0, 5.0, 'source-a'
                )
                """.trimIndent(),
            )

            conn.exec(DatabaseMigrations.ADD_CHAPTER_PAGE_OFFSET.trimIndent())

            val row = conn.queryRow(
                "SELECT last_page_read, last_page_offset FROM chapters WHERE url = '/chapter/5'",
            )!!
            assertEquals(12, row["last_page_read"])
            assertEquals(0, row["last_page_offset"])
        }
    }

    private fun migrateToV14(conn: Connection) {
        val executor = DatabaseMigrations.SqlExecutor { sql -> conn.exec(sql) }
        DatabaseMigrations.migrateChaptersToSourceAwarePk(executor)
        DatabaseMigrations.migrateHistoryToSourceAwarePk(executor)
    }

    private fun Connection.createV13LibrarySchema() {
        exec("PRAGMA foreign_keys = ON")
        exec(
            """
            CREATE TABLE mangas (
                url TEXT NOT NULL,
                source TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT,
                author TEXT,
                description TEXT,
                genre TEXT,
                status TEXT,
                thumbnail_url TEXT,
                initialized INTEGER NOT NULL,
                favorite INTEGER NOT NULL,
                date_added INTEGER NOT NULL,
                last_update INTEGER NOT NULL,
                PRIMARY KEY(url, source)
            )
            """.trimIndent(),
        )
        exec(
            """
            CREATE TABLE categories (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                `order` INTEGER NOT NULL,
                flags INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        exec(
            """
            CREATE TABLE library_categories_m2m (
                webtoonId TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                categoryId INTEGER NOT NULL,
                PRIMARY KEY(webtoonId, sourceId, categoryId),
                FOREIGN KEY(webtoonId, sourceId) REFERENCES mangas(url, source) ON DELETE CASCADE,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        exec(DatabaseMigrations.CREATE_CHAPTERS_V13.trimIndent())
        exec(DatabaseMigrations.CREATE_CHAPTERS_V13_INDEX.trimIndent())
        exec(DatabaseMigrations.CREATE_HISTORY_V13.trimIndent())
    }

    private fun withMemoryDb(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            block(conn)
        }
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.queryLong(sql: String): Long {
        createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun Connection.queryString(sql: String): String {
        createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                assertTrue(rs.next())
                return rs.getString(1)
            }
        }
    }

    private fun Connection.queryRow(sql: String): Map<String, Any?>? {
        createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (!rs.next()) return null
                val out = mutableMapOf<String, Any?>()
                for (i in 1..rs.metaData.columnCount) {
                    out[rs.metaData.getColumnLabel(i)] = rs.getObject(i)
                }
                return out
            }
        }
    }

    private fun Connection.tableExists(name: String): Boolean {
        return queryLong(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'",
        ) == 1L
    }
}
