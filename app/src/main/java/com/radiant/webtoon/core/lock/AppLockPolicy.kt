package com.radiant.webtoon.core.lock

/**
 * Fail-closed app lock. Access is granted only after settings load and a successful
 * authentication when the lock is enabled.
 */
enum class AppLockState {
    Loading,
    Locked,
    Authenticating,
    Unlocked,
}

object AppLockPolicy {
    fun grantsAccess(state: AppLockState): Boolean = state == AppLockState.Unlocked

    fun afterSettingsLoaded(lockEnabled: Boolean, alreadyUnlocked: Boolean): AppLockState {
        if (!lockEnabled) return AppLockState.Unlocked
        return if (alreadyUnlocked) AppLockState.Unlocked else AppLockState.Locked
    }

    /** Hardware/vendor/cancel/lockout must not unlock. Only success does. */
    fun staysLockedOnAuthError(): Boolean = true

    fun canOfferPrompt(state: AppLockState): Boolean =
        state == AppLockState.Locked || state == AppLockState.Authenticating
}
