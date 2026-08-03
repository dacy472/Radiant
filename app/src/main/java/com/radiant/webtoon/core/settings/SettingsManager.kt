package com.radiant.webtoon.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val DEFAULT_REPO_URL = stringPreferencesKey("default_repo_url")
        val NSFW_FILTER = booleanPreferencesKey("nsfw_filter")
        val READING_DIRECTION = stringPreferencesKey("reading_direction")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DATE_FORMAT = stringPreferencesKey("date_format")
        
        val UPDATE_FREQUENCY = stringPreferencesKey("update_frequency")
        
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEYS_NAV = booleanPreferencesKey("volume_keys_nav")
        
        val DOWNLOAD_ONLY_ON_WIFI = booleanPreferencesKey("download_only_on_wifi")
        val AUTO_DELETE_READ = booleanPreferencesKey("auto_delete_read")
        val DOWNLOAD_LOCATION_URI = stringPreferencesKey("download_location_uri")
        val DOWNLOAD_LOCATION_NAME = stringPreferencesKey("download_location_name")
        
        val REQUIRE_BIOMETRICS = booleanPreferencesKey("require_biometrics")
        val ANILIST_TOKEN = stringPreferencesKey("anilist_token")

        val PINNED_SOURCES = stringSetPreferencesKey("pinned_sources")

        val CROP_BORDERS = booleanPreferencesKey("crop_borders")
        val READER_BG = stringPreferencesKey("reader_bg")
        val INCOGNITO = booleanPreferencesKey("incognito")
        val FLAG_SECURE = booleanPreferencesKey("flag_secure")
        val LAST_LIBRARY_UPDATE = stringPreferencesKey("last_library_update")

        const val DEFAULT_REPO = "" // User will add their own repos

        private val AUTO_UPDATE_FREQUENCIES = setOf("6h", "12h", "daily")
    }

    // Default repo URL
    val defaultRepoUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_REPO_URL] ?: DEFAULT_REPO
    }

    suspend fun setDefaultRepoUrl(url: String) {
        context.dataStore.edit { it[DEFAULT_REPO_URL] = url }
    }

    // NSFW filter
    val nsfwFilter: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NSFW_FILTER] ?: true  // Filter NSFW by default
    }

    suspend fun setNsfwFilter(enabled: Boolean) {
        context.dataStore.edit { it[NSFW_FILTER] = enabled }
    }

    // Reading direction: "vertical", "ltr", "rtl"
    val readingDirection: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[READING_DIRECTION] ?: "vertical"
    }

    suspend fun setReadingDirection(direction: String) {
        context.dataStore.edit { it[READING_DIRECTION] = direction }
    }

    // Theme mode: "system", "light", "dark"
    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_MODE] ?: "system"
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    // Appearance
    val dateFormat: Flow<String> = context.dataStore.data.map { it[DATE_FORMAT] ?: "MM/dd/yyyy" }
    suspend fun setDateFormat(format: String) {
        context.dataStore.edit { it[DATE_FORMAT] = format }
    }

    // Library updates are manual-only (Updates tab pull-to-refresh).
    val updateFrequency: Flow<String> = context.dataStore.data.map { it[UPDATE_FREQUENCY] ?: "manual" }

    /** One-shot migration: older installs may still have 6h/12h/daily stored. */
    suspend fun migrateAutoUpdateToManual() {
        context.dataStore.edit { prefs ->
            val current = prefs[UPDATE_FREQUENCY]
            if (current == null || current in AUTO_UPDATE_FREQUENCIES) {
                prefs[UPDATE_FREQUENCY] = "manual"
            }
        }
    }

    // Reader
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[KEEP_SCREEN_ON] ?: true }
    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    val volumeKeysNav: Flow<Boolean> = context.dataStore.data.map { it[VOLUME_KEYS_NAV] ?: false }
    suspend fun setVolumeKeysNav(enabled: Boolean) {
        context.dataStore.edit { it[VOLUME_KEYS_NAV] = enabled }
    }

    // Downloads
    val downloadOnlyOnWifi: Flow<Boolean> = context.dataStore.data.map { it[DOWNLOAD_ONLY_ON_WIFI] ?: false }
    suspend fun setDownloadOnlyOnWifi(enabled: Boolean) {
        context.dataStore.edit { it[DOWNLOAD_ONLY_ON_WIFI] = enabled }
    }

    val autoDeleteRead: Flow<Boolean> = context.dataStore.data.map { it[AUTO_DELETE_READ] ?: false }
    suspend fun setAutoDeleteRead(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_DELETE_READ] = enabled }
    }

    val downloadLocationUri: Flow<String?> = context.dataStore.data.map { it[DOWNLOAD_LOCATION_URI] }
    suspend fun setDownloadLocationUri(uri: String?) {
        context.dataStore.edit { 
            if (uri == null) it.remove(DOWNLOAD_LOCATION_URI) else it[DOWNLOAD_LOCATION_URI] = uri 
        }
    }

    val downloadLocationName: Flow<String?> = context.dataStore.data.map { it[DOWNLOAD_LOCATION_NAME] }
    suspend fun setDownloadLocationName(name: String?) {
        context.dataStore.edit { 
            if (name == null) it.remove(DOWNLOAD_LOCATION_NAME) else it[DOWNLOAD_LOCATION_NAME] = name 
        }
    }

    // Security
    val requireBiometrics: Flow<Boolean> = context.dataStore.data.map { it[REQUIRE_BIOMETRICS] ?: false }
    suspend fun setRequireBiometrics(enabled: Boolean) {
        context.dataStore.edit { it[REQUIRE_BIOMETRICS] = enabled }
    }

    val anilistToken: Flow<String?> = context.dataStore.data.map { it[ANILIST_TOKEN] }
    suspend fun setAniListToken(token: String?) {
        context.dataStore.edit { 
            if (token == null) it.remove(ANILIST_TOKEN) else it[ANILIST_TOKEN] = token 
        }
    }

    val pinnedSources: Flow<Set<String>> = context.dataStore.data.map { it[PINNED_SOURCES] ?: emptySet() }
    
    suspend fun togglePinnedSource(sourceId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[PINNED_SOURCES] ?: emptySet()
            if (current.contains(sourceId)) {
                preferences[PINNED_SOURCES] = current - sourceId
            } else {
                preferences[PINNED_SOURCES] = current + sourceId
            }
        }
    }

    // Reader extras
    val cropBorders: Flow<Boolean> = context.dataStore.data.map { it[CROP_BORDERS] ?: false }
    suspend fun setCropBorders(enabled: Boolean) {
        context.dataStore.edit { it[CROP_BORDERS] = enabled }
    }

    val readerBg: Flow<String> = context.dataStore.data.map { it[READER_BG] ?: "black" }
    suspend fun setReaderBg(bg: String) {
        context.dataStore.edit { it[READER_BG] = bg }
    }

    val incognito: Flow<Boolean> = context.dataStore.data.map { it[INCOGNITO] ?: false }
    /** Alias for Mihon-parity naming. */
    val incognitoMode: Flow<Boolean> get() = incognito
    suspend fun setIncognito(enabled: Boolean) {
        context.dataStore.edit { it[INCOGNITO] = enabled }
    }

    val flagSecure: Flow<Boolean> = context.dataStore.data.map { it[FLAG_SECURE] ?: false }
    suspend fun setFlagSecure(enabled: Boolean) {
        context.dataStore.edit { it[FLAG_SECURE] = enabled }
    }

    val lastLibraryUpdate: Flow<Long> = context.dataStore.data.map {
        it[LAST_LIBRARY_UPDATE]?.toLongOrNull() ?: 0L
    }
    suspend fun setLastLibraryUpdate(timestamp: Long) {
        context.dataStore.edit { it[LAST_LIBRARY_UPDATE] = timestamp.toString() }
    }
}
