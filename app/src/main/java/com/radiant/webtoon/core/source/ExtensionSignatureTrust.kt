package com.radiant.webtoon.core.source

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Mihon-style extension signature trust.
 *
 * An extension is trusted when:
 * - its signing certificate SHA-256 matches the official Keiyoushi repo fingerprint, or
 * - the user previously trusted `packageName:versionCode:signatureHash` via [trust].
 */
class ExtensionSignatureTrust(context: Context) {
    private val tag = "ExtensionSignatureTrust"
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSignatures(pkgInfo: PackageInfo): List<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        return certs
            ?.mapNotNull { signature ->
                try {
                    sha256Hex(signature.toByteArray())
                } catch (e: Exception) {
                    Log.w(tag, "Failed to hash signature for ${pkgInfo.packageName}", e)
                    null
                }
            }
            .orEmpty()
    }

    fun isTrusted(pkgInfo: PackageInfo, signatures: List<String>): Boolean {
        // Empty signatures stay untrusted here. Unsigned local APKs are a debug-only
        // install policy decision, not a release trust bypass.
        if (signatures.isEmpty()) return false
        if (signatures.any { isOfficialFingerprint(it) }) return true
        val versionCode = packageVersionCode(pkgInfo)
        val key = trustKey(pkgInfo.packageName, versionCode, signatures.last())
        return key in trustedKeys()
    }

    fun isSignatureOfficial(signatures: List<String>): Boolean =
        signatures.any { isOfficialFingerprint(it) }

    fun trust(packageName: String, versionCode: Long, signatureHash: String) {
        val next = trustedKeys()
            .filterNot { it.startsWith("$packageName:") }
            .toMutableSet()
        next += trustKey(packageName, versionCode, signatureHash)
        prefs.edit().putStringSet(KEY_TRUSTED, next).apply()
        Log.i(tag, "Trusted signature for $packageName (v$versionCode)")
    }

    fun trustedKeySet(): Set<String> = trustedKeys()

    fun revokeAll() {
        prefs.edit().remove(KEY_TRUSTED).apply()
    }

    private fun trustedKeys(): Set<String> =
        prefs.getStringSet(KEY_TRUSTED, emptySet())?.toSet().orEmpty()

    companion object {
        private const val PREFS_NAME = "extension_signature_trust"
        private const val KEY_TRUSTED = "trusted_extensions"

        /**
         * SHA-256 of the Keiyoushi release APK signing certificate.
         * Kept as the original constant; do not replace it with the index key.
         */
        const val OFFICIAL_KEIYOUSHI_FINGERPRINT =
            "7ce04da7773d41b489f4693a366c36bcd0a11fc39b547168553c285bd7348e23"

        /**
         * `signingKeyFingerprint` published in the Keiyoushi index.
         * Distinct from [OFFICIAL_KEIYOUSHI_FINGERPRINT]; both are official.
         */
        const val OFFICIAL_KEIYOUSHI_INDEX_FINGERPRINT =
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

        /** Release cert and index signing key. One entry when the values match. */
        val OFFICIAL_FINGERPRINTS: Set<String> = setOf(
            OFFICIAL_KEIYOUSHI_FINGERPRINT,
            OFFICIAL_KEIYOUSHI_INDEX_FINGERPRINT,
        )

        fun isOfficialFingerprint(signatureHash: String): Boolean =
            OFFICIAL_FINGERPRINTS.any { it.equals(signatureHash, ignoreCase = true) }

        fun trustKey(packageName: String, versionCode: Long, signatureHash: String): String =
            "$packageName:$versionCode:$signatureHash"

        fun packageVersionCode(pkgInfo: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }

        /** PackageManager flags needed to read signing certificates + extension metadata. */
        fun packageFlags(): Int {
            var flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
            flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                flags or PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                flags or PackageManager.GET_SIGNATURES
            }
            return flags
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
