package eu.kanade.tachiyomi.core.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Bridge for Tachiyomi's PreferenceStore. 
 * Extensions use this to manage their own settings.
 */
interface PreferenceStore {
    fun getString(key: String, defaultValue: String): String
    fun setString(key: String, value: String)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun setBoolean(key: String, value: Boolean)
    fun getInt(key: String, defaultValue: Int): Int
    fun setInt(key: String, value: Int)
}

class AndroidPreferenceStore(context: Context, val name: String? = null) : PreferenceStore {
    private val prefs: SharedPreferences = if (name != null) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    } else {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    override fun getString(key: String, defaultValue: String): String = prefs.getString(key, defaultValue) ?: defaultValue
    override fun setString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    override fun setBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    
    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)
    override fun setInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
}
