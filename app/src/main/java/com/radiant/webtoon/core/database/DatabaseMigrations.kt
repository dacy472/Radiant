package com.radiant.webtoon.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Data-preserving Room migrations for [WebtoonDatabase].
 *
 * Recovered from git history of `WebtoonDatabase.kt` (not invented):
 * - v9 (`6450fed`): library/chapters/history/downloads/extensions/categories
 * - v10 (`68b5435`): added `tracks`
 * - v13 (`c02f686`): added `upcoming_releases`, `downloaded_chapters`, and
 *   `installed_extensions.versionCode` (the column was missing on v9/v10)
 * - v11 and v12 never appeared in this file (destructive fallback skipped them)
 *
 * v9 and v10 CREATE TABLE SQL is reconstructed from the entity classes that
 * existed at those commits. A populated device fixture for 9/10 has not been
 * run in CI; current public releases (0.1.0/0.1.1) ship schema v13.
 */
object DatabaseMigrations {
    const val START_VERSION = 9
    const val VERSION_TRACKS = 10
    const val VERSION_DOWNLOAD_INDEX = 13
    const val VERSION_SOURCE_IDENTITY = 14
    const val VERSION_PAGE_OFFSET = 15
    const val CURRENT_VERSION = VERSION_PAGE_OFFSET

    val MIGRATION_9_10 = sqlMigration(9, 10, CREATE_TRACKS)
    val MIGRATION_10_13 = object : Migration(10, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val exec = SupportSqliteExecutor(db)
            exec.exec(CREATE_UPCOMING_RELEASES.trimIndent())
            exec.exec(CREATE_DOWNLOADED_CHAPTERS.trimIndent())
            // v10 shipped installed_extensions without versionCode. v13 added the column
            // under fallbackToDestructiveMigration, so a non-destructive jump must add it
            // or Room refuses to open the database.
            migrateInstalledExtensionsAddVersionCode(exec)
        }
    }
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrateChaptersToSourceAwarePk(SupportSqliteExecutor(db))
            migrateHistoryToSourceAwarePk(SupportSqliteExecutor(db))
        }
    }
    val MIGRATION_14_15 = sqlMigration(14, 15, ADD_CHAPTER_PAGE_OFFSET)

    val ALL = arrayOf(MIGRATION_9_10, MIGRATION_10_13, MIGRATION_13_14, MIGRATION_14_15)

    /**
     * v15: position inside `last_page_read` in 1/10000 of that page's height, so tall webtoon
     * strips resume mid-image regardless of screen width.
     */
    const val ADD_CHAPTER_PAGE_OFFSET = """
        ALTER TABLE `chapters` ADD COLUMN `last_page_offset` INTEGER NOT NULL DEFAULT 0
    """

    const val CREATE_TRACKS = """
        CREATE TABLE IF NOT EXISTS `tracks` (
            `mangaUrl` TEXT NOT NULL,
            `mangaSource` TEXT NOT NULL,
            `trackerId` TEXT NOT NULL,
            `remoteId` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `lastChapter` REAL NOT NULL,
            `status` TEXT NOT NULL,
            PRIMARY KEY(`mangaUrl`, `mangaSource`, `trackerId`)
        )
    """

    const val CREATE_UPCOMING_RELEASES = """
        CREATE TABLE IF NOT EXISTS `upcoming_releases` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `mangaUrl` TEXT NOT NULL,
            `mangaTitle` TEXT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `chapterName` TEXT NOT NULL,
            `releaseAt` INTEGER NOT NULL
        )
    """

    const val CREATE_DOWNLOADED_CHAPTERS = """
        CREATE TABLE IF NOT EXISTS `downloaded_chapters` (
            `sourceId` TEXT NOT NULL,
            `chapterId` TEXT NOT NULL,
            `webtoonId` TEXT NOT NULL,
            `webtoonTitle` TEXT NOT NULL,
            `chapterTitle` TEXT NOT NULL,
            `sourceName` TEXT NOT NULL,
            `localDirectory` TEXT NOT NULL,
            `pageCount` INTEGER NOT NULL,
            `downloadedAt` INTEGER NOT NULL,
            `fileSizeBytes` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`, `chapterId`)
        )
    """

    /** v13 chapters: PK (url, manga_id) — source is a non-key column. */
    const val CREATE_CHAPTERS_V13 = """
        CREATE TABLE IF NOT EXISTS `chapters` (
            `url` TEXT NOT NULL,
            `manga_id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `scanlator` TEXT,
            `read` INTEGER NOT NULL,
            `bookmark` INTEGER NOT NULL,
            `last_page_read` INTEGER NOT NULL,
            `date_fetch` INTEGER NOT NULL,
            `date_upload` INTEGER NOT NULL,
            `chapter_number` REAL NOT NULL,
            `source` TEXT NOT NULL,
            PRIMARY KEY(`url`, `manga_id`)
        )
    """

    const val CREATE_CHAPTERS_V13_INDEX = """
        CREATE INDEX IF NOT EXISTS `index_chapters_manga_id` ON `chapters` (`manga_id`)
    """

    /** v13 history: PK chapter_id only. */
    const val CREATE_HISTORY_V13 = """
        CREATE TABLE IF NOT EXISTS `history` (
            `chapter_id` TEXT NOT NULL,
            `webtoon_id` TEXT NOT NULL,
            `source_id` TEXT NOT NULL,
            `chapter_title` TEXT NOT NULL,
            `webtoon_title` TEXT NOT NULL,
            `webtoon_thumbnail` TEXT,
            `read_at` INTEGER NOT NULL,
            PRIMARY KEY(`chapter_id`)
        )
    """

    const val CREATE_CHAPTERS_V14 = """
        CREATE TABLE IF NOT EXISTS `chapters` (
            `url` TEXT NOT NULL,
            `manga_id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `scanlator` TEXT,
            `read` INTEGER NOT NULL,
            `bookmark` INTEGER NOT NULL,
            `last_page_read` INTEGER NOT NULL,
            `date_fetch` INTEGER NOT NULL,
            `date_upload` INTEGER NOT NULL,
            `chapter_number` REAL NOT NULL,
            `source` TEXT NOT NULL,
            PRIMARY KEY(`source`, `manga_id`, `url`)
        )
    """

    const val CREATE_CHAPTERS_V14_INDEX = """
        CREATE INDEX IF NOT EXISTS `index_chapters_manga_id_source` ON `chapters` (`manga_id`, `source`)
    """

    const val CREATE_HISTORY_V14 = """
        CREATE TABLE IF NOT EXISTS `history` (
            `chapter_id` TEXT NOT NULL,
            `webtoon_id` TEXT NOT NULL,
            `source_id` TEXT NOT NULL,
            `chapter_title` TEXT NOT NULL,
            `webtoon_title` TEXT NOT NULL,
            `webtoon_thumbnail` TEXT,
            `read_at` INTEGER NOT NULL,
            PRIMARY KEY(`source_id`, `webtoon_id`, `chapter_id`)
        )
    """

    fun migrateChaptersToSourceAwarePk(db: SqlExecutor) {
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS `chapters_new` (
                `url` TEXT NOT NULL,
                `manga_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `scanlator` TEXT,
                `read` INTEGER NOT NULL,
                `bookmark` INTEGER NOT NULL,
                `last_page_read` INTEGER NOT NULL,
                `date_fetch` INTEGER NOT NULL,
                `date_upload` INTEGER NOT NULL,
                `chapter_number` REAL NOT NULL,
                `source` TEXT NOT NULL,
                PRIMARY KEY(`source`, `manga_id`, `url`)
            )
            """.trimIndent(),
        )
        db.exec(
            """
            INSERT OR IGNORE INTO `chapters_new` (
                `url`, `manga_id`, `name`, `scanlator`, `read`, `bookmark`,
                `last_page_read`, `date_fetch`, `date_upload`, `chapter_number`, `source`
            )
            SELECT `url`, `manga_id`, `name`, `scanlator`, `read`, `bookmark`,
                `last_page_read`, `date_fetch`, `date_upload`, `chapter_number`, `source`
            FROM `chapters`
            ORDER BY `read` DESC, `bookmark` DESC, `last_page_read` DESC, `date_fetch` DESC
            """.trimIndent(),
        )
        db.exec("DROP TABLE `chapters`")
        db.exec("ALTER TABLE `chapters_new` RENAME TO `chapters`")
        db.exec("CREATE INDEX IF NOT EXISTS `index_chapters_manga_id_source` ON `chapters` (`manga_id`, `source`)")
    }

    /**
     * v9/v10 `installed_extensions` has no `versionCode`. The rebuilt table matches the v13+
     * entity: NOT NULL, no SQL default (Room does not declare one). Existing rows get 0.
     */
    fun migrateInstalledExtensionsAddVersionCode(db: SqlExecutor) {
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS `installed_extensions_new` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `isTrusted` INTEGER NOT NULL,
                `dateInstalled` INTEGER NOT NULL,
                `versionCode` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.exec(
            """
            INSERT INTO `installed_extensions_new` (
                `id`, `name`, `isTrusted`, `dateInstalled`, `versionCode`
            )
            SELECT `id`, `name`, `isTrusted`, `dateInstalled`, 0 FROM `installed_extensions`
            """.trimIndent(),
        )
        db.exec("DROP TABLE `installed_extensions`")
        db.exec("ALTER TABLE `installed_extensions_new` RENAME TO `installed_extensions`")
    }

    fun migrateHistoryToSourceAwarePk(db: SqlExecutor) {
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS `history_new` (
                `chapter_id` TEXT NOT NULL,
                `webtoon_id` TEXT NOT NULL,
                `source_id` TEXT NOT NULL,
                `chapter_title` TEXT NOT NULL,
                `webtoon_title` TEXT NOT NULL,
                `webtoon_thumbnail` TEXT,
                `read_at` INTEGER NOT NULL,
                PRIMARY KEY(`source_id`, `webtoon_id`, `chapter_id`)
            )
            """.trimIndent(),
        )
        db.exec(
            """
            INSERT OR IGNORE INTO `history_new` (
                `chapter_id`, `webtoon_id`, `source_id`, `chapter_title`,
                `webtoon_title`, `webtoon_thumbnail`, `read_at`
            )
            SELECT `chapter_id`, `webtoon_id`, `source_id`, `chapter_title`,
                `webtoon_title`, `webtoon_thumbnail`, `read_at`
            FROM `history`
            ORDER BY `read_at` DESC
            """.trimIndent(),
        )
        db.exec("DROP TABLE `history`")
        db.exec("ALTER TABLE `history_new` RENAME TO `history`")
    }

    fun interface SqlExecutor {
        fun exec(sql: String)
    }

    private class SupportSqliteExecutor(
        private val db: SupportSQLiteDatabase,
    ) : SqlExecutor {
        override fun exec(sql: String) {
            db.execSQL(sql)
        }
    }

    private fun sqlMigration(start: Int, end: Int, vararg statements: String): Migration {
        return object : Migration(start, end) {
            override fun migrate(db: SupportSQLiteDatabase) {
                statements.forEach { db.execSQL(it.trimIndent()) }
            }
        }
    }
}
