package com.radiant.webtoon.core.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    private val settings: Flow<Preferences> = context.dataStore.data.catch { error ->
        Log.e(TAG, "Settings file unreadable; using defaults for this read", error)
        emit(emptyPreferences())
    }

    private fun <T> readSetting(read: (Preferences) -> T): Flow<T> =
        settings.map { prefs ->
            try {
                read(prefs)
            } catch (e: ClassCastException) {
                Log.w(TAG, "Ignoring setting stored with an unexpected type", e)
                read(emptyPreferences())
            }
        }

    private fun <T> Preferences.safe(default: T, read: Preferences.() -> T): T =
        try {
            read()
        } catch (e: ClassCastException) {
            Log.w(TAG, "Ignoring setting stored with an unexpected type", e)
            default
        }

    companion object {
        private const val TAG = "SettingsManager"

        /**
         * No shipped preference key has been renamed. This map is the hook for a future
         * rename (`new key` → older names) so reads can copy the old value instead of crashing.
         */
        private val LEGACY_SETTING_KEYS: Map<String, List<String>> = emptyMap()
        val DEFAULT_REPO_URL = stringPreferencesKey("default_repo_url")
        val NSFW_FILTER = booleanPreferencesKey("nsfw_filter")
        val READING_DIRECTION = stringPreferencesKey("reading_direction")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LIBRARY_DISPLAY_MODE = stringPreferencesKey("library_display_mode")
        val DATE_FORMAT = stringPreferencesKey("date_format")
        
        val UPDATE_FREQUENCY = stringPreferencesKey("update_frequency")
        
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEYS_NAV = booleanPreferencesKey("volume_keys_nav")
        
        val DOWNLOAD_ONLY_ON_WIFI = booleanPreferencesKey("download_only_on_wifi")
        val AUTO_DELETE_READ = booleanPreferencesKey("auto_delete_read")
        val DOWNLOAD_LOCATION_URI = stringPreferencesKey("download_location_uri")
        val DOWNLOAD_LOCATION_NAME = stringPreferencesKey("download_location_name")

        val LOCAL_LIBRARY_URI = stringPreferencesKey("local_library_uri")
        val LOCAL_LIBRARY_URI_NAME = stringPreferencesKey("local_library_uri_name")
        val LOCAL_LIBRARY_PATH = stringPreferencesKey("local_library_path")
        
        val REQUIRE_BIOMETRICS = booleanPreferencesKey("require_biometrics")
        val ANILIST_TOKEN = stringPreferencesKey("anilist_token")

        val PINNED_SOURCES = stringSetPreferencesKey("pinned_sources")

        val CROP_BORDERS = booleanPreferencesKey("crop_borders")
        val CROP_BORDERS_WEBTOON = booleanPreferencesKey("crop_borders_webtoon")
        const val READING_MODE_OVERRIDE_PREFIX = "reading_mode_override:"
        val READER_BG = stringPreferencesKey("reader_bg")
        val INCOGNITO = booleanPreferencesKey("incognito")
        val FLAG_SECURE = booleanPreferencesKey("flag_secure")
        val LAST_LIBRARY_UPDATE = stringPreferencesKey("last_library_update")

        val EXTENSION_ADVANCED_MODE = booleanPreferencesKey("extension_advanced_mode")
        val EXTENSION_SOURCE_LANGS = stringSetPreferencesKey("extension_source_langs")
        val AUTO_UPDATE_EXTENSIONS = booleanPreferencesKey("auto_update_extensions")

        val READER_BRIGHTNESS = stringPreferencesKey("reader_brightness")
        val READER_TAP_ZONES = booleanPreferencesKey("reader_tap_zones")
        val READER_ORIENTATION = stringPreferencesKey("reader_orientation")
        val PURE_BLACK_READER = booleanPreferencesKey("pure_black_reader")
        val DOWNLOAD_INDEX_BACKFILLED = booleanPreferencesKey("download_index_backfilled")
        val PAGE_LIST_DEBUG_MODE = booleanPreferencesKey("page_list_debug_mode")
        val CUSTOM_USER_AGENT = stringPreferencesKey("custom_user_agent")

        /** JSON map of sourceId → override base URL (blank value = use extension default). */
        val SOURCE_BASE_URL_OVERRIDES = stringPreferencesKey("source_base_url_overrides")

        const val DEFAULT_REPO = "" // User will add their own repos

        private val AUTO_UPDATE_FREQUENCIES = setOf("6h", "12h", "daily")

        private val EXPECTED_KINDS: Map<String, StoredPreferenceValues.Kind> = mapOf(
            "default_repo_url" to StoredPreferenceValues.Kind.STRING,
            "nsfw_filter" to StoredPreferenceValues.Kind.BOOLEAN,
            "reading_direction" to StoredPreferenceValues.Kind.STRING,
            "theme_mode" to StoredPreferenceValues.Kind.STRING,
            "library_display_mode" to StoredPreferenceValues.Kind.STRING,
            "date_format" to StoredPreferenceValues.Kind.STRING,
            "update_frequency" to StoredPreferenceValues.Kind.STRING,
            "keep_screen_on" to StoredPreferenceValues.Kind.BOOLEAN,
            "volume_keys_nav" to StoredPreferenceValues.Kind.BOOLEAN,
            "download_only_on_wifi" to StoredPreferenceValues.Kind.BOOLEAN,
            "auto_delete_read" to StoredPreferenceValues.Kind.BOOLEAN,
            "download_location_uri" to StoredPreferenceValues.Kind.STRING,
            "download_location_name" to StoredPreferenceValues.Kind.STRING,
            "local_library_uri" to StoredPreferenceValues.Kind.STRING,
            "local_library_uri_name" to StoredPreferenceValues.Kind.STRING,
            "local_library_path" to StoredPreferenceValues.Kind.STRING,
            "require_biometrics" to StoredPreferenceValues.Kind.BOOLEAN,
            "anilist_token" to StoredPreferenceValues.Kind.STRING,
            "pinned_sources" to StoredPreferenceValues.Kind.STRING_SET,
            "crop_borders" to StoredPreferenceValues.Kind.BOOLEAN,
            "crop_borders_webtoon" to StoredPreferenceValues.Kind.BOOLEAN,
            "reader_bg" to StoredPreferenceValues.Kind.STRING,
            "incognito" to StoredPreferenceValues.Kind.BOOLEAN,
            "flag_secure" to StoredPreferenceValues.Kind.BOOLEAN,
            "last_library_update" to StoredPreferenceValues.Kind.STRING,
            "extension_advanced_mode" to StoredPreferenceValues.Kind.BOOLEAN,
            "extension_source_langs" to StoredPreferenceValues.Kind.STRING_SET,
            "auto_update_extensions" to StoredPreferenceValues.Kind.BOOLEAN,
            "reader_brightness" to StoredPreferenceValues.Kind.STRING,
            "reader_tap_zones" to StoredPreferenceValues.Kind.BOOLEAN,
            "reader_orientation" to StoredPreferenceValues.Kind.STRING,
            "pure_black_reader" to StoredPreferenceValues.Kind.BOOLEAN,
            "download_index_backfilled" to StoredPreferenceValues.Kind.BOOLEAN,
            "page_list_debug_mode" to StoredPreferenceValues.Kind.BOOLEAN,
            "custom_user_agent" to StoredPreferenceValues.Kind.STRING,
            "source_base_url_overrides" to StoredPreferenceValues.Kind.STRING,
        )
    }

    /**
     * Rewrites values stored under the wrong DataStore type and copies [LEGACY_SETTING_KEYS]
     * onto their current names. Safe to run on every launch.
     */
    suspend fun migrateStoredPreferenceTypes() {
        context.dataStore.edit { prefs ->
            val stored = prefs.asMap().entries.associate { it.key.name to it.value }
            val rewrites = buildMap {
                putAll(StoredPreferenceValues.rewrites(stored, EXPECTED_KINDS))
                putAll(
                    StoredPreferenceValues.prefixedStringRewrites(stored, READING_MODE_OVERRIDE_PREFIX),
                )
                putAll(
                    StoredPreferenceValues.legacyCopies(stored, LEGACY_SETTING_KEYS, EXPECTED_KINDS),
                )
            }
            if (rewrites.isEmpty()) return@edit
            for ((name, value) in rewrites) {
                prefs.asMap().keys.filter { it.name == name }.forEach { prefs.remove(it) }
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    is Set<*> -> prefs[stringSetPreferencesKey(name)] =
                        value.mapNotNull { it?.toString() }.toSet()
                    else -> prefs[stringPreferencesKey(name)] = value.toString()
                }
            }
            for ((newName, oldNames) in LEGACY_SETTING_KEYS) {
                if (!rewrites.containsKey(newName) && stored.containsKey(newName)) continue
                for (oldName in oldNames) {
                    if (oldName == newName) continue
                    prefs.asMap().keys.filter { it.name == oldName }.forEach { prefs.remove(it) }
                }
            }
        }
    }

    // Default repo URL
    val defaultRepoUrl: Flow<String> = readSetting { preferences ->
        preferences[DEFAULT_REPO_URL] ?: DEFAULT_REPO
    }

    suspend fun setDefaultRepoUrl(url: String) {
        context.dataStore.edit { it[DEFAULT_REPO_URL] = url }
    }

    // NSFW filter
    val nsfwFilter: Flow<Boolean> = readSetting { preferences ->
        preferences[NSFW_FILTER] ?: true  // Filter NSFW by default
    }

    suspend fun setNsfwFilter(enabled: Boolean) {
        context.dataStore.edit { it[NSFW_FILTER] = enabled }
    }

    // Reading direction: "vertical", "ltr", "rtl"
    val readingDirection: Flow<String> = readSetting { preferences ->
        preferences[READING_DIRECTION] ?: "vertical"
    }

    suspend fun setReadingDirection(direction: String) {
        context.dataStore.edit { it[READING_DIRECTION] = direction }
    }

    // Theme mode: "system", "light", "dark"
    val themeMode: Flow<String> = readSetting { preferences ->
        preferences[THEME_MODE] ?: "system"
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    // Library grid: "compact" (title over cover) or "comfortable" (title below cover)
    val libraryDisplayMode: Flow<String> = readSetting { it[LIBRARY_DISPLAY_MODE] ?: "compact" }
    suspend fun setLibraryDisplayMode(mode: String) {
        context.dataStore.edit { it[LIBRARY_DISPLAY_MODE] = mode }
    }

    // Appearance
    val dateFormat: Flow<String> = readSetting { it[DATE_FORMAT] ?: "MM/dd/yyyy" }
    suspend fun setDateFormat(format: String) {
        context.dataStore.edit { it[DATE_FORMAT] = format }
    }

    // Library updates are manual-only (Updates tab pull-to-refresh).
    val updateFrequency: Flow<String> = readSetting { it[UPDATE_FREQUENCY] ?: "manual" }

    /** One-shot migration: older installs may still have 6h/12h/daily stored. */
    suspend fun migrateAutoUpdateToManual() {
        context.dataStore.edit { prefs ->
            val current = prefs.safe<String?>(null) { this[UPDATE_FREQUENCY] }
            if (current == null || current in AUTO_UPDATE_FREQUENCIES) {
                prefs[UPDATE_FREQUENCY] = "manual"
            }
        }
    }

    // Reader
    val keepScreenOn: Flow<Boolean> = readSetting { it[KEEP_SCREEN_ON] ?: true }
    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    val volumeKeysNav: Flow<Boolean> = readSetting { it[VOLUME_KEYS_NAV] ?: false }
    suspend fun setVolumeKeysNav(enabled: Boolean) {
        context.dataStore.edit { it[VOLUME_KEYS_NAV] = enabled }
    }

    // Downloads
    val downloadOnlyOnWifi: Flow<Boolean> = readSetting { it[DOWNLOAD_ONLY_ON_WIFI] ?: false }
    suspend fun setDownloadOnlyOnWifi(enabled: Boolean) {
        context.dataStore.edit { it[DOWNLOAD_ONLY_ON_WIFI] = enabled }
    }

    val autoDeleteRead: Flow<Boolean> = readSetting { it[AUTO_DELETE_READ] ?: false }
    suspend fun setAutoDeleteRead(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_DELETE_READ] = enabled }
    }

    val isDownloadIndexBackfilled: Flow<Boolean> =
        readSetting { it[DOWNLOAD_INDEX_BACKFILLED] ?: false }

    suspend fun setDownloadIndexBackfilled(done: Boolean) {
        context.dataStore.edit { it[DOWNLOAD_INDEX_BACKFILLED] = done }
    }

    val downloadLocationUri: Flow<String?> = readSetting { it[DOWNLOAD_LOCATION_URI] }
    suspend fun setDownloadLocationUri(uri: String?) {
        context.dataStore.edit { 
            if (uri == null) it.remove(DOWNLOAD_LOCATION_URI) else it[DOWNLOAD_LOCATION_URI] = uri 
        }
    }

    val downloadLocationName: Flow<String?> = readSetting { it[DOWNLOAD_LOCATION_NAME] }
    suspend fun setDownloadLocationName(name: String?) {
        context.dataStore.edit { 
            if (name == null) it.remove(DOWNLOAD_LOCATION_NAME) else it[DOWNLOAD_LOCATION_NAME] = name 
        }
    }

    val localLibraryUri: Flow<String?> = readSetting { it[LOCAL_LIBRARY_URI] }
    suspend fun setLocalLibraryUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(LOCAL_LIBRARY_URI) else it[LOCAL_LIBRARY_URI] = uri
        }
    }

    val localLibraryUriName: Flow<String?> = readSetting { it[LOCAL_LIBRARY_URI_NAME] }
    suspend fun setLocalLibraryUriName(name: String?) {
        context.dataStore.edit {
            if (name == null) it.remove(LOCAL_LIBRARY_URI_NAME) else it[LOCAL_LIBRARY_URI_NAME] = name
        }
    }

    val localLibraryPath: Flow<String?> = readSetting { it[LOCAL_LIBRARY_PATH] }
    suspend fun setLocalLibraryPath(path: String?) {
        context.dataStore.edit {
            if (path == null) it.remove(LOCAL_LIBRARY_PATH) else it[LOCAL_LIBRARY_PATH] = path
        }
    }

    // Security
    val requireBiometrics: Flow<Boolean> = readSetting { it[REQUIRE_BIOMETRICS] ?: false }
    suspend fun setRequireBiometrics(enabled: Boolean) {
        context.dataStore.edit { it[REQUIRE_BIOMETRICS] = enabled }
    }

    val anilistToken: Flow<String?> = readSetting { it[ANILIST_TOKEN] }
    suspend fun setAniListToken(token: String?) {
        context.dataStore.edit { 
            if (token == null) it.remove(ANILIST_TOKEN) else it[ANILIST_TOKEN] = token 
        }
    }

    val pinnedSources: Flow<Set<String>> = readSetting { it[PINNED_SOURCES] ?: emptySet() }
    
    suspend fun togglePinnedSource(sourceId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences.safe(emptySet()) { this[PINNED_SOURCES] ?: emptySet() }
            if (current.contains(sourceId)) {
                preferences[PINNED_SOURCES] = current - sourceId
            } else {
                preferences[PINNED_SOURCES] = current + sourceId
            }
        }
    }

    suspend fun ensurePinnedSource(sourceId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences.safe(emptySet()) { this[PINNED_SOURCES] ?: emptySet() }
            if (!current.contains(sourceId)) {
                preferences[PINNED_SOURCES] = current + sourceId
            }
        }
    }

    suspend fun unpinSource(sourceId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences.safe(emptySet()) { this[PINNED_SOURCES] ?: emptySet() }
            if (current.contains(sourceId)) {
                preferences[PINNED_SOURCES] = current - sourceId
            }
        }
    }

    // Reader extras
    val cropBorders: Flow<Boolean> = readSetting { it[CROP_BORDERS] ?: false }
    suspend fun setCropBorders(enabled: Boolean) {
        context.dataStore.edit { it[CROP_BORDERS] = enabled }
    }

    /**
     * Crop borders for the webtoon / continuous-vertical viewers. [cropBorders] stays the setting
     * for the paged viewers (LTR / RTL / vertical pager).
     */
    val cropBordersWebtoon: Flow<Boolean> = readSetting { it[CROP_BORDERS_WEBTOON] ?: false }
    suspend fun setCropBordersWebtoon(enabled: Boolean) {
        context.dataStore.edit { it[CROP_BORDERS_WEBTOON] = enabled }
    }

    /**
     * Per-series reading-mode override (a [com.radiant.webtoon.ui.reader.viewer.ReadingMode.prefId]).
     * `null` means "use the default" ([readingDirection]).
     */
    fun readingModeOverride(seriesKey: String): Flow<String?> =
        readSetting { it[stringPreferencesKey(READING_MODE_OVERRIDE_PREFIX + seriesKey)] }

    suspend fun setReadingModeOverride(seriesKey: String, modeId: String?) {
        val key = stringPreferencesKey(READING_MODE_OVERRIDE_PREFIX + seriesKey)
        context.dataStore.edit { prefs ->
            if (modeId == null) prefs.remove(key) else prefs[key] = modeId
        }
    }

    val readerBg: Flow<String> = readSetting { it[READER_BG] ?: "black" }
    suspend fun setReaderBg(bg: String) {
        context.dataStore.edit { it[READER_BG] = bg }
    }

    val incognito: Flow<Boolean> = readSetting { it[INCOGNITO] ?: false }
    /** Alias for Mihon-parity naming. */
    val incognitoMode: Flow<Boolean> get() = incognito
    suspend fun setIncognito(enabled: Boolean) {
        context.dataStore.edit { it[INCOGNITO] = enabled }
    }

    val flagSecure: Flow<Boolean> = readSetting { it[FLAG_SECURE] ?: false }
    suspend fun setFlagSecure(enabled: Boolean) {
        context.dataStore.edit { it[FLAG_SECURE] = enabled }
    }

    val lastLibraryUpdate: Flow<Long> = readSetting {
        it[LAST_LIBRARY_UPDATE]?.toLongOrNull() ?: 0L
    }
    suspend fun setLastLibraryUpdate(timestamp: Long) {
        context.dataStore.edit { it[LAST_LIBRARY_UPDATE] = timestamp.toString() }
    }

    // Extensions — curated default; advanced mode opens full English/all catalog
    val extensionAdvancedMode: Flow<Boolean> = readSetting {
        it[EXTENSION_ADVANCED_MODE] ?: false
    }

    suspend fun setExtensionAdvancedMode(enabled: Boolean) {
        context.dataStore.edit { it[EXTENSION_ADVANCED_MODE] = enabled }
    }

    /** When true, background checks download and apply extension updates silently. */
    val autoUpdateExtensions: Flow<Boolean> = readSetting {
        it[AUTO_UPDATE_EXTENSIONS] ?: true
    }

    suspend fun setAutoUpdateExtensions(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_EXTENSIONS] = enabled }
    }

    /** Source languages to activate when advanced mode is on (default: English only). */
    val extensionSourceLangs: Flow<Set<String>> = readSetting {
        it[EXTENSION_SOURCE_LANGS] ?: setOf("en")
    }

    suspend fun setExtensionSourceLangs(langs: Set<String>) {
        context.dataStore.edit { it[EXTENSION_SOURCE_LANGS] = langs.ifEmpty { setOf("en") } }
    }

    // Reader extras (Mihon parity)
    val readerBrightness: Flow<String> = readSetting { it[READER_BRIGHTNESS] ?: "system" }
    suspend fun setReaderBrightness(value: String) {
        context.dataStore.edit { it[READER_BRIGHTNESS] = value }
    }

    val readerTapZones: Flow<Boolean> = readSetting { it[READER_TAP_ZONES] ?: true }
    suspend fun setReaderTapZones(enabled: Boolean) {
        context.dataStore.edit { it[READER_TAP_ZONES] = enabled }
    }

    /** `free` (follow system), `portrait`, `landscape` or `locked` (keep the current orientation). */
    val readerOrientation: Flow<String> = readSetting { it[READER_ORIENTATION] ?: "free" }
    suspend fun setReaderOrientation(value: String) {
        context.dataStore.edit { it[READER_ORIENTATION] = value }
    }

    val pureBlackReader: Flow<Boolean> = readSetting { it[PURE_BLACK_READER] ?: false }
    suspend fun setPureBlackReader(enabled: Boolean) {
        context.dataStore.edit { it[PURE_BLACK_READER] = enabled }
    }

    val pageListDebugMode: Flow<Boolean> = readSetting { it[PAGE_LIST_DEBUG_MODE] ?: false }
    suspend fun setPageListDebugMode(enabled: Boolean) {
        context.dataStore.edit { it[PAGE_LIST_DEBUG_MODE] = enabled }
    }

    /**
     * Optional override for OkHttp / Cloudflare WebView. Blank means use
     * [android.webkit.WebSettings.getDefaultUserAgent].
     */
    val customUserAgent: Flow<String> = readSetting { it[CUSTOM_USER_AGENT] ?: "" }

    suspend fun setCustomUserAgent(value: String) {
        context.dataStore.edit { it[CUSTOM_USER_AGENT] = value }
    }

    val sourceBaseUrlOverrides: Flow<Map<String, String>> = readSetting { prefs ->
        parseSourceBaseUrlOverrides(prefs[SOURCE_BASE_URL_OVERRIDES])
    }

    suspend fun setSourceBaseUrlOverride(sourceId: String, url: String?) {
        context.dataStore.edit { prefs ->
            val current = parseSourceBaseUrlOverrides(
                prefs.safe<String?>(null) { this[SOURCE_BASE_URL_OVERRIDES] },
            ).toMutableMap()
            val trimmed = url?.trim().orEmpty()
            if (trimmed.isBlank()) {
                current.remove(sourceId)
            } else {
                current[sourceId] = trimmed
            }
            if (current.isEmpty()) {
                prefs.remove(SOURCE_BASE_URL_OVERRIDES)
            } else {
                prefs[SOURCE_BASE_URL_OVERRIDES] = serializeSourceBaseUrlOverrides(current)
            }
        }
    }

    suspend fun clearSourceBaseUrlOverride(sourceId: String) {
        setSourceBaseUrlOverride(sourceId, null)
    }

    private fun parseSourceBaseUrlOverrides(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap {
                for (key in json.keys()) {
                    val value = json.optString(key, "").trim()
                    if (value.isNotBlank()) put(key, value)
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun serializeSourceBaseUrlOverrides(overrides: Map<String, String>): String {
        val json = org.json.JSONObject()
        overrides.forEach { (sourceId, url) ->
            if (url.isNotBlank()) json.put(sourceId, url)
        }
        return json.toString()
    }
}
