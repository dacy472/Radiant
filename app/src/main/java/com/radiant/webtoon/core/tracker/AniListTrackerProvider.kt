package com.radiant.webtoon.core.tracker

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AniListTrackerProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
) : TrackerProvider {
    override val id: String = "anilist"
    override val displayName: String = "AniList"
    private var accessToken: String? = null

    override suspend fun getAuthorizationUrl(redirectUri: String): String {
        // The app should open this URL and capture the token out-of-band.
        return "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token&redirect_uri=$redirectUri"
    }

    override suspend fun setAccessToken(token: String) {
        accessToken = token
    }

    override suspend fun search(title: String): List<TrackerEntry> {
        val query = """
            query (${'$'}search: String) {
              Page(perPage: 10) {
                media(search: ${'$'}search, type: MANGA) {
                  id
                  title { romaji english }
                }
              }
            }
        """.trimIndent()
        val body = mapOf("query" to query, "variables" to mapOf("search" to title))
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            parseSearchResults(response.body?.string().orEmpty())
        }
    }

    override suspend fun syncProgress(entryId: String, chapterNumber: Int): Boolean {
        val token = accessToken ?: return false
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}progress: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: CURRENT) {
                id
              }
            }
        """.trimIndent()
        val body = mapOf(
            "query" to mutation,
            "variables" to mapOf("mediaId" to entryId.toIntOrNull(), "progress" to chapterNumber)
        )
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .addHeader("Authorization", "Bearer $token")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    companion object {
        private const val CLIENT_ID = "21753"
    }

    private fun parseSearchResults(payload: String): List<TrackerEntry> {
        return runCatching {
            val root = gson.fromJson(payload, JsonObject::class.java)
            val media = root
                .getAsJsonObject("data")
                ?.getAsJsonObject("Page")
                ?.getAsJsonArray("media")
                ?: return emptyList()
            media.mapNotNull { node ->
                val item = node.asJsonObject
                val id = item.get("id")?.asInt ?: return@mapNotNull null
                val titleObj = item.getAsJsonObject("title")
                val name = listOf("english", "romaji", "native")
                    .asSequence()
                    .mapNotNull { key -> titleObj?.get(key)?.takeIf { !it.isJsonNull }?.asString }
                    .firstOrNull()
                    ?: return@mapNotNull null
                TrackerEntry(
                    trackerId = id.toString(),
                    title = name,
                    progress = 0
                )
            }
        }.getOrDefault(emptyList())
    }
}
