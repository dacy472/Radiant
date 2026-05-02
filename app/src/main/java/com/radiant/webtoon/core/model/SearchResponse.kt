package com.radiant.webtoon.core.model

import com.google.gson.annotations.SerializedName

data class GlobalSearchResponse(
    @SerializedName("results") val results: List<SourceSearchResult>
)

data class SourceSearchResult(
    @SerializedName("sourceId") val sourceId: String,
    @SerializedName("sourceName") val sourceName: String,
    @SerializedName("sourceIcon") val sourceIcon: String,
    @SerializedName("sourceLang") val sourceLang: String,
    @SerializedName("webtoons") val webtoons: List<Webtoon>,
    @SerializedName("error") val error: String? = null
)

data class PagedWebtoonResponse(
    @SerializedName("webtoons") val webtoons: List<Webtoon>,
    @SerializedName("hasNextPage") val hasNextPage: Boolean = false
)
