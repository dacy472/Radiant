package com.radiant.webtoon.core.source

/**
 * Decides which installed-extension rows to drop when the Room table and the on-disk
 * APK cache disagree. A row stays when the package is still installed, the APK file is
 * still present (even if it failed to parse), or the extensions directory could not be listed.
 */
object InstalledExtensionReconcile {
    fun rowIdsToRemove(
        rowIds: Collection<String>,
        stillPresent: Set<String>,
        apkOnDisk: Set<String>,
        directoryReadable: Boolean,
        packagePrefix: String,
    ): List<String> {
        if (!directoryReadable) return emptyList()
        return rowIds
            .asSequence()
            .filter { it.startsWith(packagePrefix) }
            .filter { it !in stillPresent && it !in apkOnDisk }
            .distinct()
            .sorted()
            .toList()
    }
}
