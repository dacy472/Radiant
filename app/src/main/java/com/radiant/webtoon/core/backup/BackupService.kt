package com.radiant.webtoon.core.backup

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.radiant.webtoon.core.database.InstalledExtensionEntity
import com.radiant.webtoon.core.database.WebtoonDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupService(
    private val context: Context,
    private val database: WebtoonDatabase,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    suspend fun exportBackup(): File {
        val history = database.historyDao().getAll()
        val library = database.mangaDao().getFavorites()
        val historyKeys = history.map { it.source_id to it.webtoon_id }.toSet()
        val libraryKeys = library.map { it.source to it.url }.toSet()
        val chapters = database.chapterDao().getAll().filter { chapter ->
            (chapter.source to chapter.manga_id) in libraryKeys ||
                (chapter.source to chapter.manga_id) in historyKeys
        }
        val payload = BackupPayload(
            version = CURRENT_VERSION,
            generatedAt = System.currentTimeMillis(),
            library = library,
            downloads = database.downloadDao().getAll(),
            downloadedChapters = database.downloadedChapterDao().getAll(),
            categories = database.categoryDao().getAll(),
            categoryMappings = buildMappings(),
            history = history,
            chapters = chapters,
            tracks = database.trackDao().getAll(),
            extensionRepos = database.extensionRepoDao().getAll(),
            installedExtensions = database.installedExtensionDao().getAll().map {
                InstalledExtensionBackup(id = it.id, name = it.name, isTrusted = false)
            },
            sourcePreferences = collectSourcePreferences(),
        )
        validate(payload)
        val backupDir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val fileName = "webtoon-backup-${DATE_FORMAT.format(Date())}.json"
        val file = File(backupDir, fileName)
        file.writeText(gson.toJson(payload))
        return file
    }

    suspend fun restoreBackup(file: File): RestoreResult {
        val payload = BackupParser.parse(file.readText(), gson)
        validate(payload)
        var missingMedia = 0
        database.withTransaction {
            payload.library.forEach { database.mangaDao().insertReplacing(it) }
            payload.downloads.forEach { database.downloadDao().insert(it) }
            payload.downloadedChapters.forEach { row ->
                val present = File(row.localDirectory).exists()
                if (!present) missingMedia++
                database.downloadedChapterDao().insert(row)
            }
            payload.categories.forEach { database.categoryDao().insert(it) }
            payload.categoryMappings.forEach { database.categoryDao().addMapping(it) }
            payload.history.forEach { database.historyDao().insert(it) }
            payload.chapters.forEach { database.chapterDao().insertAll(listOf(it)) }
            payload.tracks.forEach { database.trackDao().insert(it) }
            payload.extensionRepos.forEach { database.extensionRepoDao().insert(it) }
            payload.installedExtensions.forEach { ext ->
                database.installedExtensionDao().insert(
                    InstalledExtensionEntity(id = ext.id, name = ext.name, isTrusted = false),
                )
            }
        }
        val unrestorable = restoreSourcePreferences(payload.sourcePreferences)
        return RestoreResult(
            restoredLibraryCount = payload.library.size,
            restoredDownloadCount = payload.downloads.size + payload.downloadedChapters.size,
            restoredCategoryCount = payload.categories.size,
            restoredHistoryCount = payload.history.size,
            restoredChapterCount = payload.chapters.size,
            restoredTrackCount = payload.tracks.size,
            restoredExtensionRepoCount = payload.extensionRepos.size,
            restoredInstalledExtensionCount = payload.installedExtensions.size,
            restoredSourcePreferencesCount = payload.sourcePreferences.size,
            missingMediaCount = missingMedia,
            unrestorablePreferenceKeys = unrestorable,
        )
    }

    private fun collectSourcePreferences(): List<SourcePreferencesBackup> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return emptyList()
        return prefsDir.listFiles()
            ?.filter { it.name.startsWith("source_") && it.name.endsWith(".xml") }
            ?.mapNotNull { file ->
                val sourceId = file.name.removePrefix("source_").removeSuffix(".xml")
                val prefs = context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)
                val typed = prefs.all.mapNotNull { (key, value) -> TypedPreferenceCodec.encode(key, value) }
                SourcePreferencesBackup(sourceId = sourceId, prefs = typed)
            }
            .orEmpty()
    }

    private fun restoreSourcePreferences(backups: List<SourcePreferencesBackup>): List<String> {
        val unrestorable = mutableListOf<String>()
        backups.forEach { backup ->
            val prefs = context.getSharedPreferences("source_${backup.sourceId}", Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()
            backup.prefs.forEach { pref ->
                when (val write = TypedPreferenceCodec.decodeWrite(pref)) {
                    is TypedPreferenceCodec.Write.BooleanVal -> editor.putBoolean(pref.key, write.value)
                    is TypedPreferenceCodec.Write.IntVal -> editor.putInt(pref.key, write.value)
                    is TypedPreferenceCodec.Write.LongVal -> editor.putLong(pref.key, write.value)
                    is TypedPreferenceCodec.Write.FloatVal -> editor.putFloat(pref.key, write.value)
                    is TypedPreferenceCodec.Write.StringVal -> editor.putString(pref.key, write.value)
                    is TypedPreferenceCodec.Write.StringSetVal -> editor.putStringSet(pref.key, write.value)
                    TypedPreferenceCodec.Write.Unrestorable -> unrestorable.add("${backup.sourceId}:${pref.key}")
                }
            }
            backup.legacyPrefs.forEach { (key, value) ->
                if (value != null) editor.putString(key, value)
                unrestorable.add("${backup.sourceId}:$key")
            }
            editor.commit()
        }
        return unrestorable
    }

    private suspend fun buildMappings() =
        database.mangaDao().getFavorites().flatMap { item ->
            database.categoryDao().getMappingsForWebtoon(item.url, item.source)
        }

    private fun validate(payload: BackupPayload) {
        require(payload.version in 1..CURRENT_VERSION) { "Unsupported backup version ${payload.version}" }
        require(payload.library.none { it.url.isBlank() || it.source.isBlank() }) { "Invalid library item in backup" }
        val chapterKeys = payload.chapters.groupingBy { Triple(it.source, it.manga_id, it.url) }.eachCount()
        require(chapterKeys.values.all { it == 1 }) { "Duplicate chapter identities in backup" }
    }

    companion object {
        const val CURRENT_VERSION = 5
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

data class RestoreResult(
    val restoredLibraryCount: Int,
    val restoredDownloadCount: Int,
    val restoredCategoryCount: Int,
    val restoredHistoryCount: Int = 0,
    val restoredChapterCount: Int = 0,
    val restoredTrackCount: Int = 0,
    val restoredExtensionRepoCount: Int = 0,
    val restoredInstalledExtensionCount: Int = 0,
    val restoredSourcePreferencesCount: Int = 0,
    val missingMediaCount: Int = 0,
    val unrestorablePreferenceKeys: List<String> = emptyList(),
)
