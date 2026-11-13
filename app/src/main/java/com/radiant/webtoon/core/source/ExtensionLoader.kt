package com.radiant.webtoon.core.source

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.annotations.SerializedName
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.source.Source as TachiyomiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Handles:
 * - Fetching repo indexes (JSON / protobuf) to list available extensions
 * - Downloading APK files from repos
 * - Scanning installed extension APKs via PackageManager
 * - Loading extension classes via [PathClassLoader] (Mihon-style)
 */
class ExtensionLoader(private val context: Context) {

    private val tag = "ExtensionLoader"

    private val networkHelper: NetworkHelper by injectLazy()
    private val signatureTrust by lazy { ExtensionSignatureTrust(context) }

    // Custom Gson to handle cases where Boolean is represented as a Number (0/1) in JSON
    private val gson = GsonBuilder()
        .registerTypeAdapter(Boolean::class.java, JsonDeserializer<Boolean> { json, _, _ ->
            try {
                val primitive = json.asJsonPrimitive
                if (primitive.isBoolean) primitive.asBoolean else primitive.asInt != 0
            } catch (e: Exception) {
                false
            }
        })
        .create()

    private val client by lazy { networkHelper.client }

    /** Retained so extension Source instances stay valid after GC. */
    private val classLoaders = java.util.concurrent.ConcurrentHashMap<String, ClassLoader>()

    private val apkDownloadDir: File
        get() {
            val dir = File(context.cacheDir, "extension_apks")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val extensionsDir: File
        get() {
            val dir = File(context.filesDir, "extensions")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /** Read-only APK copies for PathClassLoader (Android 14+ rejects writable dex paths). */
    private val readOnlyApkDir: File
        get() {
            val dir = File(context.codeCacheDir, "ext_apk")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    // ─── Known extension package prefixes ───

    private val extensionPrefixes = listOf(
        "eu.kanade.tachiyomi.extension.",
        "tachiyomi.extension.",
        "mihon.extension."
    )

    private val protoBuf = ProtoBuf

    // ─── Repo Index Fetching ───

    suspend fun fetchRepoIndex(repoUrl: String): RepoIndex = withContext(Dispatchers.IO) {
        val parentRepoUrl = repoUrl.trim()
        var url = parentRepoUrl
        if (!url.endsWith(".json") && !url.endsWith(".pb")) {
            url = "${url.trimEnd('/')}/index.pb"
        }

        fun publish(index: RepoIndex): RepoIndex {
            val filtered = filterRepoIndex(index)
            if (parentRepoUrl.isBlank()) return filtered
            return filtered.copy(extensions = filtered.extensions.attachRepoUrl(parentRepoUrl))
        }

        Log.d(tag, "Fetching repo index from: $url")

        fun fetchBytesOnce(targetUrl: String): ByteArray {
            val request = Request.Builder()
                .url(targetUrl)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Failed to fetch repo index: HTTP ${response.code}")
                }
                val body = response.body.bytes()
                if (body.isEmpty()) throw java.io.IOException("Empty repo index response")
                return body
            }
        }

        // One retry: a dropped connection on cold start otherwise leaves the catalog empty.
        fun fetchBytes(targetUrl: String): ByteArray = try {
            fetchBytesOnce(targetUrl)
        } catch (e: java.io.IOException) {
            Log.w(tag, "Repo index fetch failed, retrying once: ${e.message}")
            Thread.sleep(REPO_FETCH_RETRY_DELAY_MS)
            fetchBytesOnce(targetUrl)
        }

        var bytes = fetchBytes(url)

        // Legacy JSON fallback when .pb URL returns JSON (deprecated stub index)
        if (bytes.isNotEmpty() && (bytes[0] == '['.code.toByte() || bytes[0] == '{'.code.toByte())) {
            if (url.endsWith("index.min.json")) {
                val jsonUrl = url.replace("/index.min.json", "/index.json")
                if (jsonUrl != url) {
                    try {
                        bytes = fetchBytes(jsonUrl)
                    } catch (_: Exception) {
                        // use original bytes
                    }
                }
            }
            return@withContext publish(parseLegacyJsonIndex(String(bytes), parentRepoUrl))
        }

        // Gzip-compressed protobuf (Keiyoushi index.pb)
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        }

        val store = protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
        if (store.extensionListUrl != null) {
            Log.d(tag, "Repo uses external extension list: ${store.extensionListUrl}")
            val listBytes = fetchBytes(store.extensionListUrl!!).let { raw ->
                if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                    GZIPInputStream(raw.inputStream()).use { it.readBytes() }
                } else {
                    raw
                }
            }
            val list = protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(listBytes)
            val entries = list.extensions.flatMap { ext ->
                NetworkExtensionStore(
                    extensionList = NetworkExtensionStore.ExtensionList(extensions = listOf(ext)),
                ).toRepoEntries(parentRepoUrl)
            }
            return@withContext publish(
                RepoIndex(
                    name = store.name.ifBlank { "Repository" },
                    description = store.badgeLabel,
                    extensions = entries,
                ),
            )
        }

        val entries = store.toRepoEntries(parentRepoUrl)
        publish(
            RepoIndex(
                name = store.name.ifBlank { "Repository" },
                description = store.badgeLabel,
                extensions = entries,
            ),
        )
    }

    private fun filterRepoIndex(index: RepoIndex): RepoIndex =
        index.copy(extensions = index.extensions.filter { ExtensionAllowlist.isAllowedRepoEntry(it) })

    private fun parseLegacyJsonIndex(body: String, repoUrl: String): RepoIndex {
        val trimmedBody = body.trimStart()
        return if (trimmedBody.startsWith("[")) {
            val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
            val extensions: List<RepoExtensionEntry> = gson.fromJson(trimmedBody, listType)
            RepoIndex(
                name = "Legacy Repository",
                description = "",
                extensions = extensions.attachRepoUrl(repoUrl),
            )
        } else {
            val index = gson.fromJson(trimmedBody, RepoIndex::class.java)
            index.copy(extensions = index.extensions.attachRepoUrl(repoUrl))
        }
    }

    // ─── APK Download ───

    /**
     * Download an extension APK from the repo.
     * Returns the local File path of the downloaded APK.
     */
    suspend fun downloadExtensionApk(
        repoUrl: String?,
        entry: RepoExtensionEntry
    ): File = withContext(Dispatchers.IO) {
        // apkUrl first, then repoUrl-derived; throws "has no repository URL" when neither exists.
        val candidates = ExtensionInstallRules.downloadUrls(entry, repoUrl)
        val fileName = File(entry.fileName).name.takeIf { ExtensionApkPaths.isSafeFileName(it) }
            ?: "${entry.id}.apk"
        val localFile = ExtensionApkPaths.resolveChild(apkDownloadDir, fileName)

        var lastError: Exception? = null
        for (downloadUrl in candidates) {
            // Partial downloads never carry the final name, so they can't be installed by mistake.
            val partial = File(localFile.parentFile, "${localFile.name}.${System.nanoTime()}.part")
            try {
                Log.d(tag, "Downloading extension APK: ${entry.name}")
                val request = Request.Builder().url(downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to download extension: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw Exception("Empty response body")
                    partial.outputStream().use { out ->
                        body.byteStream().use { input -> input.copyTo(out) }
                    }
                }
                if (partial.length() <= 0L) throw Exception("Downloaded extension APK is empty")
                if (localFile.exists()) localFile.delete()
                if (!partial.renameTo(localFile)) {
                    partial.copyTo(localFile, overwrite = true)
                }
                Log.d(tag, "Extension APK saved to: ${localFile.absolutePath}")
                return@withContext localFile
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Download attempt failed for ${entry.id}: ${e.message}")
                lastError = e
            } finally {
                if (partial.exists()) partial.delete()
            }
        }
        throw ExtensionInstallException(
            lastError?.message ?: "Failed to download extension ${entry.name}",
            lastError,
        )
    }

    /**
     * Download an extension APK via system DownloadManager.
     * Use this for extensions that should be installed into the system.
     */
    fun downloadExtensionViaManager(repoUrl: String, entry: RepoExtensionEntry) {
        val downloadUrl = buildExtensionApkUrl(repoUrl, entry.fileName, entry.apkUrl)

        val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl)).apply {
            setTitle("Radiant Extension: ${entry.name}")
            setDescription("Downloading ${entry.name}...")
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, entry.fileName)
            setMimeType("application/vnd.android.package-archive")
        }
        
        val downloadId = downloadManager.enqueue(request)
        com.radiant.webtoon.core.receiver.ApkInstallReceiver.trackDownload(downloadId)
        Log.i(tag, "Enqueued extension download with manager: ${entry.name} id=$downloadId")
    }

    // ─── APK Scanning (PackageManager) ───

    companion object {
        /** Canonical Tachiyomi / Keiyoushi extension package prefix. */
        const val TACHIYOMI_EXTENSION_PREFIX = "eu.kanade.tachiyomi.extension"
        private const val REPO_FETCH_RETRY_DELAY_MS = 1_500L
    }

    /**
     * Discover system-installed Tachiyomi extension packages via PackageManager.
     *
     * Calls [PackageManager.getInstalledPackages] with [PackageManager.GET_META_DATA],
     * keeps packages whose name starts with [TACHIYOMI_EXTENSION_PREFIX], and extracts
     * application label, icon, package name, and version code for each match.
     *
     * Packages that fail metadata extraction are skipped (logged) so a bad APK cannot
     * poison the installed-sources list.
     */
    fun discoverSystemExtensionPackages(): List<ExtensionInfo> {
        val pm = context.packageManager
        val flags = ExtensionSignatureTrust.packageFlags()
        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        val extensions = mutableListOf<ExtensionInfo>()
        for (pkgInfo in installedPackages) {
            val packageName = pkgInfo.packageName ?: continue
            if (!packageName.startsWith(TACHIYOMI_EXTENSION_PREFIX)) continue

            try {
                val signatures = signatureTrust.getSignatures(pkgInfo)
                val extInfo = (parseExtensionPackage(pm, pkgInfo)
                    ?: extractBasicExtensionInfo(pm, pkgInfo))
                    ?.copy(signatureHash = signatures.lastOrNull())
                if (extInfo != null) {
                    extensions.add(extInfo)
                    Log.d(tag, "Discovered system extension: ${extInfo.name} ($packageName)")
                }
            } catch (e: Exception) {
                Log.w(tag, "Metadata extraction failed for $packageName", e)
            }
        }

        Log.i(tag, "PackageManager sync found ${extensions.size} extension package(s)")
        return extensions
    }

    /**
     * Fallback when Tachiyomi class metadata is missing: still capture label, icon,
     * package name, and version so the package can be tracked in Installed Sources.
     */
    private fun extractBasicExtensionInfo(pm: PackageManager, pkgInfo: PackageInfo): ExtensionInfo? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
        val icon = try {
            pm.getApplicationIcon(appInfo)
        } catch (_: Exception) {
            null
        }
        Log.w(tag, "Package ${pkgInfo.packageName} missing extension class metadata; tracking basic info only")
        return ExtensionInfo(
            packageName = pkgInfo.packageName,
            name = pm.getApplicationLabel(appInfo).toString(),
            versionName = pkgInfo.versionName ?: "1.0.0",
            versionCode = versionCode,
            sourceClassName = "",
            apkPath = appInfo.sourceDir.orEmpty(),
            icon = icon,
        )
    }

    /**
     * Scan all installed packages on the device to find Tachiyomi/Mihon extension APKs.
     * Uses intent-based discovery (official way) and [discoverSystemExtensionPackages] for
     * prefix-based PackageManager scanning.
     */
    fun scanInstalledExtensions(): List<ExtensionInfo> {
        val pm = context.packageManager
        val extensions = mutableListOf<ExtensionInfo>()
        val seenPackages = mutableSetOf<String>()

        // 1. Primary path: Search for apps declaring the extension intent
        val intent = android.content.Intent("eu.kanade.tachiyomi.extension.SOURCE")
        
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            pm.queryIntentServices(intent, flags) + pm.queryIntentActivities(intent, flags)
        } else {
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_META_DATA
            pm.queryIntentServices(intent, flags) + pm.queryIntentActivities(intent, flags)
        }
        
        Log.d(tag, "Found ${resolveInfos.size} apps via intent filter")

        for (resolveInfo in resolveInfos) {
            val pkgName = resolveInfo.serviceInfo?.packageName ?: resolveInfo.activityInfo?.packageName ?: continue
            if (seenPackages.contains(pkgName)) continue
            seenPackages.add(pkgName)

            try {
                val pkgInfo = pm.getPackageInfo(pkgName, ExtensionSignatureTrust.packageFlags())
                val extInfo = parseExtensionPackage(pm, pkgInfo)
                if (extInfo != null) {
                    extensions.add(extInfo)
                    Log.i(tag, "MATCHED intent extension: ${extInfo.name} ($pkgName)")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing intent extension $pkgName", e)
            }
        }

        // 2. Prefix scan via getInstalledPackages (covers manual / sideloaded installs)
        for (extInfo in discoverSystemExtensionPackages()) {
            if (seenPackages.add(extInfo.packageName)) {
                extensions.add(extInfo)
                Log.i(tag, "MATCHED prefix extension: ${extInfo.name} (${extInfo.packageName})")
            }
        }

        // Also pick up non-Tachiyomi prefixes (mihon / legacy) not covered by discover*
        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }
        for (pkgInfo in installedPackages) {
            val packageName = pkgInfo.packageName ?: continue
            if (seenPackages.contains(packageName)) continue
            if (packageName.startsWith(TACHIYOMI_EXTENSION_PREFIX)) continue
            if (extensionPrefixes.none { packageName.startsWith(it) }) continue
            try {
                val extInfo = parseExtensionPackage(pm, pkgInfo) ?: continue
                extensions.add(extInfo)
                seenPackages.add(packageName)
                Log.i(tag, "MATCHED alt-prefix extension: ${extInfo.name} ($packageName)")
            } catch (e: Exception) {
                Log.e(tag, "Error parsing prefix extension $packageName", e)
            }
        }

        Log.i(tag, "Scanned total ${extensions.size} installed extensions found")
        return extensions
    }

    /**
     * Scan the internal extensions directory for APK files.
     * These are extensions managed directly by the app, not installed into the system.
     */
    fun scanLocalExtensions(): List<ExtensionInfo> {
        val pm = context.packageManager
        val extensions = mutableListOf<ExtensionInfo>()
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".apk") } ?: emptyArray()

        Log.d(tag, "Scanning local extensions dir: ${extensionsDir.absolutePath} (found ${files.size} files)")

        for (file in files) {
            try {
                val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath, ExtensionSignatureTrust.packageFlags())
                if (pkgInfo != null) {
                    val appInfo = pkgInfo.applicationInfo
                    if (appInfo != null) {
                        appInfo.sourceDir = file.absolutePath
                        appInfo.publicSourceDir = file.absolutePath
                    }
                    
                    val extInfo = parseExtensionPackage(pm, pkgInfo)
                    if (extInfo != null) {
                        extensions.add(extInfo.copy(apkPath = file.absolutePath))
                        Log.i(tag, "Found local extension: ${extInfo.name} (${extInfo.packageName})")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing local extension file: ${file.name}", e)
            }
        }
        return extensions.distinctBy { it.packageName }
    }

    /**
     * True when [packageName] is installed as a real Android package (PackageManager),
     * not merely a private APK under the app's files directory.
     */
    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Deletes a local extension APK and its associated data.
     */
    fun deleteLocalExtension(packageName: String): Boolean {
        val file = ExtensionApkPaths.packageApkFile(extensionsDir, packageName)
        if (file.exists()) {
            // Read-only files cannot be deleted on some devices
            file.setWritable(true)
            val deleted = file.delete()
            if (deleted) {
                Log.i(tag, "Deleted local extension APK: $packageName")
                invalidateLoadedExtension(packageName)
                return true
            }
        }
        return false
    }

    fun isApkTrustedForExecution(extInfo: ExtensionInfo): Boolean {
        val apk = File(extInfo.apkPath)
        val pkgInfo = readSigningPackageInfo(extInfo) ?: return false
        val signatures = signatureTrust.getSignatures(pkgInfo)
        val decision = ExtensionInstallPolicy.decide(
            expectedPackage = extInfo.packageName,
            expectedVersionCode = null,
            actualPackage = pkgInfo.packageName,
            actualVersionCode = ExtensionSignatureTrust.packageVersionCode(pkgInfo),
            signatures = signatures,
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = signatureTrust.trustedKeySet(),
            apkBytes = apk.takeIf { it.exists() }?.length() ?: 1L,
            allowUnsigned = com.radiant.webtoon.BuildConfig.DEBUG && isPrivateExtensionApk(apk),
        )
        return decision.canExecute
    }

    fun evaluateStagedApk(
        staged: File,
        expectedPackage: String,
        expectedVersionCode: Long,
    ): ExtensionInstallDecision {
        if (!staged.exists() || staged.length() <= 0L) {
            return ExtensionInstallDecision.Rejected("Malformed or truncated APK")
        }
        val pkgInfo = context.packageManager.getPackageArchiveInfo(
            staged.absolutePath,
            ExtensionSignatureTrust.packageFlags(),
        ) ?: return ExtensionInstallDecision.Rejected("Malformed APK: PackageManager could not parse archive")
        val signatures = signatureTrust.getSignatures(pkgInfo)
        return ExtensionInstallPolicy.decide(
            expectedPackage = expectedPackage,
            expectedVersionCode = expectedVersionCode,
            actualPackage = pkgInfo.packageName,
            actualVersionCode = ExtensionSignatureTrust.packageVersionCode(pkgInfo),
            signatures = signatures,
            officialFingerprint = ExtensionSignatureTrust.OFFICIAL_KEIYOUSHI_FINGERPRINT,
            userTrustedKeys = signatureTrust.trustedKeySet(),
            apkBytes = staged.length(),
            allowUnsigned = com.radiant.webtoon.BuildConfig.DEBUG && isPrivateExtensionApk(staged),
        )
    }

    /** Private copies under filesDir/extensions. System APKs stay signature-checked. */
    private fun isPrivateExtensionApk(apk: File): Boolean {
        if (!apk.exists()) return false
        return try {
            val root = extensionsDir.canonicalFile
            val candidate = apk.canonicalFile
            val rootPath = root.path
            val candidatePath = candidate.path
            candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Replaces [dest] with [staged] while keeping the previous APK if the replace fails.
     */
    fun commitStagedApk(staged: File, dest: File) {
        val backup = File(dest.parentFile, dest.name + ".bak")
        if (dest.exists()) {
            dest.setWritable(true)
            if (backup.exists()) backup.delete()
            if (!dest.renameTo(backup) && !dest.copyTo(backup, overwrite = true).exists()) {
                throw Exception("Could not snapshot the working extension APK")
            }
        }
        try {
            if (dest.exists()) dest.delete()
            if (!staged.renameTo(dest)) {
                staged.copyTo(dest, overwrite = true)
                staged.delete()
            }
            dest.setWritable(false)
            if (backup.exists()) backup.delete()
        } catch (e: Exception) {
            if (backup.exists()) {
                dest.delete()
                backup.renameTo(dest)
            }
            throw e
        }
    }

    private fun readSigningPackageInfo(extInfo: ExtensionInfo): PackageInfo? {
        val flags = ExtensionSignatureTrust.packageFlags()
        val apk = File(extInfo.apkPath)
        if (apk.exists()) {
            return context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }
        return try {
            context.packageManager.getPackageInfo(extInfo.packageName, flags)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Wipes every locally downloaded extension bundle and associated DEX/cache artifacts.
     * Does not touch system-installed extension APKs (PackageManager).
     */
    fun clearAllDownloadedBundles(): Int {
        var deleted = 0
        val packageNames = classLoaders.keys.toList()
        for (packageName in packageNames) {
            invalidateLoadedExtension(packageName)
        }
        classLoaders.clear()

        extensionsDir.listFiles()?.forEach { file ->
            file.setWritable(true)
            if (file.delete()) deleted++
        }

        val downloadCache = File(context.cacheDir, "extension_apks")
        if (downloadCache.exists()) {
            downloadCache.deleteRecursively()
        }

        val readOnlyDir = File(context.codeCacheDir, "ext_apk")
        if (readOnlyDir.exists()) {
            readOnlyDir.deleteRecursively()
        }

        context.codeCacheDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("ext_") }
            ?.forEach { it.deleteRecursively() }

        Log.i(tag, "Cleared $deleted local extension APK(s) and all extension caches")
        return deleted
    }

    /**
     * Drop cached ClassLoader and read-only APK copy so the next load uses a fresh file.
     *
     * Limit: a [PathClassLoader] can never unload classes in the JVM. The old loader (and its
     * classes) stays in memory until nothing references it, so callers must also drop every
     * Source instance created from it and always build a NEW loader for the new APK; the old
     * loader is never reused for the updated version.
     */
    fun invalidateLoadedExtension(packageName: String) {
        classLoaders.remove(packageName)
        val dexDir = File(context.codeCacheDir, "ext_$packageName")
        if (dexDir.exists()) dexDir.deleteRecursively()
        val cachedApk = File(readOnlyApkDir, "$packageName.apk")
        if (cachedApk.exists()) {
            cachedApk.setWritable(true)
            cachedApk.delete()
        }
    }

    /**
     * Android 14+ (targetSdk 34+) forbids loading DEX from a writable path.
     * System-installed APKs are already non-writable; local copies under filesDir are not.
     * Sync a read-only copy into codeCacheDir and return that path for [PathClassLoader].
     */
    private fun prepareReadOnlyDexPath(apkPath: String, packageName: String): String {
        val source = File(apkPath)
        if (!source.exists()) {
            throw IllegalArgumentException("Extension APK missing: $apkPath")
        }

        // Already non-writable (typical for /data/app system packages) — load in place
        if (!source.canWrite()) {
            return apkPath
        }

        val dest = File(readOnlyApkDir, "$packageName.apk")
        val needsCopy = !dest.exists() ||
            dest.length() != source.length() ||
            dest.lastModified() < source.lastModified()

        if (needsCopy) {
            if (dest.exists()) dest.setWritable(true)
            source.copyTo(dest, overwrite = true)
        }

        if (!dest.setReadOnly()) {
            Log.w(tag, "Failed to mark extension APK read-only: ${dest.absolutePath}")
        }
        return dest.absolutePath
    }

    private fun parseExtensionPackage(pm: PackageManager, pkgInfo: PackageInfo): ExtensionInfo? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val metadata = appInfo.metaData ?: return null

        // Tachiyomi extensions use these metadata keys
        val sourceName = metadata.getString("tachiyomi.extension.class")
            ?: metadata.getString("source.class")
            ?: metadata.getString("tachiyomi.extension.factory")
            ?: run {
                Log.w(tag, "Package ${pkgInfo.packageName} missing required metadata (tachiyomi.extension.class or factory)")
                return null
            }

        val extName = pm.getApplicationLabel(appInfo).toString()
        val versionName = pkgInfo.versionName ?: "1.0.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        val nsfw = when (val raw = metadata.get("tachiyomi.extension.nsfw")) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            else -> metadata.getInt("tachiyomi.extension.nsfw", 0) == 1
        }

        val extensionLibVersion = ExtensionLibCompat.parseVersion(metadata)
        val libCompatibility = ExtensionLibCompat.check(extensionLibVersion)

        // Get the icon
        val icon = try {
            pm.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            null
        }

        return ExtensionInfo(
            packageName = pkgInfo.packageName,
            name = extName,
            versionName = versionName,
            versionCode = versionCode,
            sourceClassName = sourceName,
            apkPath = appInfo.sourceDir,
            nsfw = nsfw,
            icon = icon,
            extensionLibVersion = extensionLibVersion,
            libCompatibility = libCompatibility,
        )
    }

    // ─── APK Class Loading ───

    /**
     * Mihon-style entry point: discover system packages prefixed with
     * [TACHIYOMI_EXTENSION_PREFIX], load each APK via [PathClassLoader], reflectively
     * instantiate Source / SourceFactory classes, and return active Tachiyomi sources.
     *
     * Private (app-local) APKs under [extensionsDir] are also loaded so PackageManager
     * installs and in-app downloads share one source list.
     */
    fun loadExtensions(appContext: Context = context): List<TachiyomiSource> {
        val pm = appContext.packageManager
        val flags = ExtensionSignatureTrust.packageFlags()
        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        val sources = mutableListOf<TachiyomiSource>()
        val loadedPackages = mutableSetOf<String>()

        for (pkgInfo in installedPackages) {
            val packageName = pkgInfo.packageName ?: continue
            if (!packageName.startsWith(TACHIYOMI_EXTENSION_PREFIX)) continue
            val appInfo = pkgInfo.applicationInfo ?: continue

            try {
                val signatures = signatureTrust.getSignatures(pkgInfo)
                if (signatures.isEmpty()) {
                    Log.w(tag, "Skipping unsigned extension package: $packageName")
                    continue
                }
                if (!signatureTrust.isTrusted(pkgInfo, signatures)) {
                    Log.w(tag, "Skipping untrusted extension package: $packageName (sig=${signatures.last()})")
                    continue
                }

                val extInfo = parseExtensionPackage(pm, pkgInfo)?.copy(
                    signatureHash = signatures.lastOrNull(),
                ) ?: continue
                if (extInfo.sourceClassName.isBlank()) continue

                val loaded = instantiateSources(
                    extInfo = extInfo,
                    apkPath = appInfo.sourceDir ?: extInfo.apkPath,
                )
                sources.addAll(loaded)
                loadedPackages.add(packageName)
                Log.i(tag, "loadExtensions: $packageName → ${loaded.size} source(s)")
            } catch (e: Exception) {
                Log.e(tag, "loadExtensions failed for $packageName", e)
            }
        }

        // Private copies (filesDir/extensions) — do not break existing download path
        for (localInfo in scanLocalExtensions()) {
            if (localInfo.packageName in loadedPackages) continue
            if (!localInfo.packageName.startsWith(TACHIYOMI_EXTENSION_PREFIX) &&
                extensionPrefixes.none { localInfo.packageName.startsWith(it) }
            ) {
                continue
            }
            try {
                val loaded = loadExtensionSources(localInfo)
                sources.addAll(loaded)
                loadedPackages.add(localInfo.packageName)
            } catch (e: Exception) {
                Log.e(tag, "loadExtensions local failed for ${localInfo.packageName}", e)
            }
        }

        Log.i(tag, "loadExtensions complete: ${sources.size} source(s) from ${loadedPackages.size} package(s)")
        return sources
    }

    /**
     * Load the Source classes from an installed extension APK.
     * Uses [PathClassLoader] (Mihon-style) against a read-only APK path.
     */
    fun loadExtensionSources(extInfo: ExtensionInfo): List<TachiyomiSource> {
        if (!isApkTrustedForExecution(extInfo)) {
            throw SecurityException("Refusing to execute untrusted extension ${extInfo.packageName}")
        }
        if (extInfo.libCompatibility == ExtensionLibCompatibility.TooNew) {
            throw IllegalStateException(
                "Extension ${extInfo.packageName} requires extension-lib ${extInfo.extensionLibVersion}; " +
                    "Radiant supports ${ExtensionLibCompat.SUPPORTED_VERSION}. Please update the app.",
            )
        }
        if (extInfo.sourceClassName.isBlank()) {
            Log.w(tag, "No source class metadata for ${extInfo.packageName}")
            return emptyList()
        }
        val isLocal = extInfo.apkPath.startsWith(context.filesDir.absolutePath)
        Log.d(tag, "Loading APK: ${extInfo.apkPath} exists=${File(extInfo.apkPath).exists()} local=$isLocal")
        return instantiateSources(extInfo, extInfo.apkPath)
    }

    private fun instantiateSources(extInfo: ExtensionInfo, apkPath: String): List<TachiyomiSource> {
        // PathClassLoader on a non-writable path (system APK or read-only codeCache copy).
        val classLoader = classLoaders.getOrPut(extInfo.packageName) {
            val dexPath = prepareReadOnlyDexPath(apkPath, extInfo.packageName)
            Log.d(tag, "PathClassLoader path for ${extInfo.packageName}: $dexPath")
            PathClassLoader(dexPath, this::class.java.classLoader)
        }

        val sources = mutableListOf<TachiyomiSource>()
        val classNames = extInfo.sourceClassName.split(";", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (className in classNames) {
            try {
                val fullClassName = if (className.startsWith(".")) {
                    extInfo.packageName + className
                } else {
                    className
                }

                Log.d(tag, "Instantiating class: $fullClassName")
                val clazz = Class.forName(fullClassName, false, classLoader)

                val instance = try {
                    val instanceField = clazz.getDeclaredField("INSTANCE")
                    instanceField.isAccessible = true
                    instanceField.get(null)
                } catch (_: Exception) {
                    try {
                        clazz.getDeclaredConstructor().newInstance()
                    } catch (e2: Exception) {
                        Log.e(tag, "Failed to instantiate class: $fullClassName", e2)
                        continue
                    }
                }

                val isSourceFactory = try {
                    clazz.methods.any { it.name == "createSources" }
                } catch (_: Exception) {
                    false
                }

                if (instance is TachiyomiSource || isSourceByName(clazz)) {
                    val source = instance as? TachiyomiSource
                    if (source != null) {
                        Log.d(tag, "Loaded Source: ${source.name}")
                        sources.add(source)
                    }
                } else if (isSourceFactory) {
                    try {
                        val method = clazz.methods.find { it.name == "createSources" }
                        if (method != null) {
                            val rawSources = method.invoke(instance)
                            val factorySources = when (rawSources) {
                                is List<*> -> rawSources.filterIsInstance<TachiyomiSource>()
                                is Array<*> -> rawSources.filterIsInstance<TachiyomiSource>()
                                else -> emptyList()
                            }

                            if (factorySources.isEmpty() && rawSources is List<*>) {
                                for (item in rawSources) {
                                    if (item != null && isSourceByName(item.javaClass)) {
                                        (item as? TachiyomiSource)?.let { sources.add(it) }
                                    }
                                }
                            } else {
                                factorySources.forEach { source ->
                                    Log.d(tag, "Loaded Source: ${source.name} (from factory)")
                                    sources.add(source)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to invoke createSources on $fullClassName", e)
                    }
                } else {
                    Log.w(
                        tag,
                        "Class $fullClassName is not a Source/SourceFactory. " +
                            "Interfaces: ${clazz.interfaces.joinToString { it.name }}",
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error loading source class $className", e)
            }
        }
        return sources
    }

    /** SHA-256 signature hashes for a package, or empty if unsigned / unreadable. */
    fun getPackageSignatures(pkgInfo: PackageInfo): List<String> =
        signatureTrust.getSignatures(pkgInfo)

    fun isPackageSignatureTrusted(pkgInfo: PackageInfo): Boolean {
        val signatures = signatureTrust.getSignatures(pkgInfo)
        return signatureTrust.isTrusted(pkgInfo, signatures)
    }

    fun trustPackageSignature(packageName: String, versionCode: Long, signatureHash: String) {
        signatureTrust.trust(packageName, versionCode, signatureHash)
    }

    private fun isSourceByName(clazz: Class<*>): Boolean {
        var current: Class<*>? = clazz
        while (current != null) {
            if (current.interfaces.any { it.name.endsWith(".Source") }) return true
            current = current.superclass
        }
        return false
    }
}

data class ExtensionInfo(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val sourceClassName: String,
    val apkPath: String,
    val nsfw: Boolean = false,
    val icon: android.graphics.drawable.Drawable? = null,
    val extensionLibVersion: String? = null,
    val libCompatibility: ExtensionLibCompatibility = ExtensionLibCompatibility.Compatible,
    /** SHA-256 of the APK signing cert when known (Mihon-style trust). */
    val signatureHash: String? = null,
)

data class RepoIndex(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String = "",
    @SerializedName("extensions") val extensions: List<RepoExtensionEntry> = emptyList()
)

data class RepoExtensionSource(
    @SerializedName("baseUrl") val baseUrl: String?
)

data class RepoExtensionEntry(
    @SerializedName(value = "id", alternate = ["pkg"]) val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("baseUrl") val baseUrl: String? = null,
    @SerializedName("lang") val lang: String? = "en",
    @SerializedName("icon") val icon: String? = "🌐",
    @SerializedName("version") val version: String? = "1.0.0",
    @SerializedName("versionCode") val versionCode: Long = 0,
    @SerializedName(value = "fileName", alternate = ["apk"]) val fileName: String,
    /** Full APK URL from protobuf repos; preferred over repoUrl + /apk/fileName */
    @SerializedName("apkUrl") val apkUrl: String? = null,
    @SerializedName(value = "nsfw", alternate = ["isNsfw"]) val nsfw: Int = 0,
    @SerializedName("description") val description: String? = null,
    @SerializedName("extensionLib") val extensionLibVersion: String? = null,
    @SerializedName("sources") val sources: List<RepoExtensionSource>? = null,
    /** Parent repository base URL; injected after JSON/protobuf parse and persisted in cache. */
    @SerializedName("repoUrl") val repoUrl: String? = null,
)

/** Strip `index.json` / `index.pb` (and similar) so APK paths resolve against the repo root. */
internal fun repoIndexBaseUrl(repoUrl: String): String {
    val trimmed = repoUrl.trim().trimEnd('/')
    return if (trimmed.endsWith(".json", ignoreCase = true) || trimmed.endsWith(".pb", ignoreCase = true)) {
        trimmed.substringBeforeLast("/")
    } else {
        trimmed
    }
}

/**
 * Final APK download link: prefer an explicit [apkUrl], otherwise `{repoBase}/apk/{fileName}`.
 */
internal fun buildExtensionApkUrl(repoUrl: String, fileName: String, apkUrl: String? = null): String {
    apkUrl?.takeIf { it.isNotBlank() }?.let { return it }
    val file = fileName.trim()
    if (file.startsWith("http://") || file.startsWith("https://")) return file
    val relative = file.trimStart('/')
    val path = if (relative.startsWith("apk/", ignoreCase = true)) relative else "apk/$relative"
    return "${repoIndexBaseUrl(repoUrl)}/$path"
}

internal fun RepoExtensionEntry.withRepoUrl(repoUrl: String): RepoExtensionEntry = copy(repoUrl = repoUrl)

internal fun List<RepoExtensionEntry>.attachRepoUrl(repoUrl: String): List<RepoExtensionEntry> =
    map { it.withRepoUrl(repoUrl) }

/** Non-blank parent repository URL, or null when the entry cannot be installed from a repo. */
internal fun RepoExtensionEntry.installableRepoUrl(): String? = repoUrl?.takeIf { it.isNotBlank() }
