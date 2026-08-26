package com.radiant.webtoon.core.database

import android.content.Context
import androidx.room.*

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
    version = 13
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

        fun getDatabase(context: Context): WebtoonDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WebtoonDatabase::class.java,
                    "webtoon_database"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
