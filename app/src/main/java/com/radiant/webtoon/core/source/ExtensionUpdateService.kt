package com.radiant.webtoon.core.source

/**
 * Pure helpers for extension update checks. Network access goes through
 * [ExtensionLoader.fetchRepoIndex] (Keiyoushi `index.pb`); the legacy `index.min.json` files are
 * now stubs that list no real sources and must never be fetched.
 */

/**
 * Normalizes a user- or legacy-provided repo URL to the protobuf index Mihon reads:
 * `…/index.min.json`, `…/index.json` and bare repo roots all become `…/index.pb`.
 */
internal fun normalizeRepoIndexUrl(repoUrl: String): String {
    val trimmed = repoUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    val fileName = trimmed.substringAfterLast('/')
    return when {
        fileName.equals("index.pb", ignoreCase = true) -> trimmed
        fileName.equals("index.min.json", ignoreCase = true) ||
            fileName.equals("index.json", ignoreCase = true) -> "${trimmed.substringBeforeLast('/')}/index.pb"
        else -> "$trimmed/index.pb"
    }
}

/**
 * Pure update comparison — unit-testable without network/Android deps.
 *
 * Same rule as Mihon: an update is a higher versionCode **or** a newer extension-lib. Keiyoushi
 * restarts versionCode when an extension moves to a new lib (Flame Comics went from 1.4.5x to
 * 1.6.3), so comparing versionCode alone would pin users on the old, broken build forever.
 */
internal fun computeExtensionUpdates(
    remoteEntries: List<RepoExtensionEntry>,
    installedVersionCodes: Map<String, Long>,
    installedLibVersions: Map<String, String?> = emptyMap(),
): List<RepoExtensionEntry> {
    return remoteEntries
        .asSequence()
        .filter { ExtensionAllowlist.isAutoUpdatable(it.id) }
        .mapNotNull { entry ->
            val installedCode = installedVersionCodes[entry.id] ?: return@mapNotNull null
            val newerLib = isNewerExtensionLib(entry.extensionLibVersion, installedLibVersions[entry.id])
            if (entry.versionCode > installedCode || newerLib) entry else null
        }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
        .toList()
}

/** `true` when [remote] (e.g. "1.6") is a strictly newer major.minor than [installed] ("1.4"). */
internal fun isNewerExtensionLib(remote: String?, installed: String?): Boolean {
    fun parse(raw: String?): Pair<Int, Int>? {
        val parts = raw?.trim()?.split('.', '-', '_') ?: return null
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return major to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    val r = parse(remote) ?: return false
    val i = parse(installed) ?: return false
    return r.first > i.first || (r.first == i.first && r.second > i.second)
}

/**
 * Merge entries from several repositories: unique by package id, highest [RepoExtensionEntry.versionCode]
 * wins (first repo wins ties). Result keeps first-seen order.
 */
internal fun mergeRepoEntriesKeepingHighest(entries: List<RepoExtensionEntry>): List<RepoExtensionEntry> {
    val best = LinkedHashMap<String, RepoExtensionEntry>()
    for (entry in entries) {
        val current = best[entry.id]
        if (current == null || entry.versionCode > current.versionCode) best[entry.id] = entry
    }
    return best.values.toList()
}

/**
 * Fetch every repo with [fetch]; a failing repo is reported via [onError] and skipped so the
 * remaining repos still contribute. Entries are merged with [mergeRepoEntriesKeepingHighest].
 */
internal suspend fun collectRepoEntries(
    repoUrls: List<String>,
    fetch: suspend (String) -> List<RepoExtensionEntry>,
    onError: (String, Exception) -> Unit = { _, _ -> },
): List<RepoExtensionEntry> {
    val all = mutableListOf<RepoExtensionEntry>()
    for (url in repoUrls.distinct()) {
        try {
            all.addAll(fetch(url))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(url, e)
        }
    }
    return mergeRepoEntriesKeepingHighest(all)
}

/** Update status for the installed extension that [this] remote entry supersedes. */
internal fun RepoExtensionEntry.toUpdateAvailable(): com.radiant.webtoon.core.model.Extension.Installed.UpdateAvailable =
    com.radiant.webtoon.core.model.Extension.Installed.UpdateAvailable(
        remoteVersionCode = versionCode,
        remoteVersionName = version,
        repoUrl = installableRepoUrl(),
    )
