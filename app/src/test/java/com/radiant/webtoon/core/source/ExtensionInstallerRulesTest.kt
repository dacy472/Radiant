package com.radiant.webtoon.core.source

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtensionInstallerRulesTest {

    private fun entry(apkUrl: String? = null, repoUrl: String? = null, fileName: String = "ext-v1.apk") =
        RepoExtensionEntry(
            id = "eu.kanade.tachiyomi.extension.en.foo",
            name = "Foo",
            fileName = fileName,
            apkUrl = apkUrl,
            repoUrl = repoUrl,
            versionCode = 10,
        )

    // ── download URL selection ──

    @Test
    fun downloadUrls_prefersApkUrlThenRepoFallback() {
        val urls = ExtensionInstallRules.downloadUrls(
            entry(apkUrl = "https://cdn.example.com/foo.apk"),
            repoUrl = "https://example.com/repo/index.pb",
        )
        assertEquals(
            listOf("https://cdn.example.com/foo.apk", "https://example.com/repo/apk/ext-v1.apk"),
            urls,
        )
    }

    @Test
    fun downloadUrls_usesRepoUrlWhenNoApkUrl() {
        val urls = ExtensionInstallRules.downloadUrls(entry(), repoUrl = "https://example.com/repo")
        assertEquals(listOf("https://example.com/repo/apk/ext-v1.apk"), urls)
    }

    @Test
    fun downloadUrls_usesEntryRepoUrlWhenParamMissing() {
        val urls = ExtensionInstallRules.downloadUrls(entry(repoUrl = "https://example.com/repo/"), repoUrl = null)
        assertEquals(listOf("https://example.com/repo/apk/ext-v1.apk"), urls)
    }

    @Test
    fun downloadUrls_apkUrlOnlyIsEnough() {
        val urls = ExtensionInstallRules.downloadUrls(entry(apkUrl = "https://cdn.example.com/foo.apk"), repoUrl = null)
        assertEquals(listOf("https://cdn.example.com/foo.apk"), urls)
    }

    @Test
    fun downloadUrls_missingBothFailsClearly() {
        try {
            ExtensionInstallRules.downloadUrls(entry(apkUrl = " ", repoUrl = ""), repoUrl = "  ")
            fail("expected failure")
        } catch (e: ExtensionInstallException) {
            assertTrue(e.message!!.contains("has no repository URL"))
        }
    }

    // ── archive validation ──

    @Test
    fun validateArchive_acceptsSameOrNewerVersion() {
        val pkg = "eu.kanade.tachiyomi.extension.en.foo"
        assertNull(ExtensionInstallRules.validateArchive(pkg, pkg, 11, 10))
        assertNull(ExtensionInstallRules.validateArchive(pkg, pkg, 10, 10))
        assertNull(ExtensionInstallRules.validateArchive(pkg, pkg, 1, null))
    }

    @Test
    fun validateArchive_rejectsDowngrade() {
        val pkg = "eu.kanade.tachiyomi.extension.en.foo"
        val reason = ExtensionInstallRules.validateArchive(pkg, pkg, 9, 10)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Downgrade"))
    }

    @Test
    fun validateArchive_rejectsPackageMismatch() {
        val reason = ExtensionInstallRules.validateArchive("a.b.c", "x.y.z", 5, null)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Package mismatch"))
        assertNotNull(ExtensionInstallRules.validateArchive("a.b.c", null, 5, null))
    }

    // ── signature continuity ──

    @Test
    fun signatureContinuity_sameKeyOk() {
        assertNull(ExtensionInstallRules.checkSignatureContinuity(listOf("AA"), listOf("aa"), false, false))
    }

    @Test
    fun signatureContinuity_differentKeyRejectedUnlessOfficial() {
        assertNotNull(ExtensionInstallRules.checkSignatureContinuity(listOf("aa"), listOf("bb"), false, false))
        assertNull(ExtensionInstallRules.checkSignatureContinuity(listOf("aa"), listOf("bb"), true, false))
    }

    @Test
    fun signatureContinuity_unsignedOnlyInDebug() {
        assertNotNull(ExtensionInstallRules.checkSignatureContinuity(listOf("aa"), emptyList(), false, false))
        assertNull(ExtensionInstallRules.checkSignatureContinuity(emptyList(), emptyList(), false, true))
    }

    @Test
    fun useSystemInstaller_onlyForRealPackagesWithoutLocalCopy() {
        val local = InstalledPackageState(1, emptyList(), isLocal = true)
        val system = InstalledPackageState(1, emptyList(), isLocal = false)
        assertTrue(ExtensionInstallRules.useSystemInstaller(system, systemInstalled = true))
        assertFalse(ExtensionInstallRules.useSystemInstaller(local, systemInstalled = true))
        assertFalse(ExtensionInstallRules.useSystemInstaller(null, systemInstalled = false))
    }

    // ── PackageInstaller status mapping ──

    @Test
    fun describeStatus_mapsKnownCodes() {
        assertTrue(ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE_BLOCKED).contains("blocked"))
        assertTrue(ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE_ABORTED).contains("cancelled"))
        assertTrue(ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE_INVALID).contains("invalid"))
        assertTrue(ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE_CONFLICT).contains("conflicts"))
        assertTrue(ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE_STORAGE).contains("storage"))
        assertTrue(ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE).contains("compatible"))
        assertTrue(ExtensionInstallRules.describeStatus(ExtensionInstallRules.STATUS_FAILURE_TIMEOUT).contains("timed out"))
        assertEquals("The installation failed.", ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE))
    }

    @Test
    fun describeStatus_appendsSystemDetailOnFailureOnly() {
        assertEquals(
            "The installation failed. (INSTALL_FAILED_X)",
            ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_X"),
        )
        assertEquals(
            "Installed successfully.",
            ExtensionInstallRules.describeStatus(PackageInstaller.STATUS_SUCCESS, "ignored"),
        )
    }

    // ── fingerprint ──

    private fun fp(version: Long = 10, path: String = "/f/a.apk", mtime: Long = 100, size: Long = 5) =
        ExtensionInstallRules.buildFingerprint(
            listOf(ExtensionInstallRules.fingerprintEntry("pkg", version, path, mtime, size)),
            advanced = false,
            langs = setOf("en"),
        )

    @Test
    fun fingerprint_stableForSameInputs() {
        assertEquals(fp(), fp())
    }

    @Test
    fun fingerprint_changesWithVersionCodeOrLastModifiedOrSizeOrPath() {
        val base = fp()
        assertNotEquals(base, fp(version = 11))
        assertNotEquals(base, fp(mtime = 101))
        assertNotEquals(base, fp(size = 6))
        assertNotEquals(base, fp(path = "/f/b.apk"))
    }

    @Test
    fun fingerprint_changesWithModeAndLanguages() {
        val e = listOf(ExtensionInstallRules.fingerprintEntry("pkg", 1, "/p", 1, 1))
        assertNotEquals(
            ExtensionInstallRules.buildFingerprint(e, false, setOf("en")),
            ExtensionInstallRules.buildFingerprint(e, true, setOf("en")),
        )
        assertNotEquals(
            ExtensionInstallRules.buildFingerprint(e, false, setOf("en")),
            ExtensionInstallRules.buildFingerprint(e, false, setOf("en", "ja")),
        )
    }
}
