package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionAllowlistTest {

    @Test
    fun allowsCuratedPackages() {
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.asurascans"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.all.mangadex"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.mangabuddy"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.all.mangafire"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.all.manhwa18cc"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.manga18fx"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.madarascans"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.madaradex"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.templescan"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.flamecomics"))
    }

    @Test
    fun rejectsRemovedAndOtherPackagesInCuratedMode() {
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.mangareadercc"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.toonily"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.webtoons"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.mangasee"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.es.mangadex"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.fr.mangakawaii"))
        assertFalse(ExtensionAllowlist.isAllowed(""))
    }

    @Test
    fun advancedModeStillRejectsExtensionsOutsideCuratedList() {
        assertFalse(
            ExtensionAllowlist.isAllowed(
                "eu.kanade.tachiyomi.extension.en.webtoons",
                advancedMode = true,
            ),
        )
        assertTrue(
            ExtensionAllowlist.isAllowed(
                "eu.kanade.tachiyomi.extension.en.asurascans",
                advancedMode = true,
            ),
        )
    }

    @Test
    fun isAllowedRepoEntryMatchesByNameOrSlug() {
        val byName = RepoExtensionEntry(
            id = "eu.kanade.tachiyomi.extension.en.unknownslug",
            name = "MangaDex",
            fileName = "apk/test.apk",
        )
        val bySlug = RepoExtensionEntry(
            id = "eu.kanade.tachiyomi.extension.all.mangadex",
            name = "Some Other Label",
            fileName = "apk/test.apk",
        )
        val rejected = RepoExtensionEntry(
            id = "eu.kanade.tachiyomi.extension.en.webtoons",
            name = "WEBTOON",
            fileName = "apk/test.apk",
        )

        assertTrue(ExtensionAllowlist.isAllowedRepoEntry(byName))
        assertTrue(ExtensionAllowlist.isAllowedRepoEntry(bySlug))
        assertFalse(ExtensionAllowlist.isAllowedRepoEntry(rejected))
        val mangaK = RepoExtensionEntry(
            id = "eu.kanade.tachiyomi.extension.en.mangabuddy",
            name = "MangaK",
            fileName = "apk/test.apk",
        )
        assertTrue(ExtensionAllowlist.isAllowedRepoEntry(mangaK))
    }

    @Test
    fun allowedSourceNamesMapToExpectedSlugs() {
        assertEquals(11, ExtensionAllowlist.ALLOWED_SOURCE_NAMES.size)
        assertEquals("mangabuddy", ExtensionAllowlist.SOURCE_NAME_TO_SLUG["MangaK"])
        assertEquals("madarascans", ExtensionAllowlist.SOURCE_NAME_TO_SLUG["Madara Scans"])
        assertEquals("madaradex", ExtensionAllowlist.SOURCE_NAME_TO_SLUG["MadaraDex"])
        assertEquals(ExtensionAllowlist.SOURCE_NAME_TO_SLUG.values.toSet(), ExtensionAllowlist.SLUGS)
    }

    @Test
    fun packagesToPurgeRemovesLegacyInstalledExtensions() {
        val installed = listOf(
            "eu.kanade.tachiyomi.extension.en.asurascans",
            "eu.kanade.tachiyomi.extension.en.mangabuddy",
            "eu.kanade.tachiyomi.extension.en.toonily",
            "eu.kanade.tachiyomi.extension.all.mangadex",
        )
        assertEquals(
            listOf("eu.kanade.tachiyomi.extension.en.toonily"),
            ExtensionAllowlist.packagesToPurge(installed),
        )
    }

    @Test
    fun isStrictlyAllowedMatchesDisplayNameFallback() {
        assertTrue(
            ExtensionAllowlist.isStrictlyAllowed(
                pkgOrId = "eu.kanade.tachiyomi.extension.en.unknownslug",
                displayName = "Asura Scans",
            ),
        )
        assertFalse(
            ExtensionAllowlist.isStrictlyAllowed(
                pkgOrId = "eu.kanade.tachiyomi.extension.en.webtoons",
                displayName = "WEBTOON",
            ),
        )
    }

    @Test
    fun repoStubsAreIdentified() {
        assertTrue(ExtensionAllowlist.isRepoStub("eu.kanade.tachiyomi.extension.all.keiyoushi"))
        assertTrue(ExtensionAllowlist.isRepoStub("eu.kanade.tachiyomi.extension.all.mihon"))
        assertFalse(ExtensionAllowlist.isRepoStub("eu.kanade.tachiyomi.extension.en.asurascans"))
    }

    @Test
    fun dedupePackageIdsPreferEnKeepsEnglishOverAll() {
        val enManhwa18 = "eu.kanade.tachiyomi.extension.en.manhwa18cc"
        val allManhwa18 = "eu.kanade.tachiyomi.extension.all.manhwa18cc"
        val enMangadex = "eu.kanade.tachiyomi.extension.en.mangadex"
        val allMangadex = "eu.kanade.tachiyomi.extension.all.mangadex"

        val result = ExtensionAllowlist.dedupePackageIdsPreferEn(
            listOf(allManhwa18, enManhwa18, allMangadex, enMangadex),
        )
        assertEquals(listOf(enManhwa18, enMangadex), result)
    }

    @Test
    fun dedupePackageIdsPreferEnKeepsAllWhenEnglishMissing() {
        val allManhwa18 = "eu.kanade.tachiyomi.extension.all.manhwa18cc"
        val enMangadex = "eu.kanade.tachiyomi.extension.en.mangadex"

        val result = ExtensionAllowlist.dedupePackageIdsPreferEn(
            listOf(allManhwa18, enMangadex),
        )
        assertEquals(listOf(allManhwa18, enMangadex), result)
    }

    @Test
    fun dedupePackageIdsPreferEnSkipsDedupInAdvancedMode() {
        val enManhwa18 = "eu.kanade.tachiyomi.extension.en.manhwa18cc"
        val allManhwa18 = "eu.kanade.tachiyomi.extension.all.manhwa18cc"
        val packages = listOf(allManhwa18, enManhwa18)

        val result = ExtensionAllowlist.dedupePackageIdsPreferEn(
            packages,
            advancedMode = true,
        )
        assertEquals(packages, result)
    }

    @Test
    fun dedupeSourcesBySlugPreferEnKeepsEnglishOverAll() {
        val enManhwa18 = "eu.kanade.tachiyomi.extension.en.manhwa18cc"
        val allManhwa18 = "eu.kanade.tachiyomi.extension.all.manhwa18cc"
        val enSource = FakeSource(id = "1", name = "Manhwa18", icon = "pkg:$enManhwa18")
        val allSource = FakeSource(id = "2", name = "Manhwa18", icon = "pkg:$allManhwa18")

        val result = ExtensionAllowlist.dedupeSourcesBySlugPreferEn(
            listOf(allSource, enSource),
        )

        assertEquals(listOf(enSource), result)
    }

    private class FakeSource(
        override val id: String,
        override val name: String,
        override val icon: String,
        override val baseUrl: String = "https://example.com",
        override val lang: String = "en",
        override var isPinned: Boolean = false,
        override val capabilities: SourceCapabilities = SourceCapabilities(),
    ) : Source {
        override suspend fun fetchPopularManga(page: Int) =
            throw UnsupportedOperationException()
        override suspend fun fetchSearchManga(
            page: Int,
            query: String,
            filters: eu.kanade.tachiyomi.source.model.FilterList,
        ) = throw UnsupportedOperationException()
        override suspend fun getWebtoonDetail(webtoonId: String) =
            throw UnsupportedOperationException()
        override suspend fun getMangaDetails(webtoonId: String) =
            throw UnsupportedOperationException()
        override suspend fun getChapterList(webtoonId: String) =
            throw UnsupportedOperationException()
        override suspend fun getChapterPages(chapterId: String) =
            throw UnsupportedOperationException()
    }
}
