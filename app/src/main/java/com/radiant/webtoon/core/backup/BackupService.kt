package com.radiant.webtoon.core.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.radiant.webtoon.core.database.Category
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.database.DownloadedChapter
import com.radiant.webtoon.core.database.ExtensionRepoEntity
import com.radiant.webtoon.core.database.InstalledExtensionEntity
import com.radiant.webtoon.core.database.History
import com.radiant.webtoon.core.database.LibraryCategoryCrossRef
import com.radiant.webtoon.core.database.Manga
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
        val payload = BackupPayload(
            version = CURRENT_VERSION,
            generatedAt = System.currentTimeMillis(),
            library = database.mangaDao().getFavorites(),
            downloads = database.downloadDao().getAll(),
            downloadedChapters = database.downloadedChapterDao().getAll(),
            categories = database.categoryDao().getAll(),
            categoryMappings = buildMappings(),
            history = database.historyDao().getAll(),
            extensionRepos = database.extensionRepoDao().getAll(),
            installedExtensions = database.installedExtensionDao().getAll().map {
                InstalledExtensionBackup(id = it.id, name = it.name, isTrusted = it.isTrusted)
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
        val payload = gson.fromJson(file.readText(), BackupPayload::class.java)
        validate(payload)
        payload.library.forEach { database.mangaDao().insert(it) }
        payload.downloads.forEach { database.downloadDao().insert(it) }
        payload.downloadedChapters.forEach { database.downloadedChapterDao().insert(it) }
        payload.categories.forEach { database.categoryDao().insert(it) }
        payload.categoryMappings.forEach { database.categoryDao().addMapping(it) }
        payload.history.forEach { database.historyDao().insert(it) }
        payload.extensionRepos.forEach { database.extensionRepoDao().insert(it) }
        payload.installedExtensions.forEach { ext ->
            database.installedExtensionDao().insert(
                InstalledExtensionEntity(id = ext.id, name = ext.name, isTrusted = ext.isTrusted),
            )
        }
        restoreSourcePreferences(payload.sourcePreferences)
        return RestoreResult(
            restoredLibraryCount = payload.library.size,
            restoredDownloadCount = payload.downloads.size + payload.downloadedChapters.size,
            restoredCategoryCount = payload.categories.size,
            restoredHistoryCount = payload.history.size,
            restoredExtensionRepoCount = payload.extensionRepos.size,
            restoredInstalledExtensionCount = payload.installedExtensions.size,
            restoredSourcePreferencesCount = payload.sourcePreferences.size,
        )
    }

    private fun collectSourcePreferences(): List<SourcePreferencesBackup> {
        val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return emptyList()
        return prefsDir.listFiles()
            ?.filter { it.name.startsWith("source_") && it.name.endsWith(".xml") }
            ?.mapNotNull { file ->
                val sourceId = file.name.removePrefix("source_").removeSuffix(".xml")
                val prefs = context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)
                val all = prefs.all.mapValues { (_, v) -> v?.toString() }
                SourcePreferencesBackup(sourceId = sourceId, prefs = all)
            }
            .orEmpty()
    }

    private fun restoreSourcePreferences(backups: List<SourcePreferencesBackup>) {
        backups.forEach { backup ->
            val prefs = context.getSharedPreferences("source_${backup.sourceId}", Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()
            backup.prefs.forEach { (key, value) ->
                if (value != null) editor.putString(key, value)
            }
            editor.apply()
        }
    }

    private suspend fun buildMappings(): List<LibraryCategoryCrossRef> {
        val library = database.mangaDao().getFavorites()
        return library.flatMap { item ->
            database.categoryDao().getMappingsForWebtoon(item.url, item.source)
        }
    }

    private fun validate(payload: BackupPayload) {
        require(payload.version in 1..CURRENT_VERSION) { "Unsupported backup version ${payload.version}" }
        require(payload.library.none { it.url.isBlank() || it.source.isBlank() }) { "Invalid library item in backup" }
    }

    companion object {
        const val CURRENT_VERSION = 4
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

data class BackupPayload(
    val version: Int,
    val generatedAt: Long,
    val library: List<Manga>,
    val downloads: List<DownloadTask>,
    val downloadedChapters: List<DownloadedChapter> = emptyList(),
    val categories: List<Category>,
    val categoryMappings: List<LibraryCategoryCrossRef>,
    val history: List<History> = emptyList(),
    val extensionRepos: List<ExtensionRepoEntity> = emptyList(),
    val installedExtensions: List<InstalledExtensionBackup> = emptyList(),
    val sourcePreferences: List<SourcePreferencesBackup> = emptyList(),
)

data class RestoreResult(
    val restoredLibraryCount: Int,
    val restoredDownloadCount: Int,
    val restoredCategoryCount: Int,
    val restoredHistoryCount: Int = 0,
    val restoredExtensionRepoCount: Int = 0,
    val restoredInstalledExtensionCount: Int = 0,
    val restoredSourcePreferencesCount: Int = 0,
)
