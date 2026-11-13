package com.radiant.webtoon.core.database

import android.content.Context
import androidx.room.*
import java.util.concurrent.atomic.AtomicReference

@Database(
    entities = [
        Manga::class,
        Chapter::class,
        History::class,
        DownloadTask::class,
        ExtensionRepoEntity::class,
        InstalledExtensionEntity::class,
        Category::class,
        LibraryCategoryCrossRef::class,
        Track::class,
        UpcomingRelease::class,
        DownloadedChapter::class,
    ],
    version = DatabaseMigrations.CURRENT_VERSION,
    exportSchema = true,
)
abstract class WebtoonDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadedChapterDao(): DownloadedChapterDao
    abstract fun extensionRepoDao(): ExtensionRepoDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun trackDao(): TrackDao
    abstract fun upcomingReleaseDao(): UpcomingReleaseDao

    companion object {
        @Volatile
        private var INSTANCE: WebtoonDatabase? = null
        private val lastOpenError = AtomicReference<Throwable?>(null)

        fun lastOpenError(): Throwable? = lastOpenError.get()

        fun getDatabase(context: Context): WebtoonDatabase {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: openDatabase(context).also { INSTANCE = it }
            }
        }

        private fun openDatabase(context: Context): WebtoonDatabase {
            lastOpenError.set(null)
            return try {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WebtoonDatabase::class.java,
                    "webtoon_database",
                )
                    .addMigrations(*DatabaseMigrations.ALL)
                    .build()
                // Force a real open so an unsupported schema fails here, not on first DAO call.
                instance.openHelper.writableDatabase
                instance
            } catch (e: IllegalStateException) {
                val wrapped = DatabaseUpgradeException(
                    "Radiant could not upgrade the local library database. " +
                        "The original file was left unchanged. Reinstall the previous version " +
                        "or restore a backup after exporting it from another copy of the app.",
                    e,
                )
                lastOpenError.set(wrapped)
                throw wrapped
            }
        }
    }
}
