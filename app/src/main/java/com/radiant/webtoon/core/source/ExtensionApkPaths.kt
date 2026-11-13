package com.radiant.webtoon.core.source

import java.io.File

/** Path containment helpers for extension APK staging. */
object ExtensionApkPaths {
    fun isSafeFileName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed != File(trimmed).name) return false
        if (trimmed == "." || trimmed == "..") return false
        if (trimmed.contains("..") || trimmed.contains('/') || trimmed.contains('\\')) return false
        return trimmed.endsWith(".apk", ignoreCase = true)
    }

    fun resolveChild(directory: File, fileName: String): File {
        require(isSafeFileName(fileName)) { "Rejected extension filename: $fileName" }
        val dir = directory.canonicalFile
        val dest = File(dir, fileName).canonicalFile
        require(dest.path.startsWith(dir.path + File.separator) || dest == File(dir, fileName).canonicalFile) {
            "Extension path escaped its directory: $fileName"
        }
        require(dest.parentFile?.canonicalFile == dir) { "Extension path escaped its directory: $fileName" }
        return dest
    }

    fun packageApkFile(extensionsDir: File, packageName: String): File {
        require(packageName.isNotBlank() && !packageName.contains("..") && !packageName.contains('/') && !packageName.contains('\\')) {
            "Invalid extension package name: $packageName"
        }
        return resolveChild(extensionsDir, "$packageName.apk")
    }
}

sealed class ExtensionInstallDecision {
    data class Trusted(
        val packageName: String,
        val versionCode: Long,
        val signatureHash: String,
        val official: Boolean,
    ) : ExtensionInstallDecision()

    data class UnknownSigner(
        val packageName: String,
        val versionCode: Long,
        val signatureHash: String,
    ) : ExtensionInstallDecision()

    data class Rejected(val reason: String) : ExtensionInstallDecision()

    val canExecute: Boolean get() = this is Trusted
}

object ExtensionInstallPolicy {
    fun decide(
        expectedPackage: String?,
        expectedVersionCode: Long?,
        actualPackage: String?,
        actualVersionCode: Long,
        signatures: List<String>,
        officialFingerprint: String,
        userTrustedKeys: Set<String>,
        apkBytes: Long,
        /** Debug-only escape for unsigned private APKs. Release callers must leave this false. */
        allowUnsigned: Boolean = false,
    ): ExtensionInstallDecision {
        if (apkBytes <= 0L) return ExtensionInstallDecision.Rejected("APK is empty or truncated")
        if (actualPackage.isNullOrBlank()) return ExtensionInstallDecision.Rejected("Malformed APK: missing package name")
        if (expectedPackage != null && !expectedPackage.equals(actualPackage, ignoreCase = false)) {
            return ExtensionInstallDecision.Rejected("Package mismatch: expected $expectedPackage, got $actualPackage")
        }
        if (expectedVersionCode != null && expectedVersionCode > 0 && actualVersionCode > 0 && actualVersionCode < expectedVersionCode) {
            return ExtensionInstallDecision.Rejected("Version downgrade blocked for $actualPackage")
        }
        if (signatures.isEmpty()) {
            return if (allowUnsigned) {
                ExtensionInstallDecision.Trusted(
                    packageName = actualPackage,
                    versionCode = actualVersionCode,
                    signatureHash = "",
                    official = false,
                )
            } else {
                ExtensionInstallDecision.Rejected("APK is unsigned or certificates could not be read")
            }
        }
        val hash = signatures.last()
        val official = signatures.any { signature ->
            signature.equals(officialFingerprint, ignoreCase = true) ||
                ExtensionSignatureTrust.isOfficialFingerprint(signature)
        }
        if (official) {
            return ExtensionInstallDecision.Trusted(actualPackage, actualVersionCode, hash, official = true)
        }
        val key = ExtensionSignatureTrust.trustKey(actualPackage, actualVersionCode, hash)
        val trustedAnyVersion = userTrustedKeys.any {
            it.startsWith("$actualPackage:") && it.endsWith(":$hash")
        }
        if (key in userTrustedKeys || trustedAnyVersion) {
            return ExtensionInstallDecision.Trusted(actualPackage, actualVersionCode, hash, official = false)
        }
        return ExtensionInstallDecision.UnknownSigner(actualPackage, actualVersionCode, hash)
    }
}
