package com.radiant.webtoon.core.database

import androidx.room.*

/**
 * Entity tracking extension repository URLs.
 */
@Entity(tableName = "extension_repos")
data class ExtensionRepoEntity(
    @PrimaryKey val baseUrl: String,
    val name: String,
    val shortName: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
)
