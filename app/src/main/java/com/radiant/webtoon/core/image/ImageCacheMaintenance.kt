package com.radiant.webtoon.core.image

import android.content.Context
import android.util.Log
import com.radiant.webtoon.core.settings.StoredPreferenceValues
import java.io.File

/**
 * One-off disk cache purges tied to app upgrades.
 *
 * v0.1.2 and earlier ran `FlameComicsJsonRepairInterceptor` on `cdn.flamecomics.xyz` images and
 * re-encoded their bytes through a String, so Coil persisted corrupted covers/pages to disk.
 * Those entries would otherwise look "broken" until evicted. Bump [IMAGE_CACHE_GENERATION]
 * whenever a bug could have poisoned the cache again.
 */
object ImageCacheMaintenance {
    private const val TAG = "ImageCacheMaintenance"
    private const val PREFS = "radiant_cache_maintenance"
    private const val KEY_GENERATION = "image_cache_generation"

    /** Increment when previously cached image bytes must be discarded. */
    const val IMAGE_CACHE_GENERATION = 2

    fun runIfNeeded(context: Context) {
        try {
            runIfNeededUnchecked(context)
        } catch (e: Exception) {
            // A bad pref type must not crash Application.onCreate after an upgrade.
            Log.e(TAG, "Image cache maintenance skipped", e)
        }
    }

    private fun runIfNeededUnchecked(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.all
        val current = StoredPreferenceValues.asInt(stored[KEY_GENERATION], 1)
        if (stored.containsKey(KEY_GENERATION) && stored[KEY_GENERATION] !is Int) {
            prefs.edit().putInt(KEY_GENERATION, current).apply()
        }
        if (current >= IMAGE_CACHE_GENERATION) return
        val removed = listOf(
            RadiantImageLoader.diskCacheDir(context),
            File(context.cacheDir, "reader_pages"),
        ).sumOf { dir -> purge(dir) }
        Log.i(TAG, "Image cache generation $current -> $IMAGE_CACHE_GENERATION, removed $removed files")
        prefs.edit().putInt(KEY_GENERATION, IMAGE_CACHE_GENERATION).apply()
    }

    private fun purge(dir: File): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        dir.walkBottomUp().forEach { file ->
            if (file != dir && file.delete()) count++
        }
        return count
    }
}
