package com.radiant.webtoon.core.backup

import com.google.gson.annotations.SerializedName

data class InstalledExtensionBackup(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("isTrusted") val isTrusted: Boolean,
)

data class SourcePreferencesBackup(
    @SerializedName("sourceId") val sourceId: String,
    @SerializedName("prefs") val prefs: Map<String, String?>,
)
