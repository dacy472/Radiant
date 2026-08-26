package com.radiant.webtoon.core.source

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for Tachiyomi/Mihon [eu.kanade.tachiyomi.source.model.SChapter.memo]
 * / [eu.kanade.tachiyomi.source.model.SManga.memo] values that our UI [Chapter] model
 * previously discarded. Sources like Asura Scans require `mangaSlug` in chapter memo to
 * build page-list URLs (`getChapterUrl` → `/comics/{slug}/chapter/{n}`).
 */
object ChapterMemoCache {

    private val chapterMemos = ConcurrentHashMap<String, JsonObject>()
    private val mangaMemos = ConcurrentHashMap<String, JsonObject>()

    private fun chapterKey(sourceId: String, chapterUrl: String): String =
        "$sourceId\u0000$chapterUrl"

    private fun mangaKey(sourceId: String, mangaUrl: String): String =
        "$sourceId\u0000$mangaUrl"

    fun putChapter(sourceId: String, chapterUrl: String, memo: JsonObject) {
        if (memo.isEmpty()) return
        chapterMemos[chapterKey(sourceId, chapterUrl)] = memo
    }

    fun getChapter(sourceId: String, chapterUrl: String): JsonObject? =
        chapterMemos[chapterKey(sourceId, chapterUrl)]?.takeIf { it.isNotEmpty() }

    fun putManga(sourceId: String, mangaUrl: String, memo: JsonObject) {
        if (memo.isEmpty()) return
        mangaMemos[mangaKey(sourceId, mangaUrl)] = memo
    }

    fun getManga(sourceId: String, mangaUrl: String): JsonObject? =
        mangaMemos[mangaKey(sourceId, mangaUrl)]?.takeIf { it.isNotEmpty() }

    fun clearSource(sourceId: String) {
        val prefix = "$sourceId\u0000"
        chapterMemos.keys.filter { it.startsWith(prefix) }.forEach { chapterMemos.remove(it) }
        mangaMemos.keys.filter { it.startsWith(prefix) }.forEach { mangaMemos.remove(it) }
    }
}
