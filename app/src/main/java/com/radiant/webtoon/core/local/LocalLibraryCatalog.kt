package com.radiant.webtoon.core.local

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory catalog updated after each filesystem scan.
 */
class LocalLibraryCatalog {
    private val seriesById = ConcurrentHashMap<String, LocalSeries>()
    @Volatile
    var lastRootPath: String = ""
        private set

    fun update(scan: ScanResult) {
        lastRootPath = scan.rootPath
        seriesById.clear()
        scan.series.forEach { seriesById[it.id] = it }
    }

    fun getAllSeries(): List<LocalSeries> =
        seriesById.values.sortedBy { it.title.lowercase() }

    fun getSeries(id: String): LocalSeries? = seriesById[id]

    fun search(query: String): List<LocalSeries> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return getAllSeries()
        return getAllSeries().filter { it.title.lowercase().contains(q) }
    }
}
