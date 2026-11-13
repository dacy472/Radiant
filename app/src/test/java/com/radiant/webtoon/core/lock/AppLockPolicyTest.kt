package com.radiant.webtoon.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {

    @Test
    fun loadingDoesNotGrantAccess() {
        assertFalse(AppLockPolicy.grantsAccess(AppLockState.Loading))
        assertFalse(AppLockPolicy.grantsAccess(AppLockState.Locked))
        assertFalse(AppLockPolicy.grantsAccess(AppLockState.Authenticating))
        assertTrue(AppLockPolicy.grantsAccess(AppLockState.Unlocked))
    }

    @Test
    fun enabledLockStartsLockedUntilSuccess() {
        assertEquals(AppLockState.Locked, AppLockPolicy.afterSettingsLoaded(lockEnabled = true, alreadyUnlocked = false))
        assertEquals(AppLockState.Unlocked, AppLockPolicy.afterSettingsLoaded(lockEnabled = true, alreadyUnlocked = true))
        assertEquals(AppLockState.Unlocked, AppLockPolicy.afterSettingsLoaded(lockEnabled = false, alreadyUnlocked = false))
    }

    @Test
    fun authErrorsNeverUnlock() {
        assertTrue(AppLockPolicy.staysLockedOnAuthError())
    }
}
