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
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.*
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source as TachiyomiSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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

    // ─── Known extension package prefixes ───

    private val extensionPrefixes = listOf(
        "eu.kanade.tachiyomi.extension.",
        "tachiyomi.extension.",
        "mihon.extension."
    )

    // ─── Repo Index Fetching ───

    suspend fun fetchRepoIndex(repoUrl: String): RepoIndex = withContext(Dispatchers.IO) {
        var url = repoUrl.trim()
        if (!url.endsWith(".json")) {
            url = "${url.trimEnd('/')}/index.min.json"
        }

        Log.d(tag, "Fetching repo index from: $url")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        var request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        var response = client.newCall(request).execute()

        // Fallback to index.json if index.min.json fails
        if (!response.isSuccessful && url.endsWith("index.min.json")) {
            url = url.replace("index.min.json", "index.json")
            Log.d(tag, "Fallback fetching repo index from: $url")
            request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            response = client.newCall(request).execute()
        }

        if (!response.isSuccessful) {
            throw Exception("Failed to fetch repo index: HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("Empty response body")
        val trimmedBody = body.trimStart()

        if (trimmedBody.startsWith("[")) {
            // Direct array of extensions
            val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
            val extensions: List<RepoExtensionEntry> = gson.fromJson(trimmedBody, listType)
            RepoIndex(name = "Imported Repository", description = "", extensions = extensions)
        } else {
            gson.fromJson(trimmedBody, RepoIndex::class.java)
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
        val baseRepoUrl = if (repoUrl.trim().endsWith(".json"))
            repoUrl.trim().substringBeforeLast("/")
        else
            repoUrl.trimEnd('/')

        val fileName = entry.fileName
        val downloadUrl = "$baseRepoUrl/apk/$fileName"
        Log.d(tag, "Downloading extension APK: ${entry.name} from $downloadUrl")

        val request = Request.Builder().url(downloadUrl).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Failed to download extension: HTTP ${response.code}")
        }

        val localFile = File(apkDownloadDir, fileName)
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

    /**
     * Download an extension APK via system DownloadManager.
     * Use this for extensions that should be installed into the system.
     */
    fun downloadExtensionViaManager(repoUrl: String, entry: RepoExtensionEntry) {
        val baseRepoUrl = if (repoUrl.trim().endsWith(".json"))
            repoUrl.trim().substringBeforeLast("/")
        else
            repoUrl.trimEnd('/')

        val downloadUrl = "$baseRepoUrl/apk/${entry.fileName}"
        
        val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl)).apply {
            setTitle("Radiant Extension: ${entry.name}")
            setDescription("Downloading ${entry.name}...")
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, entry.fileName)
            setMimeType("application/vnd.android.package-archive")
        }
        
        downloadManager.enqueue(request)
        Log.i(tag, "Enqueued extension download with manager: ${entry.name}")
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
                    // Critical: populate applicationInfo fields so parseExtensionPackage can find resources/metadata
                    pkgInfo.applicationInfo.sourceDir = file.absolutePath
                    pkgInfo.applicationInfo.publicSourceDir = file.absolutePath
                    
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
        return extensions
    }

    /**
     * Deletes a local extension APK and its associated data.
     */
    fun deleteLocalExtension(packageName: String): Boolean {
        val file = File(extensionsDir, "$packageName.apk")
        if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                Log.i(tag, "Deleted local extension APK: $packageName")
                // Also clean up any optimized dex files if they exist
                val dexDir = File(context.codeCacheDir, "ext_$packageName")
                if (dexDir.exists()) dexDir.deleteRecursively()
                return true
            }
        }
        return false
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

        val nsfw = metadata.getInt("tachiyomi.extension.nsfw", 0) == 1 || 
                   metadata.getBoolean("tachiyomi.extension.nsfw", false)
        
        // Removed unused hasReadme and hasChangelog

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
            icon = icon
        )
    }

    // ─── APK Class Loading ───

    /**
     * Load the Source classes from an installed extension APK.
     * Returns a list of CatalogueSource instances.
     */
    fun loadExtensionSources(extInfo: ExtensionInfo): List<TachiyomiSource> {
        val isLocal = extInfo.apkPath.startsWith(context.filesDir.absolutePath)
        Log.e("RADIANT_DEBUG", "Attempting to load APK: ${extInfo.apkPath} | Exists: ${java.io.File(extInfo.apkPath).exists()} | IsLocal: $isLocal")
        
        val classLoader = if (isLocal) {
            // Use DexClassLoader for local files to allow optimized dex storage in codeCacheDir
            val optimizedDir = File(context.codeCacheDir, "ext_${extInfo.packageName}").apply { if (!exists()) mkdirs() }
            DexClassLoader(extInfo.apkPath, optimizedDir.absolutePath, null, this::class.java.classLoader)
        } else {
            PathClassLoader(extInfo.apkPath, null, this::class.java.classLoader)
        }
        
        Log.e("RADIANT_DEBUG", "ClassLoader initialized with parent: ${this::class.java.classLoader.javaClass.simpleName}")
        
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

                Log.e("RADIANT_DEBUG", "Attempting to instantiate class: $fullClassName")
                val clazz = Class.forName(fullClassName, false, classLoader)
                Log.d(tag, "Loaded class: $fullClassName")

                // Check if it's a Kotlin object (has INSTANCE field)
                val instance = try {
                    val instanceField = clazz.getDeclaredField("INSTANCE")
                    instanceField.setAccessible(true)
                    val inst = instanceField.get(null)
                    Log.e("RADIANT_DEBUG", "Successfully instantiated via INSTANCE: $fullClassName")
                    inst
                } catch (e: Exception) {
                    try {
                        val inst = clazz.getDeclaredConstructor().newInstance()
                        Log.e("RADIANT_DEBUG", "Successfully instantiated via constructor: $fullClassName")
                        inst
                    } catch (e2: Exception) {
                        Log.e("RADIANT_DEBUG", "ClassLoader CRASHED for $fullClassName: ${e2.message}", e2)
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
                        Log.e("RADIANT_DEBUG", "Successfully loaded Source: ${source.name}")
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
                                    Log.e("RADIANT_DEBUG", "Successfully loaded Source: ${source.name} (from factory)")
                                    sources.add(source)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("RADIANT_DEBUG", "ClassLoader CRASHED for $fullClassName (Factory): ${e.message}", e)
                        Log.e(tag, "Failed to invoke createSources on $fullClassName", e)
                    }
                } else {
                    Log.w(tag, "Class $fullClassName does NOT appear to be a Source or SourceFactory. Interfaces: ${clazz.interfaces.joinToString { it.name }}")
                }
            } catch (e: Exception) {
                Log.e("RADIANT_DEBUG", "Unexpected error loading source class $className: ${e.message}", e)
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
    val icon: android.graphics.drawable.Drawable? = null
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
    @SerializedName(value = "fileName", alternate = ["apk"]) val fileName: String,
    @SerializedName(value = "nsfw", alternate = ["isNsfw"]) val nsfw: Int = 0,
    @SerializedName("description") val description: String? = null,
    @SerializedName("sources") val sources: List<RepoExtensionSource>? = null,
    @Transient var repoUrl: String? = null
)
