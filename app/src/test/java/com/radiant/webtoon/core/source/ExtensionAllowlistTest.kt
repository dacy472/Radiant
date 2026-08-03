package com.radiant.webtoon.core.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionAllowlistTest {

    @Test
    fun allowsCuratedPackages() {
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.asurascans"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.all.mangadex"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.mangakakalot"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.mangabuddy"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.mangareadercc"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.toonily"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.all.mangafire"))
        assertTrue(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.all.manhwa18cc"))
    }

    @Test
    fun rejectsOtherPackages() {
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.webtoons"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.en.mangasee"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.es.mangadex"))
        assertFalse(ExtensionAllowlist.isAllowed("eu.kanade.tachiyomi.extension.fr.mangakawaii"))
        assertFalse(ExtensionAllowlist.isAllowed(""))
    }
}
