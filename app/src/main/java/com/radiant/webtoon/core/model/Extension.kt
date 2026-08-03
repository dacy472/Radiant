package com.radiant.webtoon.core.model

import com.radiant.webtoon.core.source.Source

/**
 * Sealed class representing an extension.
 * Can either be Installed (on the device) or Available (on a remote repository).
 */
sealed class Extension {
    abstract val id: String
    abstract val name: String
    abstract val icon: String?
    abstract val version: String?
    abstract val nsfw: Boolean
    abstract val isTrusted: Boolean
    abstract val isInstalled: Boolean

    /**
     * An extension that is physically installed on the device (usually an APK).
     */
    data class Installed(
        override val id: String,
        override val name: String,
        override val icon: String? = null,
        override val version: String? = null,
        override val nsfw: Boolean = false,
        override val isTrusted: Boolean = true,
        val pkgName: String,
        val versionName: String? = null,
        val versionCode: Int = 0,
        val sources: List<Source> = emptyList(),
        val hasUpdate: Boolean = false,
        val downloadUrl: String? = null
    ) : Extension() {
        override val isInstalled: Boolean = true
    }

    /**
     * An extension that is available on a remote repository but not yet installed.
     */
    data class Available(
        override val id: String,
        override val name: String,
        override val icon: String? = null,
        override val version: String? = null,
        override val nsfw: Boolean = false,
        override val isTrusted: Boolean = false,
        val lang: String? = "en",
        val description: String? = null,
        val baseUrl: String? = "",
        val downloadUrl: String? = null,
        override val isInstalled: Boolean = false,
        /** False for non-English catalog entries — shown but not installable. */
        val isLanguageEnabled: Boolean = true
    ) : Extension()
}
