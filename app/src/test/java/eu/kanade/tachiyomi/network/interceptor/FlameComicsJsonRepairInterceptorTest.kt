package eu.kanade.tachiyomi.network.interceptor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameComicsJsonRepairInterceptorTest {

    @Test
    fun `adds missing series fields`() {
        val repaired = repairFlameComicsJsonBody("""{"title":"Test","slug":"test","id":1}""")
        assertNotNull(repaired)
        assertTrue(repaired!!.contains("\"altTitles\""))
        assertTrue(repaired.contains("\"tags\""))
        assertTrue(repaired.contains("\"views\""))
    }

    @Test
    fun `drops catalogue rows without series_id so v1_6_3 parses browse json`() {
        // Shape of /_next/data/<build>/browse.json: one row has no series_id (the production
        // failure "Field 'series_id' is required ... at path: $.pageProps.series[154]").
        val raw = """
            {"pageProps":{"series":[
              {"title":"A","cover":"a.webp","type":"Manhwa","status":"Ongoing","series_id":1,"last_edit":10,"popularityRank":2},
              {"title":"Ghost","cover":"g.webp","type":"Manhwa","status":"Ongoing","last_edit":11,"popularityRank":null},
              {"title":"B","cover":"b.webp","type":"Manhwa","status":"Ongoing","series_id":null,"last_edit":12},
              {"title":"C","cover":"c.webp","type":"Manhwa","status":"Completed","series_id":3,"last_edit":13}
            ]}}
        """.trimIndent()

        val repaired = repairFlameComicsJsonBody(raw)
        assertNotNull(repaired)
        val series = Json.parseToJsonElement(repaired!!).jsonObject["pageProps"]!!.jsonObject["series"]!!.jsonArray
        assertEquals(2, series.size)
        assertEquals("A", series[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("C", series[1].jsonObject["title"]!!.jsonPrimitive.content)
        assertFalse(repaired.contains("Ghost"))
    }

    @Test
    fun `fills required SeriesDto fields missing on the series page`() {
        // /_next/data/<build>/series/1.json omits popularityRank (production failure
        // "Field 'popularityRank' is required ... at path: $.pageProps.series").
        val raw = """{"pageProps":{"series":{"title":"Solo Leveling","cover":"c.webp","type":"Manhwa","status":"Completed","series_id":1},"chapters":[]}}"""
        val repaired = repairFlameComicsJsonBody(raw)
        assertNotNull(repaired)
        val series = Json.parseToJsonElement(repaired!!).jsonObject["pageProps"]!!.jsonObject["series"]!!.jsonObject
        assertEquals(Int.MAX_VALUE, series["popularityRank"]!!.jsonPrimitive.content.toInt())
        assertEquals(0L, series["last_edit"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, series["series_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("Completed", series["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keeps non-series arrays untouched`() {
        val raw = """{"pageProps":{"chapters":[{"chapter":1.0,"token":"x","series_id":1,"release_date":1}],"tags":["Action"]}}"""
        val repaired = repairFlameComicsJsonBody(raw)
        assertNotNull(repaired)
        val props = Json.parseToJsonElement(repaired!!).jsonObject["pageProps"]!!.jsonObject
        assertEquals(1, props["chapters"]!!.jsonArray.size)
        assertEquals(1, props["tags"]!!.jsonArray.size)
    }

    @Test
    fun `never rewrites cdn image responses`() {
        val url = "https://cdn.flamecomics.xyz/uploads/images/series/1/abc/01.jpg?1".toHttpUrl()
        val image = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_2)
            .code(200)
            .message("OK")
            .body(ByteArray(16) { 0xFF.toByte() }.toResponseBody("image/jpeg".toMediaType()))
            .build()
        assertFalse(FlameComicsJsonRepairInterceptor.shouldRepair(url, image))

        val dataUrl = "https://flamecomics.xyz/_next/data/abc/browse.json".toHttpUrl()
        val json = image.newBuilder()
            .request(Request.Builder().url(dataUrl).build())
            .body("{}".toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
        assertTrue(FlameComicsJsonRepairInterceptor.shouldRepair(dataUrl, json))

        val untyped = image.newBuilder()
            .request(Request.Builder().url(dataUrl).build())
            .body("{}".toResponseBody(null))
            .build()
        assertTrue(FlameComicsJsonRepairInterceptor.shouldRepair(dataUrl, untyped))
    }

    @Test
    fun `returns null for non json bodies`() {
        assertEquals(null, repairFlameComicsJsonBody("<html></html>"))
        assertEquals(null, repairFlameComicsJsonBody(""))
    }
}
