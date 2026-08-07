package com.radiant.webtoon.core.local

data class LocalSeries(
    val id: String,
    val title: String,
    val coverPath: String?,
    val chapters: List<LocalChapter>,
)

data class LocalChapter(
    val id: String,
    val title: String,
    val number: Double?,
    val localPath: String,
    val pageCount: Int = 0,
    val fileSizeBytes: Long = 0L,
)

data class ScanResult(
    val series: List<LocalSeries>,
    val rootPath: String,
    val error: String? = null,
)
