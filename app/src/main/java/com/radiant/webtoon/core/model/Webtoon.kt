package com.radiant.webtoon.core.model

import com.google.gson.annotations.SerializedName

data class Webtoon(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("thumbnail") val thumbnail: String,
    @SerializedName("url") val url: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("author") val author: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("genres") val genres: List<String> = emptyList(),
    @SerializedName("chapters") val chapters: List<Chapter> = emptyList()
)

data class Chapter(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("url") val url: String? = null,
    @SerializedName("date") val date: String? = null,
    /** Epoch millis of the source upload date; 0 if the site did not provide one. */
    @SerializedName("dateUploadMillis") val dateUploadMillis: Long = 0L,
    @SerializedName("number") val number: Double? = null,
    @SerializedName("read") val read: Boolean = false,
    /** JSON object string of Tachiyomi SChapter.memo (e.g. Asura mangaSlug). */
    @SerializedName("memo") val memoJson: String? = null,
)
