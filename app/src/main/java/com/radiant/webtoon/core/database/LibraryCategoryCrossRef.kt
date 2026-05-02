package com.radiant.webtoon.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "library_categories_m2m",
    primaryKeys = ["webtoonId", "sourceId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = Manga::class,
            parentColumns = ["url", "source"],
            childColumns = ["webtoonId", "sourceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["webtoonId", "sourceId"]),
        Index(value = ["categoryId"])
    ]
)
data class LibraryCategoryCrossRef(
    val webtoonId: String,
    val sourceId: String,
    val categoryId: Int
)
