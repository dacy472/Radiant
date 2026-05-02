package com.radiant.webtoon.core.database

import androidx.room.*

// ─── Extension Repository (trusted repo URLs) DAO ───

@Dao
interface ExtensionRepoDao {
    @Query("SELECT * FROM extension_repos ORDER BY dateAdded DESC")
    suspend fun getAll(): List<ExtensionRepoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(repo: ExtensionRepoEntity)

    @Query("DELETE FROM extension_repos WHERE baseUrl = :baseUrl")
    suspend fun delete(baseUrl: String)

    @Query("SELECT * FROM extension_repos WHERE baseUrl = :baseUrl")
    suspend fun getByUrl(baseUrl: String): ExtensionRepoEntity?
}

// ─── Installed Extension (tracks trusted APK extensions) ───

@Entity(tableName = "installed_extensions")
data class InstalledExtensionEntity(
    @PrimaryKey val id: String, // Package Name
    val name: String,
    val isTrusted: Boolean = false,
    val dateInstalled: Long = System.currentTimeMillis()
)

@Dao
interface InstalledExtensionDao {
    @Query("SELECT * FROM installed_extensions")
    suspend fun getAll(): List<InstalledExtensionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ext: InstalledExtensionEntity)

    @Query("UPDATE installed_extensions SET isTrusted = :trusted WHERE id = :id")
    suspend fun setTrusted(id: String, trusted: Boolean)

    @Query("DELETE FROM installed_extensions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM installed_extensions WHERE id = :id")
    suspend fun getById(id: String): InstalledExtensionEntity?
}
