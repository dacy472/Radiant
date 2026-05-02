package com.radiant.webtoon.core.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.radiant.webtoon.core.database.Category
import com.radiant.webtoon.core.database.DownloadTask
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
    fun exportBackup(): File {
        val payload = BackupPayload(
            version = CURRENT_VERSION,
            generatedAt = System.currentTimeMillis(),
            library = runBlockingDb { database.mangaDao().getFavorites() },
            downloads = runBlockingDb { database.downloadDao().getAll() },
            categories = runBlockingDb { database.categoryDao().getAll() },
            categoryMappings = buildMappings()
        )
        validate(payload)
        val backupDir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val fileName = "webtoon-backup-${DATE_FORMAT.format(Date())}.json"
        val file = File(backupDir, fileName)
        file.writeText(gson.toJson(payload))
        return file
    }

    fun restoreBackup(file: File): RestoreResult {
        val payload = gson.fromJson(file.readText(), BackupPayload::class.java)
        validate(payload)
        runBlockingDb {
            payload.library.forEach { database.mangaDao().insert(it) }
            payload.downloads.forEach { database.downloadDao().insert(it) }
            payload.categories.forEach { database.categoryDao().insert(it) }
            payload.categoryMappings.forEach { database.categoryDao().addMapping(it) }
        }
        return RestoreResult(
            restoredLibraryCount = payload.library.size,
            restoredDownloadCount = payload.downloads.size,
            restoredCategoryCount = payload.categories.size
        )
    }

    private fun buildMappings(): List<LibraryCategoryCrossRef> {
        val library = runBlockingDb { database.mangaDao().getFavorites() }
        return library.flatMap { item ->
            runBlockingDb { database.categoryDao().getMappingsForWebtoon(item.url, item.source) }
        }
    }

    private fun validate(payload: BackupPayload) {
        require(payload.version in 1..CURRENT_VERSION) { "Unsupported backup version ${payload.version}" }
        require(payload.library.none { it.url.isBlank() || it.source.isBlank() }) { "Invalid library item in backup" }
    }

    private fun <T> runBlockingDb(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    companion object {
        const val CURRENT_VERSION = 1
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

data class BackupPayload(
    val version: Int,
    val generatedAt: Long,
    val library: List<Manga>,
    val downloads: List<DownloadTask>,
    val categories: List<Category>,
    val categoryMappings: List<LibraryCategoryCrossRef>,
)

data class RestoreResult(
    val restoredLibraryCount: Int,
    val restoredDownloadCount: Int,
    val restoredCategoryCount: Int,
)
