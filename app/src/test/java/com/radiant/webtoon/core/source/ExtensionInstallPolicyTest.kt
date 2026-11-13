package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionInstallPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun officialSignerIsTrustedWithoutRoomFlag() {
        val decision = ExtensionInstallPolicy.decide(
            expectedPackage = "eu.kanade.tachiyomi.extension.en.foo",
            expectedVersionCode = 1,
            actualPackage = "eu.kanade.tachiyomi.extension.en.foo",
            actualVersionCode = 1,
            signatures = listOf(ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = emptySet(),
            apkBytes = 1024,
        )
        assertTrue(decision.canExecute)
        assertTrue(decision is ExtensionInstallDecision.Trusted)
    }

    @Test
    fun unknownSignerDoesNotExecuteEvenIfRoomWouldSayTrusted() {
        val decision = ExtensionInstallPolicy.decide(
            expectedPackage = "eu.kanade.tachiyomi.extension.en.foo",
            expectedVersionCode = 1,
            actualPackage = "eu.kanade.tachiyomi.extension.en.foo",
            actualVersionCode = 1,
            signatures = listOf("deadbeef"),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = emptySet(),
            apkBytes = 1024,
        )
        assertFalse(decision.canExecute)
        assertTrue(decision is ExtensionInstallDecision.UnknownSigner)
    }

    @Test
    fun signerChangeWithoutTrustKeyIsUnknown() {
        val oldKey = ExtensionSignatureTrust.trustKey("pkg.a", 1, "aaa")
        val decision = ExtensionInstallPolicy.decide(
            expectedPackage = "pkg.a",
            expectedVersionCode = 2,
            actualPackage = "pkg.a",
            actualVersionCode = 2,
            signatures = listOf("bbb"),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = setOf(oldKey),
            apkBytes = 2048,
        )
        assertFalse(decision.canExecute)
    }

    @Test
    fun packageMismatchIsRejected() {
        val decision = ExtensionInstallPolicy.decide(
            expectedPackage = "pkg.a",
            expectedVersionCode = 1,
            actualPackage = "pkg.b",
            actualVersionCode = 1,
            signatures = listOf(ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = emptySet(),
            apkBytes = 10,
        )
        assertTrue(decision is ExtensionInstallDecision.Rejected)
    }

    @Test
    fun truncatedApkIsRejected() {
        val decision = ExtensionInstallPolicy.decide(
            expectedPackage = "pkg.a",
            expectedVersionCode = 1,
            actualPackage = "pkg.a",
            actualVersionCode = 1,
            signatures = listOf(ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = emptySet(),
            apkBytes = 0,
        )
        assertTrue(decision is ExtensionInstallDecision.Rejected)
    }

    @Test
    fun keiyoushiIndexFingerprintIsOfficialAlongsideReleaseKey() {
        assertTrue(
            ExtensionSignatureTrust.isOfficialFingerprint(
                ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_INDEX_FINGERPRINT,
            ),
        )
        assertTrue(
            ExtensionSignatureTrust.isOfficialFingerprint(
                ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            ),
        )
        assertEquals(
            2,
            ExtensionSignatureTrust.OFFICIAL_FINGERPRINTS.size,
        )
        val decision = ExtensionInstallPolicy.decide(
            expectedPackage = "eu.kanade.tachiyomi.extension.en.foo",
            expectedVersionCode = 1,
            actualPackage = "eu.kanade.tachiyomi.extension.en.foo",
            actualVersionCode = 1,
            signatures = listOf(ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_INDEX_FINGERPRINT),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = emptySet(),
            apkBytes = 1024,
        )
        assertTrue(decision is ExtensionInstallDecision.Trusted)
        assertTrue((decision as ExtensionInstallDecision.Trusted).official)
    }

    @Test
    fun unsignedApkIsRejectedUnlessExplicitlyAllowed() {
        val rejected = ExtensionInstallPolicy.decide(
            expectedPackage = "eu.kanade.tachiyomi.extension.en.foo",
            expectedVersionCode = 1,
            actualPackage = "eu.kanade.tachiyomi.extension.en.foo",
            actualVersionCode = 1,
            signatures = emptyList(),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = emptySet(),
            apkBytes = 1024,
            allowUnsigned = false,
        )
        assertTrue(rejected is ExtensionInstallDecision.Rejected)
        assertFalse(rejected.canExecute)

        val allowed = ExtensionInstallPolicy.decide(
            expectedPackage = "eu.kanade.tachiyomi.extension.en.foo",
            expectedVersionCode = 1,
            actualPackage = "eu.kanade.tachiyomi.extension.en.foo",
            actualVersionCode = 1,
            signatures = emptyList(),
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = emptySet(),
            apkBytes = 1024,
            allowUnsigned = true,
        )
        assertTrue(allowed.canExecute)
        assertTrue(allowed is ExtensionInstallDecision.Trusted)
        assertFalse((allowed as ExtensionInstallDecision.Trusted).official)
    }

    @Test
    fun pathTraversalFilenamesAreRejected() {
        assertFalse(ExtensionApkPaths.isSafeFileName("../evil.apk"))
        assertFalse(ExtensionApkPaths.isSafeFileName("foo/bar.apk"))
        assertFalse(ExtensionApkPaths.isSafeFileName(".."))
        assertTrue(ExtensionApkPaths.isSafeFileName("eu.kanade.tachiyomi.extension.en.foo.apk"))
        val dir = tmp.newFolder("extensions")
        val dest = ExtensionApkPaths.resolveChild(dir, "eu.kanade.tachiyomi.extension.en.foo.apk")
        assertEquals(dir.canonicalFile, dest.parentFile?.canonicalFile)
    }
}
