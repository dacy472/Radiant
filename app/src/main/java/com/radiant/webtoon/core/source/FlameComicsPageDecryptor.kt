package com.radiant.webtoon.core.source

import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * Compatibility shim — page-list scraping lives in [FlameComicsScraper].
 */
@Deprecated(
    message = "Use FlameComicsScraper.fetchPageList instead",
    replaceWith = ReplaceWith(
        "FlameComicsScraper.from(httpSource, jsEngine).fetchPageList(httpSource.getChapterUrl(chapter))",
        "com.radiant.webtoon.core.source.FlameComicsScraper",
    ),
)
object FlameComicsPageDecryptor {

    fun tryDecrypt(
        httpSource: HttpSource,
        chapter: SChapter,
        jsEngine: JavaScriptEngine,
    ): List<String> {
        val chapterUrl = httpSource.getChapterUrl(chapter)
        return FlameComicsScraper.from(httpSource, jsEngine).fetchPageList(chapterUrl)
    }
}
