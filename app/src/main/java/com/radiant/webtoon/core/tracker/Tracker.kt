package com.radiant.webtoon.core.tracker

data class TrackerEntry(
    val trackerId: String,
    val title: String,
    val progress: Int,
)

interface TrackerProvider {
    val id: String
    val displayName: String
    suspend fun getAuthorizationUrl(redirectUri: String): String
    suspend fun setAccessToken(token: String)
    suspend fun search(title: String): List<TrackerEntry>
    suspend fun syncProgress(entryId: String, chapterNumber: Int): Boolean
}
