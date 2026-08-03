package eu.kanade.tachiyomi.source

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A source that has configurable settings/preferences.
 */
interface ConfigurableSource : Source {
    /**
     * Gets instance of [SharedPreferences] scoped to the specific source. Matches the shape
     * of modern Mihon/Keiyoushi's extensions-lib so extensions relying on the inherited default
     * (instead of overriding it themselves) resolve correctly instead of throwing
     * AbstractMethodError/NoSuchMethodError.
     *
     * @since extensions-lib 1.5
     */
    fun getSourcePreferences(): SharedPreferences =
        Injekt.get<Application>().getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)

    /**
     * Add the source's preferences to the [screen].
     */
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableSource.preferenceKey(): String = "source_$id"

// TODO: use getSourcePreferences once all extensions are on ext-lib 1.5
fun ConfigurableSource.sourcePreferences(): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)
