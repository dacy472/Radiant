package eu.kanade.tachiyomi.network.interceptor

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the resumed activity so the Cloudflare interceptor can attach an invisible WebView
 * (or a visible sheet) without a NEW_TASK full-screen jump.
 */
object ForegroundActivityHolder : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    val activity: Activity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is com.radiant.webtoon.ui.extensions.CloudflareBypassActivity) return
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) {
            resumed = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) {
            resumed = null
        }
    }
}
