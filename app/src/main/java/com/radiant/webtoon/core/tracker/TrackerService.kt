package com.radiant.webtoon.core.tracker

import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.flow.first

class TrackerService(
    private val settingsManager: SettingsManager,
    private val aniListProvider: AniListTrackerProvider = AniListTrackerProvider(),
) {
    suspend fun getAuthUrl(redirectUri: String): String {
        return aniListProvider.getAuthorizationUrl(redirectUri)
    }

    suspend fun linkAniListToken(token: String): Result<Unit> {
        val trimmed = token.trim()
        if (trimmed.length < 16) {
            return Result.failure(IllegalArgumentException("Token looks invalid"))
        }
        settingsManager.setAniListToken(trimmed)
        aniListProvider.setAccessToken(trimmed)
        return Result.success(Unit)
    }

    suspend fun search(title: String): List<TrackerEntry> {
        ensureTokenLoaded()
        return aniListProvider.search(title)
    }

    suspend fun syncProgress(entryId: String, chapterNumber: Int): Boolean {
        ensureTokenLoaded()
        return aniListProvider.syncProgress(entryId, chapterNumber)
    }

    suspend fun syncProgressChecked(entryId: String, chapterNumber: Int): Result<Unit> {
        if (entryId.toIntOrNull() == null) {
            return Result.failure(IllegalArgumentException("AniList media id must be numeric"))
        }
        if (chapterNumber < 0) {
            return Result.failure(IllegalArgumentException("Chapter progress must be positive"))
        }
        val synced = runCatching { syncProgress(entryId, chapterNumber) }
            .getOrElse { return Result.failure(it) }
        if (!synced) {
            return Result.failure(IllegalStateException("AniList sync failed"))
        }
        return Result.success(Unit)
    }

    private suspend fun ensureTokenLoaded() {
        val token = settingsManager.anilistToken.first()
        if (!token.isNullOrBlank()) {
            aniListProvider.setAccessToken(token)
        }
    }
}
