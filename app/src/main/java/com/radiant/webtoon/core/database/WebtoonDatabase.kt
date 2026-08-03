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
        Track::class
    ],
    version = 10
)
abstract class WebtoonDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun extensionRepoDao(): ExtensionRepoDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun trackDao(): TrackDao

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

@Entity(tableName = "webtoons")
data class WebtoonEntity(
    @PrimaryKey val url: String,
    val title: String,
    val sourceId: String,
    val thumbnailUrl: String,
    val isFavorite: Boolean
)

@Dao
interface WebtoonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(webtoon: WebtoonEntity)

    @Query("SELECT * FROM webtoons WHERE isFavorite = 1")
    fun getFavorites(): kotlinx.coroutines.flow.Flow<List<WebtoonEntity>>

    @Query("SELECT * FROM webtoons WHERE url = :url")
    suspend fun getWebtoon(url: String): WebtoonEntity?
}

@Database(entities = [WebtoonEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun webtoonDao(): WebtoonDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
