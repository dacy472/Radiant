package com.radiant.webtoon.core.source

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.radiant.webtoon.core.receiver.PackageInstallResultReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

/** Install/update failure with a message the Extensions UI can show verbatim. */
class ExtensionInstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Terminal outcome of a [PackageInstaller] session. */
sealed class PackageInstallResult {
    data object Success : PackageInstallResult()
    data class Failure(
        val status: Int,
        val systemMessage: String?,
        val otherPackage: String? = null,
    ) : PackageInstallResult()
}

/**
 * Pluggable way of getting an APK onto the device as a real Android package.
 *
 * [PackageInstallerInstallMethod] is the only implementation. Shizuku / root installers are NOT
 * configured in this project (no dependency, no setting); implement this interface to add one.
 */
interface ExtensionInstallMethod {
    /** Install [apk] as [packageName]; returns normally on success, throws [ExtensionInstallException] otherwise. */
    suspend fun install(apk: File, packageName: String, isUpdate: Boolean)
}

/** Snapshot of what is currently installed for an extension package. */
data class InstalledPackageState(
    val versionCode: Long,
    val signatures: List<String>,
    /** True when the APK lives under filesDir/extensions (loaded by PathClassLoader, not a real install). */
    val isLocal: Boolean,
)

/** Pure decision helpers (unit-tested without Android). */
internal object ExtensionInstallRules {

    /**
     * Ordered download candidates: the repo-published [RepoExtensionEntry.apkUrl] first, then the
     * URL derived from [repoUrl] + file name. Throws when neither is available.
     */
    fun downloadUrls(entry: RepoExtensionEntry, repoUrl: String?): List<String> {
        val urls = LinkedHashSet<String>()
        entry.apkUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        val repo = repoUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: entry.repoUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (repo != null && entry.fileName.isNotBlank()) {
            urls.add(buildExtensionApkUrl(repo, entry.fileName))
        }
        if (urls.isEmpty()) {
            throw ExtensionInstallException(
                "${entry.name} has no repository URL, so its APK cannot be downloaded. " +
                    "Refresh the extension list and try again.",
            )
        }
        return urls.toList()
    }

    /** Null when the downloaded archive is acceptable, otherwise a user-facing rejection reason. */
    fun validateArchive(
        expectedPackage: String,
        archivePackage: String?,
        archiveVersionCode: Long,
        installedVersionCode: Long?,
    ): String? {
        if (archivePackage.isNullOrBlank()) return "Malformed APK: missing package name"
        if (archivePackage != expectedPackage) {
            return "Package mismatch: expected $expectedPackage, got $archivePackage"
        }
        if (installedVersionCode != null && archiveVersionCode < installedVersionCode) {
            return "Downgrade blocked: downloaded version $archiveVersionCode is older than installed $installedVersionCode"
        }
        return null
    }

    /**
     * In-place update rule: the new certificate set must overlap the installed one, or be an
     * explicitly trusted official key. Unsigned APKs are tolerated only when [allowUnsignedDebug].
     * Returns null when OK.
     */
    fun checkSignatureContinuity(
        installedSignatures: List<String>,
        candidateSignatures: List<String>,
        candidateIsOfficial: Boolean,
        allowUnsignedDebug: Boolean,
    ): String? {
        if (candidateSignatures.isEmpty()) {
            return if (allowUnsignedDebug) null else "Update rejected: the new APK is unsigned."
        }
        if (installedSignatures.isEmpty()) return null
        val installed = installedSignatures.map { it.lowercase() }.toSet()
        if (candidateSignatures.any { it.lowercase() in installed }) return null
        if (candidateIsOfficial) return null
        return "Update rejected: the new APK is signed with a different key than the installed extension."
    }

    /** True when an update must go through the OS installer (real package, no private copy). */
    fun useSystemInstaller(state: InstalledPackageState?, systemInstalled: Boolean): Boolean =
        systemInstalled && state?.isLocal != true

    fun describeStatus(status: Int, systemMessage: String? = null): String {
        val base = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Installed successfully."
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Waiting for you to confirm the installation."
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "The installation was blocked by the device. Allow Radiant to install unknown apps and try again."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The installation was cancelled."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid or corrupt."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "The APK conflicts with the installed package (for example a different signing key)."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space to install the extension."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The extension is not compatible with this device."
            STATUS_FAILURE_TIMEOUT -> "The installation timed out."
            else -> "The installation failed."
        }
        val detail = systemMessage?.trim()?.takeIf { it.isNotEmpty() && status != PackageInstaller.STATUS_SUCCESS }
        return if (detail != null) "$base ($detail)" else base
    }

    /** Fingerprint element that changes when the package, version, or APK file changes. */
    fun fingerprintEntry(packageName: String, versionCode: Long, apkPath: String, apkLastModified: Long, apkSize: Long): String =
        "$packageName:$versionCode:$apkPath:$apkLastModified:$apkSize"

    fun buildFingerprint(entries: List<String>, advanced: Boolean, langs: Set<String>): String =
        entries.joinToString("|") + "|advanced=$advanced|langs=${langs.joinToString(",")}"

    /** PackageInstaller.STATUS_FAILURE_TIMEOUT (API 34); literal so older compile paths stay valid. */
    const val STATUS_FAILURE_TIMEOUT = 8
}

/** Default [ExtensionInstallMethod]: PackageInstaller session API. */
class PackageInstallerInstallMethod(private val context: Context) : ExtensionInstallMethod {

    override suspend fun install(apk: File, packageName: String, isUpdate: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (isUpdate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Silent only when the OS allows it (we installed the package / update ownership);
                // otherwise the system falls back to a confirmation prompt we launch from the receiver.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        val waiter = PackageInstallResultReceiver.register(sessionId)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                session.commit(resultIntentSender(sessionId, packageName))
            }

            val result = try {
                withTimeout(INSTALL_TIMEOUT_MS) { waiter.await() }
            } catch (_: TimeoutCancellationException) {
                throw ExtensionInstallException(
                    ExtensionInstallRules.describeStatus(ExtensionInstallRules.STATUS_FAILURE_TIMEOUT),
                )
            }
            when (result) {
                PackageInstallResult.Success -> Unit
                is PackageInstallResult.Failure -> throw ExtensionInstallException(
                    ExtensionInstallRules.describeStatus(result.status, result.systemMessage),
                )
            }
        } catch (e: CancellationException) {
            abandonQuietly(installer, sessionId)
            throw e
        } catch (e: ExtensionInstallException) {
            abandonQuietly(installer, sessionId)
            throw e
        } catch (e: Exception) {
            abandonQuietly(installer, sessionId)
            throw ExtensionInstallException("Could not install $packageName: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            PackageInstallResultReceiver.unregister(sessionId)
        }
    }

    private fun resultIntentSender(sessionId: Int, packageName: String): android.content.IntentSender {
        // Explicit + package-scoped so the receiver stays unexported. PackageInstaller must fill in
        // status extras, so the PendingIntent has to be mutable on API 31+.
        val intent = Intent(context, PackageInstallResultReceiver::class.java).apply {
            action = PackageInstallResultReceiver.ACTION_INSTALL_RESULT
            setPackage(context.packageName)
            putExtra(EXTRA_EXTENSION_PACKAGE, packageName)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        @Suppress("UnspecifiedImmutableFlag")
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private fun abandonQuietly(installer: PackageInstaller, sessionId: Int) {
        try {
            installer.abandonSession(sessionId)
        } catch (_: Exception) {
            // Already finished or committed; nothing to abandon.
        }
    }

    private companion object {
        const val EXTRA_EXTENSION_PACKAGE = "extension_package"

        /** Covers a user leaving the confirmation dialog open. */
        const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

/**
 * Download, verify, and (for real packages) install extension APKs.
 *
 * Two storage modes coexist:
 * - **Local APK** under filesDir/extensions, loaded via PathClassLoader from a read-only copy.
 *   Updates replace the stored file atomically ([ExtensionLoader.commitStagedApk]).
 * - **System package** (installed through the OS): updated in place via [ExtensionInstallMethod].
 */
class ExtensionInstaller(
    private val context: Context,
    private val loader: ExtensionLoader,
    private val systemInstallMethod: ExtensionInstallMethod = PackageInstallerInstallMethod(context),
) {
    private val tag = "ExtensionInstaller"

    /** What is installed now for [packageName]; local APK wins over a system package (same as load order). */
    fun readInstalledState(packageName: String): InstalledPackageState? {
        val flags = ExtensionSignatureTrust.packageFlags()
        val pm = context.packageManager
        try {
            val local = ExtensionApkPaths.packageApkFile(loader.extensionsDir, packageName)
            if (local.exists()) {
                pm.getPackageArchiveInfo(local.absolutePath, flags)?.let {
                    return it.toState(isLocal = true)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not read local APK state for $packageName: ${e.javaClass.simpleName}")
        }
        return try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags).toState(isLocal = false)
        } catch (_: Exception) {
            null
        }
    }

    private fun PackageInfo.toState(isLocal: Boolean) = InstalledPackageState(
        versionCode = ExtensionSignatureTrust.packageVersionCode(this),
        signatures = loader.getPackageSignatures(this),
        isLocal = isLocal,
    )

    /**
     * Download the APK (apkUrl first, then repo-derived URL) into the cache dir and check that it
     * is a parseable APK for [entry] that does not downgrade [installed].
     */
    suspend fun downloadAndVerify(entry: RepoExtensionEntry, repoUrl: String?, installed: InstalledPackageState?): File {
        val file = loader.downloadExtensionApk(repoUrl, entry)
        try {
            val info = context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                ExtensionSignatureTrust.packageFlags(),
            ) ?: throw ExtensionInstallException("Malformed APK: PackageManager could not parse the download")
            ExtensionInstallRules.validateArchive(
                expectedPackage = entry.id,
                archivePackage = info.packageName,
                archiveVersionCode = ExtensionSignatureTrust.packageVersionCode(info),
                installedVersionCode = installed?.versionCode,
            )?.let { throw ExtensionInstallException(it) }
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    /** Signature continuity vs the currently installed APK; throws [ExtensionInstallException] on mismatch. */
    fun requireSignatureContinuity(apk: File, installed: InstalledPackageState?, official: Boolean, allowUnsignedDebug: Boolean) {
        if (installed == null) return
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, ExtensionSignatureTrust.packageFlags())
            ?: throw ExtensionInstallException("Malformed APK: PackageManager could not parse the download")
        ExtensionInstallRules.checkSignatureContinuity(
            installedSignatures = installed.signatures,
            candidateSignatures = loader.getPackageSignatures(info),
            candidateIsOfficial = official,
            allowUnsignedDebug = allowUnsignedDebug,
        )?.let { throw ExtensionInstallException(it) }
    }

    /** Update a real Android package in place through [systemInstallMethod]. */
    suspend fun installIntoSystem(apk: File, entry: RepoExtensionEntry, installed: InstalledPackageState?) {
        val decision = loader.evaluateStagedApk(apk, expectedPackage = entry.id, expectedVersionCode = entry.versionCode)
        val official = when (decision) {
            is ExtensionInstallDecision.Rejected -> throw ExtensionInstallException(decision.reason)
            is ExtensionInstallDecision.UnknownSigner -> throw ExtensionInstallException(
                "Update rejected: the signer of ${entry.name} is not trusted. The installed version was kept.",
            )
            is ExtensionInstallDecision.Trusted -> decision.official
        }
        // Never tolerate unsigned APKs for real packages, even in debug builds.
        requireSignatureContinuity(apk, installed, official, allowUnsignedDebug = false)
        Log.i(tag, "Installing ${entry.id} v${entry.versionCode} via ${systemInstallMethod.javaClass.simpleName}")
        systemInstallMethod.install(apk, entry.id, isUpdate = installed != null)
    }
}
