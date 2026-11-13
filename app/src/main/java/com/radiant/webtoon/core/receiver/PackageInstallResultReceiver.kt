package com.radiant.webtoon.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.radiant.webtoon.core.source.PackageInstallResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives [PackageInstaller] session results for extension installs/updates.
 *
 * Not exported: the [android.app.PendingIntent] handed to the session is explicit, so only the
 * system installer (acting on our behalf) can reach this receiver. Results are delivered to the
 * coroutine waiting on the session via [register].
 */
class PackageInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val systemMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val otherPackage = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    complete(sessionId, PackageInstallResult.Failure(status, systemMessage, otherPackage))
                    return
                }
                try {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not launch install confirmation: ${e.javaClass.simpleName}")
                    complete(
                        sessionId,
                        PackageInstallResult.Failure(PackageInstaller.STATUS_FAILURE_ABORTED, e.message, otherPackage),
                    )
                }
                // Final status arrives in a later broadcast for the same session.
            }
            PackageInstaller.STATUS_SUCCESS -> complete(sessionId, PackageInstallResult.Success)
            else -> complete(sessionId, PackageInstallResult.Failure(status, systemMessage, otherPackage))
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "PkgInstallReceiver"

        const val ACTION_INSTALL_RESULT = "com.radiant.webtoon.action.EXTENSION_INSTALL_RESULT"

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<PackageInstallResult>>()

        /** Call right after creating a session, before committing it. */
        fun register(sessionId: Int): CompletableDeferred<PackageInstallResult> =
            CompletableDeferred<PackageInstallResult>().also { pending[sessionId] = it }

        fun unregister(sessionId: Int) {
            pending.remove(sessionId)
        }

        private fun complete(sessionId: Int, result: PackageInstallResult) {
            val waiter = pending.remove(sessionId)
            if (waiter == null) {
                Log.d(TAG, "No waiter for install session $sessionId (process restarted?)")
                return
            }
            waiter.complete(result)
        }
    }
}
