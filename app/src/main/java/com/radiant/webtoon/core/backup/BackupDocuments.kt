package com.radiant.webtoon.core.backup

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.radiant.webtoon.core.database.Category
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.database.DownloadedChapter
import com.radiant.webtoon.core.database.ExtensionRepoEntity
import com.radiant.webtoon.core.database.History
import com.radiant.webtoon.core.database.LibraryCategoryCrossRef
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.database.Track

data class TypedPreference(
    @SerializedName("key") val key: String,
    @SerializedName("type") val type: String,
    @SerializedName("stringValue") val stringValue: String? = null,
    @SerializedName("boolValue") val boolValue: Boolean? = null,
    @SerializedName("longValue") val longValue: Long? = null,
    @SerializedName("doubleValue") val doubleValue: Double? = null,
    @SerializedName("stringSetValue") val stringSetValue: List<String>? = null,
)

data class SourcePreferencesBackup(
    @SerializedName("sourceId") val sourceId: String,
    @SerializedName("prefs") val prefs: List<TypedPreference> = emptyList(),
    /** Legacy v4 string-only map. */
    @SerializedName("legacyPrefs") val legacyPrefs: Map<String, String?> = emptyMap(),
)

data class BackupPayload(
    val version: Int,
    val generatedAt: Long,
    val library: List<Manga>,
    val downloads: List<DownloadTask>,
    val downloadedChapters: List<DownloadedChapter> = emptyList(),
    val categories: List<Category>,
    val categoryMappings: List<LibraryCategoryCrossRef>,
    val history: List<History> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val extensionRepos: List<ExtensionRepoEntity> = emptyList(),
    val installedExtensions: List<InstalledExtensionBackup> = emptyList(),
    val sourcePreferences: List<SourcePreferencesBackup> = emptyList(),
)

object BackupParser {
    fun parse(json: String, gson: Gson): BackupPayload {
        val root = gson.fromJson(json, JsonObject::class.java)
            ?: throw IllegalArgumentException("Backup is not a JSON object")
        val version = root.get("version")?.asInt
            ?: throw IllegalArgumentException("Backup is missing version")
        require(version in 1..BackupService.CURRENT_VERSION) { "Unsupported backup version $version" }

        fun <T> list(name: String, type: TypeToken<List<T>>): List<T> {
            val el = root.get(name) ?: return emptyList()
            if (el.isJsonNull) return emptyList()
            require(el.isJsonArray) { "Backup field $name must be an array" }
            return gson.fromJson(el, type.type) ?: emptyList()
        }

        val sourcePreferences = parseSourcePreferences(root, gson)
        return BackupPayload(
            version = version,
            generatedAt = root.get("generatedAt")?.asLong ?: 0L,
            library = list("library", object : TypeToken<List<Manga>>() {}),
            downloads = list("downloads", object : TypeToken<List<DownloadTask>>() {}),
            downloadedChapters = list("downloadedChapters", object : TypeToken<List<DownloadedChapter>>() {}),
            categories = list("categories", object : TypeToken<List<Category>>() {}),
            categoryMappings = list("categoryMappings", object : TypeToken<List<LibraryCategoryCrossRef>>() {}),
            history = list("history", object : TypeToken<List<History>>() {}),
            chapters = list("chapters", object : TypeToken<List<Chapter>>() {}),
            tracks = list("tracks", object : TypeToken<List<Track>>() {}),
            extensionRepos = list("extensionRepos", object : TypeToken<List<ExtensionRepoEntity>>() {}),
            installedExtensions = list("installedExtensions", object : TypeToken<List<InstalledExtensionBackup>>() {}),
            sourcePreferences = sourcePreferences,
        )
    }

    private fun parseSourcePreferences(root: JsonObject, gson: Gson): List<SourcePreferencesBackup> {
        val el = root.get("sourcePreferences") ?: return emptyList()
        if (el.isJsonNull) return emptyList()
        require(el.isJsonArray) { "sourcePreferences must be an array" }
        return el.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val sourceId = obj.get("sourceId")?.asString ?: return@mapNotNull null
            val prefsEl = obj.get("prefs")
            when {
                prefsEl == null || prefsEl.isJsonNull ->
                    SourcePreferencesBackup(sourceId = sourceId)
                prefsEl.isJsonArray -> SourcePreferencesBackup(
                    sourceId = sourceId,
                    prefs = gson.fromJson(prefsEl, object : TypeToken<List<TypedPreference>>() {}.type) ?: emptyList(),
                )
                prefsEl.isJsonObject -> {
                    val legacy = mutableMapOf<String, String?>()
                    for ((k, v) in prefsEl.asJsonObject.entrySet()) {
                        legacy[k] = if (v.isJsonNull) null else v.asString
                    }
                    SourcePreferencesBackup(sourceId = sourceId, legacyPrefs = legacy)
                }
                else -> SourcePreferencesBackup(sourceId = sourceId)
            }
        }
    }
}

object TypedPreferenceCodec {
    fun encode(key: String, value: Any?): TypedPreference? {
        if (value == null) return null
        return when (value) {
            is Boolean -> TypedPreference(key, "boolean", boolValue = value)
            is Int -> TypedPreference(key, "int", longValue = value.toLong())
            is Long -> TypedPreference(key, "long", longValue = value)
            is Float -> TypedPreference(key, "float", doubleValue = value.toDouble())
            is Double -> TypedPreference(key, "float", doubleValue = value)
            is Set<*> -> TypedPreference(
                key,
                "stringSet",
                stringSetValue = value.map { it.toString() },
            )
            else -> TypedPreference(key, "string", stringValue = value.toString())
        }
    }

    sealed class Write {
        data class BooleanVal(val value: Boolean) : Write()
        data class IntVal(val value: Int) : Write()
        data class LongVal(val value: Long) : Write()
        data class FloatVal(val value: Float) : Write()
        data class StringVal(val value: String?) : Write()
        data class StringSetVal(val value: Set<String>) : Write()
        data object Unrestorable : Write()
    }

    fun decodeWrite(pref: TypedPreference): Write {
        return when (pref.type) {
            "boolean" -> pref.boolValue?.let { Write.BooleanVal(it) } ?: Write.Unrestorable
            "int" -> {
                val value = pref.longValue ?: return Write.Unrestorable
                if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) Write.Unrestorable
                else Write.IntVal(value.toInt())
            }
            "long" -> pref.longValue?.let { Write.LongVal(it) } ?: Write.Unrestorable
            "float" -> pref.doubleValue?.let { Write.FloatVal(it.toFloat()) } ?: Write.Unrestorable
            "stringSet" -> Write.StringSetVal(pref.stringSetValue?.toSet() ?: emptySet())
            else -> Write.StringVal(pref.stringValue)
        }
    }
}
