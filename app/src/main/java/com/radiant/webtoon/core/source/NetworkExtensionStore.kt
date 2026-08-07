package com.radiant.webtoon.core.source

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Keiyoushi / Mihon extension repository protobuf schema (index.pb).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact? = null,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    )

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources? = null,
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long = 0,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }
}

fun NetworkExtensionStore.toRepoEntries(repoUrl: String): List<RepoExtensionEntry> {
    val list = extensionList?.extensions ?: return emptyList()
    return list.map { ext ->
        val langs = ext.sources.map { it.language }.toSet()
        val lang = if (langs.size == 1) langs.first() else "all"
        val apkUrl = ext.resources?.apkUrl.orEmpty()
        val iconUrl = ext.resources?.iconUrl.orEmpty()
        RepoExtensionEntry(
            id = ext.packageName,
            name = ext.name,
            baseUrl = ext.sources.firstOrNull()?.homeUrl?.takeIf { it.isNotBlank() },
            lang = lang,
            icon = iconUrl.ifBlank { "🌐" },
            version = ext.versionName,
            versionCode = ext.versionCode,
            fileName = apkUrl.substringAfterLast('/').ifBlank { "${ext.packageName}.apk" },
            apkUrl = apkUrl.takeIf { it.isNotBlank() },
            nsfw = if (ext.contentWarning >= NetworkExtensionStore.ContentWarning.MIXED) 1 else 0,
            extensionLibVersion = ext.extensionLib.takeIf { it.isNotBlank() },
            sources = ext.sources.map { RepoExtensionSource(baseUrl = it.homeUrl) },
            repoUrl = repoUrl,
        )
    }
}
