package com.radiant.webtoon.ui.reader.viewer

/**
 * Reader modes. [prefId] is what is persisted in DataStore (default mode: `reading_direction`,
 * per-series override: `reading_mode_override:<sourceId>|<webtoonId>`).
 */
enum class ReadingMode(
    val prefId: String,
    val label: String,
    val family: ViewerFamily,
) {
    LEFT_TO_RIGHT("ltr", "Left to right", ViewerFamily.PAGER_L2R),
    RIGHT_TO_LEFT("rtl", "Right to left", ViewerFamily.PAGER_R2L),
    VERTICAL("vertical_pager", "Vertical", ViewerFamily.PAGER_VERTICAL),
    WEBTOON("webtoon", "Webtoon", ViewerFamily.RECYCLER_WEBTOON),
    CONTINUOUS_VERTICAL("continuous_vertical", "Continuous vertical", ViewerFamily.RECYCLER_CONTINUOUS);

    /** Webtoon and continuous vertical share the RecyclerView infrastructure. */
    val usesRecycler: Boolean get() = family == ViewerFamily.RECYCLER_WEBTOON || family == ViewerFamily.RECYCLER_CONTINUOUS

    /** Which of the two Crop Borders settings applies (webtoon-like vs. paged). */
    val usesWebtoonCrop: Boolean get() = usesRecycler

    fun next(): ReadingMode = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = WEBTOON

        /**
         * Parses a stored id. The legacy `vertical` value (pre-native-reader "Vertical" = continuous
         * long strip) maps to [WEBTOON]; unknown values fall back to [DEFAULT].
         */
        fun fromPrefId(id: String?): ReadingMode = when (id) {
            null, "" -> DEFAULT
            "ltr" -> LEFT_TO_RIGHT
            "rtl" -> RIGHT_TO_LEFT
            "vertical_pager" -> VERTICAL
            "vertical", "webtoon" -> WEBTOON
            "continuous_vertical" -> CONTINUOUS_VERTICAL
            else -> DEFAULT
        }

        fun seriesKey(sourceId: String, webtoonId: String): String = "$sourceId|$webtoonId"
    }
}

/** Which concrete Viewer class backs a [ReadingMode]. */
enum class ViewerFamily {
    PAGER_L2R,
    PAGER_R2L,
    PAGER_VERTICAL,
    RECYCLER_WEBTOON,
    RECYCLER_CONTINUOUS,
}
