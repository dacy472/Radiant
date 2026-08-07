package com.radiant.webtoon.core.local

import android.content.Context
import android.util.Log
import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalLibraryManager(
    context: Context,
    private val settingsManager: SettingsManager,
    database: WebtoonDatabase,
    private val catalog: LocalLibraryCatalog,
) {
    companion object {
        private const val TAG = "LocalLibraryManager"
    }

    private val scanner = LocalLibraryScanner(context, settingsManager)
    private val sync = LocalLibrarySync(
        database = database,
        mangaDao = database.mangaDao(),
        chapterDao = database.chapterDao(),
        downloadedChapterDao = database.downloadedChapterDao(),
    )
    private val refreshMutex = Mutex()

    suspend fun refresh(): ScanResult = refreshMutex.withLock {
        val scan = scanner.scan()
        if (scan.error != null) {
            Log.w(TAG, "Scan completed with error: ${scan.error}")
        } else {
            Log.i(TAG, "Scanned ${scan.series.size} local series at ${scan.rootPath}")
        }
        catalog.update(scan)
        if (scan.error == null) {
            sync.sync(scan)
        }
        scan
    }
}
