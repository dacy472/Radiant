package com.radiant.webtoon.core.model

import com.google.gson.annotations.SerializedName

data class GlobalSearchResponse(
    @SerializedName("results") val results: List<SourceSearchResult>
)

enum class SourceSearchStatus {
    Idle,
    Loading,
    Success,
    Error,
    /** Cloudflare challenge — show inline "Solve in WebView", no app-wide modal. */
    VerificationNeeded,
}

data class SourceSearchResult(
    @SerializedName("sourceId") val sourceId: String,
    @SerializedName("sourceName") val sourceName: String,
    @SerializedName("sourceIcon") val sourceIcon: String,
    @SerializedName("sourceLang") val sourceLang: String,
    @SerializedName("webtoons") val webtoons: List<Webtoon>,
    @SerializedName("error") val error: String? = null,
    @SerializedName("hasNextPage") val hasNextPage: Boolean = false,
    @SerializedName("currentPage") val currentPage: Int = 1,
    @SerializedName("status") val status: SourceSearchStatus = SourceSearchStatus.Idle,
    @SerializedName("challengeUrl") val challengeUrl: String? = null,
)

data class PagedWebtoonResponse(
    @SerializedName("webtoons") val webtoons: List<Webtoon>,
    @SerializedName("hasNextPage") val hasNextPage: Boolean = false
)
