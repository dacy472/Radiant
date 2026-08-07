package eu.kanade.tachiyomi

import com.radiant.webtoon.BuildConfig

/**
 * Host app version info exposed to extensions (matches Mihon/TachiyomiX contract).
 */
object AppInfo {
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE
    fun getVersionName(): String = BuildConfig.VERSION_NAME
}
