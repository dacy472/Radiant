package com.radiant.webtoon.core.image

import com.radiant.webtoon.core.model.PagedWebtoonResponse
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.source.Source
import com.radiant.webtoon.core.source.SourceCapabilities
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceImageOkHttpInterceptorTest {

    @Test
    fun stripsSourceIdAndDoesNotReplaceExistingReferer() {
        val interceptor = interceptor(stubSource())
        val captured = capture(
            interceptor,
            Request.Builder()
                .url("https://2xstorage.com/cover.jpg")
                .header(SourceImageHeaders.SOURCE_ID_HEADER, "src-1")
                .header(SourceImageHeaders.REFERER, "https://coil-injected.example/")
                .header(SourceImageHeaders.USER_AGENT, "Coil-UA/1.0")
                .build(),
        )
        assertNull(captured.header(SourceImageHeaders.SOURCE_ID_HEADER))
        assertEquals("https://coil-injected.example/", captured.header(SourceImageHeaders.REFERER))
        assertEquals("Coil-UA/1.0", captured.header(SourceImageHeaders.USER_AGENT))
    }

    @Test
    fun addsRefererOnlyWhenAbsent() {
        val interceptor = interceptor(stubSource())
        val captured = capture(
            interceptor,
            Request.Builder()
                .url("https://2xstorage.com/cover.jpg")
                .header(SourceImageHeaders.SOURCE_ID_HEADER, "src-1")
                .build(),
        )
        assertNull(captured.header(SourceImageHeaders.SOURCE_ID_HEADER))
        assertEquals("https://manhwa18.cc/", captured.header(SourceImageHeaders.REFERER))
        assertEquals(UserAgentInterceptor.FALLBACK_USER_AGENT, captured.header(SourceImageHeaders.USER_AGENT))
    }

    private fun interceptor(source: Source) = SourceImageOkHttpInterceptor(
        getSource = { id -> source.takeIf { it.id == id } },
        installedSources = { listOf(source) },
    )

    private fun capture(interceptor: okhttp3.Interceptor, request: Request): Request {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                seen = chain.request()
                throw java.io.IOException("stop")
            }
            .build()
        try {
            client.newCall(request).execute()
        } catch (_: java.io.IOException) {
            // expected
        }
        return seen ?: error("interceptor did not run")
    }

    private fun stubSource(): Source = object : Source {
        override val id = "src-1"
        override val name = "Stub"
        override val baseUrl = "https://manhwa18.cc"
        override val lang = "en"
        override var isPinned = false
        override val icon = ""
        override val capabilities = SourceCapabilities()
        override suspend fun fetchPopularManga(page: Int): PagedWebtoonResponse = error("unused")
        override suspend fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage = error("unused")
        override suspend fun getWebtoonDetail(webtoonId: String): Webtoon = error("unused")
        override suspend fun getMangaDetails(webtoonId: String): Webtoon = error("unused")
        override suspend fun getChapterList(webtoonId: String) = emptyList<com.radiant.webtoon.core.model.Chapter>()
        override suspend fun getChapterPages(chapterId: String) = emptyList<String>()
    }
}
