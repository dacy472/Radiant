package com.radiant.webtoon.core.database

object MangaMetadataMerge {
    fun merge(existing: Manga, fetched: Manga): Manga {
        return existing.copy(
            title = fetched.title,
            artist = fetched.artist ?: existing.artist,
            author = fetched.author ?: existing.author,
            description = fetched.description ?: existing.description,
            genre = fetched.genre ?: existing.genre,
            status = fetched.status ?: existing.status,
            thumbnail_url = fetched.thumbnail_url ?: existing.thumbnail_url,
            initialized = fetched.initialized,
            last_update = fetched.last_update,
            favorite = existing.favorite,
            date_added = existing.date_added,
        )
    }
}
