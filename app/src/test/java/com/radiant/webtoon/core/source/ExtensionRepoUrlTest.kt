package com.radiant.webtoon.core.source

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExtensionRepoUrlTest {

    @Test
    fun repoIndexBaseUrl_keepsCustomRepoRoot() {
        assertEquals(
            "https://example.com/custom-repo",
            repoIndexBaseUrl("https://example.com/custom-repo"),
        )
        assertEquals(
            "https://example.com/custom-repo",
            repoIndexBaseUrl("https://example.com/custom-repo/"),
        )
    }

    @Test
    fun repoIndexBaseUrl_stripsIndexFiles() {
        assertEquals(
            "https://github.com/keiyoushi/extensions/raw/repo",
            repoIndexBaseUrl("https://github.com/keiyoushi/extensions/raw/repo/index.pb"),
        )
        assertEquals(
            "https://example.com/repo",
            repoIndexBaseUrl("https://example.com/repo/index.min.json"),
        )
    }

    @Test
    fun buildExtensionApkUrl_usesRepoUrlAndApkName() {
        val url = buildExtensionApkUrl(
            repoUrl = "https://example.com/custom-repo",
            fileName = "tachiyomi-en.mangadex-v1.apk",
        )
        assertEquals(
            "https://example.com/custom-repo/apk/tachiyomi-en.mangadex-v1.apk",
            url,
        )
    }

    @Test
    fun buildExtensionApkUrl_prefersExplicitApkUrl() {
        val url = buildExtensionApkUrl(
            repoUrl = "https://example.com/custom-repo",
            fileName = "ignored.apk",
            apkUrl = "https://cdn.example.com/files/real.apk",
        )
        assertEquals("https://cdn.example.com/files/real.apk", url)
    }

    @Test
    fun attachRepoUrl_injectsParentRepositoryIntoEveryEntry() {
        val entries = listOf(
            RepoExtensionEntry(id = "a", name = "A", fileName = "a.apk"),
            RepoExtensionEntry(id = "b", name = "B", fileName = "b.apk"),
        )

        val attached = entries.attachRepoUrl("https://example.com/custom-repo")

        assertEquals("https://example.com/custom-repo", attached[0].repoUrl)
        assertEquals("https://example.com/custom-repo", attached[1].repoUrl)
    }

    @Test
    fun gsonRoundTrip_persistsRepoUrlForCustomRepositories() {
        val original = listOf(
            RepoExtensionEntry(
                id = "eu.kanade.tachiyomi.extension.en.mangadex",
                name = "MangaDex",
                fileName = "tachiyomi-en.mangadex-v1.apk",
                repoUrl = "https://example.com/custom-repo",
            ),
        )
        val gson = Gson()
        val json = gson.toJson(original)
        val listType = object : TypeToken<List<RepoExtensionEntry>>() {}.type
        val restored: List<RepoExtensionEntry> = gson.fromJson(json, listType)

        assertEquals(1, restored.size)
        assertEquals("https://example.com/custom-repo", restored[0].repoUrl)
        assertNotNull(restored[0].repoUrl)
        assertEquals(
            "https://example.com/custom-repo/apk/tachiyomi-en.mangadex-v1.apk",
            buildExtensionApkUrl(restored[0].repoUrl!!, restored[0].fileName, restored[0].apkUrl),
        )
    }
}
