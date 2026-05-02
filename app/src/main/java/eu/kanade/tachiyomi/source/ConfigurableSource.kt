package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen

/**
 * A source that has configurable settings/preferences.
 */
interface ConfigurableSource : Source {
    /**
     * Add the source's preferences to the [screen].
     */
    fun setupPreferenceScreen(screen: PreferenceScreen)
}
