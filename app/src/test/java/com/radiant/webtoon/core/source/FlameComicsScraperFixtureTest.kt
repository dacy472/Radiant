package com.radiant.webtoon.core.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `chapter-next-data.json` is a live `__NEXT_DATA__` snapshot of a flamecomics.xyz chapter. */
class FlameComicsScraperFixtureTest {

    private val fixture: String =
        requireNotNull(javaClass.classLoader?.getResource("flame/chapter-next-data.json")) {
            "missing flame fixture"
        }.readText()

    @Test
    fun `parses every image of a live chapter into CDN urls`() {
        val urls = FlameComicsScraper(OkHttpClient()).parseChapterImageUrls(fixture, preferPropsPath = true)

        assertEquals(17, urls.size)
        assertEquals(
            "https://cdn.flamecomics.xyz/uploads/images/series/2/364db6fd6bef182e/ORV-311-00.jpg?1779213574",
            urls.first(),
        )
        assertTrue(urls.all { it.startsWith("https://cdn.flamecomics.xyz/uploads/images/series/2/") })
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun `parses the data route shape without the props wrapper`() {
        val dataRoute = Json.parseToJsonElement(fixture).jsonObject.getValue("props").toString()
        val urls = FlameComicsScraper(OkHttpClient()).parseChapterImageUrls(dataRoute, preferPropsPath = false)
        assertEquals(17, urls.size)
    }
}
