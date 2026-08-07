package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionUpdateServiceTest {

    @Test
    fun `normalizeIndexMinJsonUrl from index pb`() {
        val url = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        assertEquals(
            "https://github.com/keiyoushi/extensions/raw/repo/index.min.json",
            ExtensionUpdateService.normalizeIndexMinJsonUrl(url),
        )
    }

    @Test
    fun `normalizeIndexMinJsonUrl from repo root`() {
        val url = "https://example.com/extensions/repo"
        assertEquals(
            "https://example.com/extensions/repo/index.min.json",
            ExtensionUpdateService.normalizeIndexMinJsonUrl(url),
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

    private fun entry(id: String, versionCode: Long): RepoExtensionEntry =
        RepoExtensionEntry(
            id = id,
            name = id.substringAfterLast('.'),
            fileName = "apk/$id.apk",
            versionCode = versionCode,
        )
}
