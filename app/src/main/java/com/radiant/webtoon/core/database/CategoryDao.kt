package com.radiant.webtoon.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY `order` ASC")
    suspend fun getAll(): List<Category>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: Category)

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMapping(crossRef: LibraryCategoryCrossRef)

    @Query("SELECT * FROM library_categories_m2m WHERE webtoonId = :webtoonId AND sourceId = :sourceId")
    suspend fun getMappingsForWebtoon(webtoonId: String, sourceId: String): List<LibraryCategoryCrossRef>

    @Query("DELETE FROM library_categories_m2m WHERE webtoonId = :webtoonId AND sourceId = :sourceId")
    suspend fun removeMappingsForWebtoon(webtoonId: String, sourceId: String)
}
