package eu.kanade.tachiyomi.source.model

/**
 * Combined result of a manga details + chapter list refresh, mirroring the modern
 * Mihon/Keiyoushi extensions-lib `Source.getMangaUpdate` contract.
 */
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
