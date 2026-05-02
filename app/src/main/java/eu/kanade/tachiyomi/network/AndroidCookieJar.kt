package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * A persistent CookieJar that saves cookies to SharedPreferences.
 * This ensures that sessions (like login or Cloudflare clearance) survive app restarts.
 */
class AndroidCookieJar(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences("network_cookies", Context.MODE_PRIVATE)

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val domain = url.host
        val currentCookies = loadForRequest(url).toMutableList()

        for (newCookie in cookies) {
            // Remove existing cookie with same name for this domain to avoid duplicates
            currentCookies.removeAll { it.name == newCookie.name }
            currentCookies.add(newCookie)
        }

        // Filter out expired cookies before saving
        val validCookies = currentCookies.filter { it.expiresAt > System.currentTimeMillis() }

        prefs.edit().putStringSet(domain, validCookies.map { it.toString() }.toSet()).apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val domain = url.host
        val cookieStrings = prefs.getStringSet(domain, null) ?: return emptyList()

        return cookieStrings.mapNotNull { 
            Cookie.parse(url, it)
        }.filter { 
            it.expiresAt > System.currentTimeMillis() 
        }
    }
}
