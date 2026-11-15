package com.radiant.webtoon.core.service

import android.util.Log
import com.radiant.webtoon.WebtoonApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs a one-shot extension PackageManager sync + update check when the app process cold-starts.
 * [Application.onCreate] is only invoked for a new process, so this does not
 * re-fire when the user returns from the background.
 */
object ExtensionColdStartUpdate {
    private const val TAG = "ExtensionColdStartUpdate"

    fun schedule(app: WebtoonApplication, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Cold-start extension PackageManager sync and update check")
            // Each step is isolated so a missing APK or a bad Room row cannot abort the rest
            // or crash the process during an in-place upgrade.
            runStep("sync") { app.extensionManager.syncSystemInstalledExtensions() }
            runStep("purge") { app.extensionManager.purgeNonWhitelistedExtensions() }
            runStep("load") { app.extensionManager.loadInstalledExtensions(force = true) }
            runStep("catalog") { app.extensionManager.findAvailableExtensions() }
            runStep("updates") { app.extensionManager.runBackgroundExtensionUpdateCheck() }
            Log.d(TAG, "Cold-start extension sync and update check finished")
        }
    }

    private suspend inline fun runStep(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Cold-start extension step '$name' failed", e)
        }
    }
}
