package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionUpdateServiceTest {

    @Test
    fun `normalizeRepoIndexUrl keeps index pb`() {
        val url = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        assertEquals(url, normalizeRepoIndexUrl(url))
    }

    @Test
    fun `normalizeRepoIndexUrl rewrites legacy json stub to index pb`() {
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            normalizeRepoIndexUrl("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"),
        )
        assertEquals(
            "https://example.com/repo/index.pb",
            normalizeRepoIndexUrl("https://example.com/repo/index.json"),
        )
    }

    @Test
    fun `normalizeRepoIndexUrl appends index pb to repo root`() {
        assertEquals(
            "https://example.com/extensions/repo/index.pb",
            normalizeRepoIndexUrl("https://example.com/extensions/repo/ "),
        )
    }

    @Test
    fun `computeExtensionUpdates filters whitelist and versionCode`() {
        val remote = listOf(
            entry("eu.kanade.tachiyomi.extension.en.mangadex", versionCode = 10),
            entry("eu.kanade.tachiyomi.extension.en.asurascans", versionCode = 99),
            entry("eu.kanade.tachiyomi.extension.en.mock", versionCode = 5),
            entry("eu.kanade.tachiyomi.extension.en.webtoons", versionCode = 50),
        )
        val installed = mapOf(
            "eu.kanade.tachiyomi.extension.en.mangadex" to 8L,
            "eu.kanade.tachiyomi.extension.en.asurascans" to 100L,
            "eu.kanade.tachiyomi.extension.en.mock" to 5L,
            "eu.kanade.tachiyomi.extension.en.webtoons" to 10L,
        )

        val updates = computeExtensionUpdates(remote, installed)

        assertEquals(1, updates.size)
        assertEquals("eu.kanade.tachiyomi.extension.en.mangadex", updates.first().id)
        assertEquals(10, updates.first().versionCode)
    }

    @Test
    fun `computeExtensionUpdates includes curated flamecomics when remote is newer`() {
        val remote = listOf(
            entry("eu.kanade.tachiyomi.extension.en.flamecomics", versionCode = 15),
        )
        val installed = mapOf(
            "eu.kanade.tachiyomi.extension.en.flamecomics" to 10L,
        )

        val updates = computeExtensionUpdates(remote, installed)

        assertEquals(1, updates.size)
        assertEquals("eu.kanade.tachiyomi.extension.en.flamecomics", updates.first().id)
        assertEquals(15, updates.first().versionCode)
    }

    @Test
    fun `computeExtensionUpdates includes curated asurascans when remote is newer`() {
        val remote = listOf(
            entry("eu.kanade.tachiyomi.extension.en.asurascans", versionCode = 101),
        )
        val installed = mapOf(
            "eu.kanade.tachiyomi.extension.en.asurascans" to 100L,
        )

        val updates = computeExtensionUpdates(remote, installed)

        assertEquals(1, updates.size)
        assertEquals("eu.kanade.tachiyomi.extension.en.asurascans", updates.first().id)
        assertEquals(101, updates.first().versionCode)
    }

    @Test
    fun `computeExtensionUpdates skips non-curated extensions`() {
        val remote = listOf(
            entry("eu.kanade.tachiyomi.extension.en.webtoons", versionCode = 50),
        )
        val installed = mapOf(
            "eu.kanade.tachiyomi.extension.en.webtoons" to 10L,
        )

        val updates = computeExtensionUpdates(remote, installed)

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `computeExtensionUpdates skips extensions that are not installed`() {
        val remote = listOf(entry("eu.kanade.tachiyomi.extension.en.mangadex", versionCode = 20))
        val updates = computeExtensionUpdates(remote, emptyMap())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `computeExtensionUpdates includes curated flamecomics`() {
        val remote = listOf(
            entry("eu.kanade.tachiyomi.extension.en.flamecomics", versionCode = 50),
            entry("eu.kanade.tachiyomi.extension.en.randomsource", versionCode = 99),
        )
        val installed = mapOf("eu.kanade.tachiyomi.extension.en.flamecomics" to 45L)

        val updates = computeExtensionUpdates(remote, installed)

        assertEquals(1, updates.size)
        assertEquals("eu.kanade.tachiyomi.extension.en.flamecomics", updates.first().id)
        assertEquals(50, updates.first().versionCode)
    }

    @Test
    fun `computeExtensionUpdates includes curated asurascans when newer`() {
        val remote = listOf(entry("eu.kanade.tachiyomi.extension.en.asurascans", versionCode = 99))
        val installed = mapOf("eu.kanade.tachiyomi.extension.en.asurascans" to 90L)

        val updates = computeExtensionUpdates(remote, installed)

        assertEquals(1, updates.size)
        assertEquals("eu.kanade.tachiyomi.extension.en.asurascans", updates.first().id)
    }

    @Test
    fun isAutoUpdatable_includesCuratedAndVerified() {
        assertTrue(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.flamecomics"))
        assertTrue(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.mangadex"))
        assertFalse(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.randomsource"))
    }

    @Test
    fun verifiedExtensions_matchesPackageSuffix() {
        assertTrue(VerifiedExtensions.isVerified("eu.kanade.tachiyomi.extension.en.mangadex"))
        assertFalse(VerifiedExtensions.isVerified("eu.kanade.tachiyomi.extension.en.asurascans"))
    }

    @Test
    fun isAutoUpdatable_coversCuratedAndVerified() {
        assertTrue(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.flamecomics"))
        assertTrue(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.asurascans"))
        assertTrue(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.mangadex"))
        assertTrue(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.mock"))
        assertFalse(ExtensionAllowlist.isAutoUpdatable("eu.kanade.tachiyomi.extension.en.webtoons"))
    }

    @Test
    fun `remote newer than local flags update with remote version and repo`() {
        val id = "eu.kanade.tachiyomi.extension.en.mangadex"
        val remote = listOf(entry(id, versionCode = 12).copy(version = "1.4.12", repoUrl = "https://r1/index.pb"))
        val updates = computeExtensionUpdates(remote, mapOf(id to 10L))
        assertEquals(1, updates.size)
        val status = updates.first().toUpdateAvailable()
        assertEquals(12L, status.remoteVersionCode)
        assertEquals("1.4.12", status.remoteVersionName)
        assertEquals("https://r1/index.pb", status.repoUrl)
    }

    @Test
    fun `newer extension lib flags update even when versionCode restarted`() {
        val id = "eu.kanade.tachiyomi.extension.en.flamecomics"
        val remote = listOf(entry(id, versionCode = 3).copy(extensionLibVersion = "1.6"))
        val updates = computeExtensionUpdates(
            remoteEntries = remote,
            installedVersionCodes = mapOf(id to 51L),
            installedLibVersions = mapOf(id to "1.4"),
        )
        assertEquals(listOf(id), updates.map { it.id })
    }

    @Test
    fun `same lib and lower versionCode is not an update`() {
        val id = "eu.kanade.tachiyomi.extension.en.flamecomics"
        val updates = computeExtensionUpdates(
            remoteEntries = listOf(entry(id, versionCode = 3).copy(extensionLibVersion = "1.6")),
            installedVersionCodes = mapOf(id to 5L),
            installedLibVersions = mapOf(id to "1.6"),
        )
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `isNewerExtensionLib compares major and minor`() {
        assertTrue(isNewerExtensionLib("1.6", "1.4"))
        assertTrue(isNewerExtensionLib("2.0", "1.9"))
        assertFalse(isNewerExtensionLib("1.6", "1.6"))
        assertFalse(isNewerExtensionLib("1.5", "1.6"))
        assertFalse(isNewerExtensionLib("1.6", null))
    }

    @Test
    fun `equal or lower remote versionCode does not flag update`() {
        val id = "eu.kanade.tachiyomi.extension.en.mangadex"
        assertTrue(computeExtensionUpdates(listOf(entry(id, 10)), mapOf(id to 10L)).isEmpty())
        assertTrue(computeExtensionUpdates(listOf(entry(id, 9)), mapOf(id to 10L)).isEmpty())
    }

    @Test
    fun `mergeRepoEntriesKeepingHighest keeps highest versionCode per package`() {
        val id = "eu.kanade.tachiyomi.extension.en.mangadex"
        val merged = mergeRepoEntriesKeepingHighest(
            listOf(
                entry(id, 10).copy(repoUrl = "https://a"),
                entry(id, 14).copy(repoUrl = "https://b"),
                entry(id, 12).copy(repoUrl = "https://c"),
                entry("eu.kanade.tachiyomi.extension.en.flamecomics", 3).copy(repoUrl = "https://a"),
            ),
        )
        assertEquals(2, merged.size)
        val best = merged.first { it.id == id }
        assertEquals(14L, best.versionCode)
        assertEquals("https://b", best.repoUrl)
    }

    @Test
    fun `multi repo duplicate drives update against highest remote`() {
        val id = "eu.kanade.tachiyomi.extension.en.mangadex"
        val merged = mergeRepoEntriesKeepingHighest(listOf(entry(id, 11), entry(id, 15)))
        val updates = computeExtensionUpdates(merged, mapOf(id to 12L))
        assertEquals(1, updates.size)
        assertEquals(15L, updates.first().versionCode)
    }

    @Test
    fun `collectRepoEntries skips failing repo and keeps the rest`() = kotlinx.coroutines.runBlocking {
        val id = "eu.kanade.tachiyomi.extension.en.mangadex"
        val errors = mutableListOf<String>()
        val result = collectRepoEntries(
            repoUrls = listOf("https://bad", "https://good1", "https://good2"),
            fetch = { url ->
                when (url) {
                    "https://bad" -> throw java.io.IOException("boom")
                    "https://good1" -> listOf(entry(id, 10).copy(repoUrl = url))
                    else -> listOf(entry(id, 20).copy(repoUrl = url))
                }
            },
            onError = { url, _ -> errors.add(url) },
        )
        assertEquals(listOf("https://bad"), errors)
        assertEquals(1, result.size)
        assertEquals(20L, result.first().versionCode)
        assertEquals("https://good2", result.first().repoUrl)
    }

    private fun entry(id: String, versionCode: Long): RepoExtensionEntry =
        RepoExtensionEntry(
            id = id,
            name = id.substringAfterLast('.'),
            fileName = "apk/$id.apk",
            versionCode = versionCode,
        )
}

