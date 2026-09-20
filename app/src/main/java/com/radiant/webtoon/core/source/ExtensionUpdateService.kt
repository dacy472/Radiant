package com.radiant.webtoon.core.source

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Fetches `index.min.json` from a repository, filters by [ExtensionAllowlist.isAutoUpdatable],
 * and detects extensions whose remote [RepoExtensionEntry.versionCode] is newer
 * than the locally installed build.
 */
class ExtensionUpdateService(
    private val networkHelper: NetworkHelper,
    private val gson: Gson = Gson(),
) {
    private val tag = "ExtensionUpdateService"

    /**
     * Download and parse the repository's `index.min.json` array.
     */
    suspend fun fetchIndexMinJson(repoUrl: String): List<RepoExtensionEntry> = withContext(Dispatchers.IO) {
        val indexUrl = normalizeIndexMinJsonUrl(repoUrl)
        Log.d(tag, "Fetching extension index: $indexUrl")

        val request = Request.Builder()
            .url(indexUrl)
            .header("Accept", "application/json")
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch index.min.json: HTTP ${response.code}")
            }
            val body = response.body?.string()?.trim().orEmpty()
            if (body.isEmpty()) {
                throw IOException("Empty index.min.json response")
            }
            parseIndexMinJson(body, repoBaseUrl(repoUrl))
        }
    }

    /**
     * Filter whitelisted extensions and keep only those with a newer remote [versionCode].
     *
     * @param installedVersionCodes package name → installed versionCode (from PackageManager scan)
     */
    fun computeAvailableUpdates(
        remoteEntries: List<RepoExtensionEntry>,
        installedVersionCodes: Map<String, Long>,
    ): List<RepoExtensionEntry> = computeExtensionUpdates(remoteEntries, installedVersionCodes)

    /**
     * Full update check: fetch remote index, compare against installed versions.
     */
    suspend fun checkForUpdates(
        repoUrl: String,
        installedVersionCodes: Map<String, Long>,
    ): List<RepoExtensionEntry> {
        val remote = fetchIndexMinJson(repoUrl)
        val updates = computeAvailableUpdates(remote, installedVersionCodes)
        Log.d(tag, "Found ${updates.size} extension update(s) from ${remote.size} remote entries")
        return updates
    }

    /**
     * Detect whitelisted updates and apply each via [applier] (download, overwrite, reload).
     * Returns entries that could not be applied — safe for silent background use.
     */
    suspend fun checkAndApplyUpdates(
        repoUrl: String,
        installedVersionCodes: Map<String, Long>,
        applier: suspend (RepoExtensionEntry) -> Unit,
    ): List<RepoExtensionEntry> {
        val updates = try {
            checkForUpdates(repoUrl, installedVersionCodes)
        } catch (e: Exception) {
            Log.w(tag, "Update check failed (ignored): ${e.message}")
            return emptyList()
        }

        if (updates.isEmpty()) return emptyList()

        val failed = mutableListOf<RepoExtensionEntry>()
        for (entry in updates) {
            try {
                applier(entry)
                Log.d(tag, "Silently applied update for ${entry.id} (v${entry.versionCode})")
            } catch (e: Exception) {
                Log.w(tag, "Silent update failed for ${entry.id}: ${e.message}")
                failed.add(entry)
            }
        }
        return failed
    }

    private fun parseIndexMinJson(body: String, repoUrl: String): List<RepoExtensionEntry> {
        val trimmed = body.trimStart()
        val extensions: List<RepoExtensionEntry> = when {
            trimmed.startsWith("[") -> {
                val listType = object : TypeToken<List<RepoExtensionEntry>>() {}.type
                gson.fromJson(trimmed, listType)
            }
            trimmed.startsWith("{") -> {
                val index = gson.fromJson(trimmed, RepoIndex::class.java)
                index.extensions
            }
            else -> throw IOException("Unrecognized index.min.json format")
        }
        return extensions.map { it.withRepoUrl(repoUrl) }
    }

    companion object {
        /** Resolve any repo URL variant to `index.min.json`. */
        fun normalizeIndexMinJsonUrl(repoUrl: String): String {
            val trimmed = repoUrl.trim().trimEnd('/')
            if (trimmed.endsWith("index.min.json", ignoreCase = true)) return trimmed

            val fileName = trimmed.substringAfterLast('/')
            val base = if (fileName.contains('.')) {
                trimmed.substringBeforeLast('/')
            } else {
                trimmed
            }

            return when {
                fileName.equals("index.pb", ignoreCase = true) ||
                    fileName.equals("index.json", ignoreCase = true) -> "$base/index.min.json"
                fileName.endsWith(".json", ignoreCase = true) -> "$base/index.min.json"
                else -> "$trimmed/index.min.json"
            }
        }

        /** Repo root used for APK download URL construction. */
        fun repoBaseUrl(repoUrl: String): String {
            val indexUrl = normalizeIndexMinJsonUrl(repoUrl)
            return indexUrl.substringBeforeLast('/')
        }
    }
}

/** Pure update comparison — unit-testable without network/Android deps. */
internal fun computeExtensionUpdates(
    remoteEntries: List<RepoExtensionEntry>,
    installedVersionCodes: Map<String, Long>,
): List<RepoExtensionEntry> {
    return remoteEntries
        .asSequence()
        .filter { ExtensionAllowlist.isAutoUpdatable(it.id) }
        .mapNotNull { entry ->
            val installedCode = installedVersionCodes[entry.id] ?: return@mapNotNull null
            if (entry.versionCode > installedCode) entry else null
        }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
        .toList()
}
