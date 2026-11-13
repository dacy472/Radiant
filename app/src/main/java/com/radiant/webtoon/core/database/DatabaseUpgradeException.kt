package com.radiant.webtoon.core.database

/**
 * Thrown when Room cannot migrate the on-disk database. The original
 * `webtoon_database` file is left intact (no destructive recreate).
 */
class DatabaseUpgradeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
