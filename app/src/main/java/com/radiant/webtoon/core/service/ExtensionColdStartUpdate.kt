package com.radiant.webtoon.core.service

import android.util.Log
import com.radiant.webtoon.WebtoonApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs a one-shot extension update check when the app process cold-starts.
 * [Application.onCreate] is only invoked for a new process, so this does not
 * re-fire when the user returns from the background.
 */
object ExtensionColdStartUpdate {
    private const val TAG = "ExtensionColdStartUpdate"

    fun schedule(app: WebtoonApplication, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Cold-start extension purge and update check")
            app.extensionManager.purgeNonWhitelistedExtensions()
            app.extensionManager.loadInstalledExtensions()
            app.extensionManager.runBackgroundExtensionUpdateCheck()
            Log.d(TAG, "Cold-start extension purge and update check finished")
        }
    }
}
