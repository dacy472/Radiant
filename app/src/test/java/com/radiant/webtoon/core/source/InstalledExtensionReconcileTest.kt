package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledExtensionReconcileTest {
    private val prefix = "eu.kanade.tachiyomi.extension."

    @Test
    fun keepsRowWhenApkFileExistsButCouldNotBeParsed() {
        val removed = InstalledExtensionReconcile.rowIdsToRemove(
            rowIds = listOf(prefix + "en.flamecomics", prefix + "en.removed"),
            stillPresent = emptySet(),
            apkOnDisk = setOf(prefix + "en.flamecomics"),
            directoryReadable = true,
            packagePrefix = prefix,
        )
        assertEquals(listOf(prefix + "en.removed"), removed)
    }

    @Test
    fun keepsEveryRowWhenTheExtensionsDirectoryCannotBeListed() {
        val removed = InstalledExtensionReconcile.rowIdsToRemove(
            rowIds = listOf(prefix + "en.flamecomics"),
            stillPresent = emptySet(),
            apkOnDisk = emptySet(),
            directoryReadable = false,
            packagePrefix = prefix,
        )
        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun doesNotRemoveRowsOutsideTheTachiyomiPrefix() {
        val removed = InstalledExtensionReconcile.rowIdsToRemove(
            rowIds = listOf("com.example.other", prefix + "en.gone"),
            stillPresent = emptySet(),
            apkOnDisk = emptySet(),
            directoryReadable = true,
            packagePrefix = prefix,
        )
        assertEquals(listOf(prefix + "en.gone"), removed)
    }

    @Test
    fun keepsRowThatIsStillInstalled() {
        val id = prefix + "en.mangadex"
        val removed = InstalledExtensionReconcile.rowIdsToRemove(
            rowIds = listOf(id),
            stillPresent = setOf(id),
            apkOnDisk = emptySet(),
            directoryReadable = true,
            packagePrefix = prefix,
        )
        assertEquals(emptyList<String>(), removed)
    }
}
