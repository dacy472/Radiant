package com.radiant.webtoon.core.source

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.annotations.SerializedName
import dalvik.system.DexClassLoader
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
 * - Fetching repo indexes (JSON) to list available extensions
 * - Downloading APK files from repos
 * - Scanning installed extension APKs via PackageManager
 * - Loading extension classes via DexClassLoader / PathClassLoader
 */
class ExtensionLoader(private val context: Context) {

    private val tag = "ExtensionLoader"

    private val networkHelper: NetworkHelper by injectLazy()

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

    /** Read-only APK copies for DexClassLoader (Android 14+ rejects writable dex paths). */
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
        var url = repoUrl.trim()
        if (!url.endsWith(".json") && !url.endsWith(".pb")) {
            url = "${url.trimEnd('/')}/index.pb"
        }

        Log.d(tag, "Fetching repo index from: $url")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun fetchBytes(targetUrl: String): ByteArray {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", userAgent)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to fetch repo index: HTTP ${response.code}")
                }
                return response.body?.bytes() ?: throw Exception("Empty response body")
            }
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
            return@withContext filterRepoIndex(parseLegacyJsonIndex(String(bytes), url))
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
                ).toRepoEntries(url)
            }
            return@withContext filterRepoIndex(
                RepoIndex(
                    name = store.name.ifBlank { "Repository" },
                    description = store.badgeLabel,
                    extensions = entries,
                ),
            )
        }

        val entries = store.toRepoEntries(url)
        filterRepoIndex(
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
            RepoIndex(name = "Legacy Repository", description = "", extensions = extensions.onEach { it.repoUrl = repoUrl })
        } else {
            gson.fromJson(trimmedBody, RepoIndex::class.java).also { index ->
                index.extensions.forEach { it.repoUrl = repoUrl }
            }
        }
    }

    // ─── APK Download ───

    /**
     * Download an extension APK from the repo.
     * Returns the local File path of the downloaded APK.
     */
    suspend fun downloadExtensionApk(
        repoUrl: String,
        entry: RepoExtensionEntry
    ): File = withContext(Dispatchers.IO) {
        val downloadUrl = entry.apkUrl?.takeIf { it.isNotBlank() }
            ?: run {
                val baseRepoUrl = if (repoUrl.trim().endsWith(".json") || repoUrl.trim().endsWith(".pb")) {
                    repoUrl.trim().substringBeforeLast("/")
                } else {
                    repoUrl.trimEnd('/')
                }
                "$baseRepoUrl/apk/${entry.fileName}"
            }
        Log.d(tag, "Downloading extension APK: ${entry.name} from $downloadUrl")

        val request = Request.Builder().url(downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to download extension: HTTP ${response.code}")
            }

            val localFile = File(apkDownloadDir, entry.fileName)
            response.body?.let { body ->
                localFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            } ?: throw Exception("Empty response body")

            Log.d(tag, "Extension APK saved to: ${localFile.absolutePath}")
            localFile
        }
    }

    /**
     * Download an extension APK via system DownloadManager.
     * Use this for extensions that should be installed into the system.
     */
    fun downloadExtensionViaManager(repoUrl: String, entry: RepoExtensionEntry) {
        val downloadUrl = entry.apkUrl?.takeIf { it.isNotBlank() }
            ?: run {
                val baseRepoUrl = if (repoUrl.trim().endsWith(".json") || repoUrl.trim().endsWith(".pb")) {
                    repoUrl.trim().substringBeforeLast("/")
                } else {
                    repoUrl.trimEnd('/')
                }
                "$baseRepoUrl/apk/${entry.fileName}"
            }
        
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

    /**
     * Scan all installed packages on the device to find Tachiyomi/Mihon extension APKs.
     * Uses intent-based discovery (official way) and fallback prefix scanning.
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
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_META_DATA)
                val extInfo = parseExtensionPackage(pm, pkgInfo)
                if (extInfo != null) {
                    extensions.add(extInfo)
                    Log.i(tag, "MATCHED intent extension: ${extInfo.name} ($pkgName)")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing intent extension $pkgName", e)
            }
        }

        // 2. Fallback: Scan packages with specific prefixes (useful for dev or manual installs)
        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }

        for (pkgInfo in installedPackages) {
            val packageName = pkgInfo.packageName ?: continue
            if (seenPackages.contains(packageName)) continue
            if (extensionPrefixes.none { packageName.startsWith(it) }) continue

            Log.d(tag, "Checking prefix extension: $packageName")
            try {
                val extInfo = parseExtensionPackage(pm, pkgInfo)
                if (extInfo != null) {
                    extensions.add(extInfo)
                    seenPackages.add(packageName)
                    Log.i(tag, "MATCHED prefix extension: ${extInfo.name} ($packageName)")
                }
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
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
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
     * Deletes a local extension APK and its associated data.
     */
    fun deleteLocalExtension(packageName: String): Boolean {
        val file = File(extensionsDir, "$packageName.apk")
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

    /** Drop cached ClassLoader and read-only APK copy so the next load uses a fresh file. */
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
     * Sync a read-only copy into codeCacheDir and return that path for DexClassLoader.
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
     * Load the Source classes from an installed extension APK.
     * Returns a list of CatalogueSource instances.
     */
    fun loadExtensionSources(extInfo: ExtensionInfo): List<TachiyomiSource> {
        if (extInfo.libCompatibility == ExtensionLibCompatibility.TooNew) {
            throw IllegalStateException(
                "Extension ${extInfo.packageName} requires extension-lib ${extInfo.extensionLibVersion}; " +
                    "Radiant supports ${ExtensionLibCompat.SUPPORTED_VERSION}. Please update the app.",
            )
        }
        val isLocal = extInfo.apkPath.startsWith(context.filesDir.absolutePath)
        Log.d(tag, "Loading APK: ${extInfo.apkPath} exists=${File(extInfo.apkPath).exists()} local=$isLocal")

        // Always use DexClassLoader with a retained instance so loaded Sources stay valid.
        // Local APKs under filesDir are writable — Android 14+ rejects those as dex paths.
        val classLoader = classLoaders.getOrPut(extInfo.packageName) {
            val dexPath = prepareReadOnlyDexPath(extInfo.apkPath, extInfo.packageName)
            val optimizedDir = File(context.codeCacheDir, "ext_${extInfo.packageName}").apply {
                if (!exists()) mkdirs()
            }
            Log.d(tag, "DexClassLoader path for ${extInfo.packageName}: $dexPath")
            DexClassLoader(
                dexPath,
                optimizedDir.absolutePath,
                null,
                this::class.java.classLoader
            )
        }

        val sources = mutableListOf<TachiyomiSource>()

        // The source class names may be comma-separated
        val classNames = extInfo.sourceClassName.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        for (className in classNames) {
            try {
                // If the class name doesn't contain a package, prepend a default
                val fullClassName = if (className.startsWith(".")) {
                    extInfo.packageName + className
                } else {
                    className
                }

                Log.d(tag, "Instantiating class: $fullClassName")
                val clazz = Class.forName(fullClassName, false, classLoader)

                // Check if it's a Kotlin object (has INSTANCE field)
                val instance = try {
                    val instanceField = clazz.getDeclaredField("INSTANCE")
                    instanceField.isAccessible = true
                    instanceField.get(null)
                } catch (e: Exception) {
                    try {
                        clazz.getDeclaredConstructor().newInstance()
                    } catch (e2: Exception) {
                        Log.e(tag, "Failed to instantiate class: $fullClassName. No public constructor or INSTANCE found.", e2)
                        continue
                    }
                }

                // Check if it's a SourceFactory (returns multiple sources)
                val isSourceFactory = try {
                    // Check by method name to avoid ClassLoader mismatch with the interface
                    clazz.methods.any { it.name == "createSources" }
                } catch (e: Exception) {
                    false
                }

                if (instance is TachiyomiSource || isSourceByName(clazz)) {
                    Log.d(tag, "Class $fullClassName identifies as a Source")
                    val source = instance as? TachiyomiSource ?: instance as? eu.kanade.tachiyomi.source.Source
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
                                // Fallback: try to add them if they look like sources even if cast fails
                                for (item in rawSources) {
                                    if (item != null && isSourceByName(item.javaClass)) {
                                        (item as? TachiyomiSource)?.let {
                                            sources.add(it)
                                        }
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
                    Log.w(tag, "Class $fullClassName does NOT appear to be a Source or SourceFactory. Interfaces: ${clazz.interfaces.joinToString { it.name }}")
                }
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error loading source class $className", e)
            }
        }
        return sources
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
    @Transient val apkUrl: String? = null,
    @SerializedName(value = "nsfw", alternate = ["isNsfw"]) val nsfw: Int = 0,
    @SerializedName("description") val description: String? = null,
    @SerializedName("extensionLib") val extensionLibVersion: String? = null,
    @SerializedName("sources") val sources: List<RepoExtensionSource>? = null,
    @Transient var repoUrl: String? = null
)
