package com.radiant.webtoon.core.tracker

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AniListTrackerProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val endpoint: String = "https://graphql.anilist.co",
) : TrackerProvider {
    override val id: String = "anilist"
    override val displayName: String = "AniList"
    private var accessToken: String? = null

    override suspend fun getAuthorizationUrl(redirectUri: String): String {
        return "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token&redirect_uri=$redirectUri"
    }

    override suspend fun setAccessToken(token: String) {
        accessToken = token
    }

    override suspend fun search(title: String): List<TrackerEntry> = withContext(Dispatchers.IO) {
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
            .url(endpoint)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        val payload = client.newCall(request).awaitBody()
        parseSearchResults(payload)
    }

    override suspend fun syncProgress(entryId: String, chapterNumber: Int): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext false
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}progress: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: CURRENT) {
                id
                progress
              }
            }
        """.trimIndent()
        val body = mapOf(
            "query" to mutation,
            "variables" to mapOf("mediaId" to entryId.toIntOrNull(), "progress" to chapterNumber)
        )
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $token")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        val payload = runCatching { client.newCall(request).awaitBody() }.getOrElse { return@withContext false }
        runCatching {
            val root = gson.fromJson(payload, JsonObject::class.java)
            assertNoGraphqlErrors(root)
            root.getAsJsonObject("data")?.getAsJsonObject("SaveMediaListEntry") != null
        }.getOrDefault(false)
    }

    companion object {
        private const val CLIENT_ID = "21753"
    }

    private fun parseSearchResults(payload: String): List<TrackerEntry> {
        val root = gson.fromJson(payload, JsonObject::class.java)
            ?: throw IllegalStateException("AniList returned empty JSON")
        assertNoGraphqlErrors(root)
        val media = root
            .getAsJsonObject("data")
            ?.getAsJsonObject("Page")
            ?.getAsJsonArray("media")
            ?: return emptyList()
        return media.mapNotNull { node ->
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
    }

    private fun assertNoGraphqlErrors(root: JsonObject) {
        val errors = root.getAsJsonArray("errors") ?: return
        if (errors.size() == 0) return
        val message = errors[0].asJsonObject.get("message")?.asString ?: "AniList GraphQL error"
        throw IllegalStateException(message)
    }
}

private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = it.body.string()
                if (!it.isSuccessful) {
                    if (cont.isActive) cont.resumeWithException(IOException("HTTP ${it.code}"))
                } else if (cont.isActive) {
                    cont.resume(body)
                }
            }
        }
    })
    cont.invokeOnCancellation { cancel() }
}
