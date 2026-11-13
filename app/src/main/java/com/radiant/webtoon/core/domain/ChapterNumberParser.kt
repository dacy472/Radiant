package com.radiant.webtoon.core.domain

/**
 * Pure (JVM-testable) chapter-number parsing used when matching chapters across sources.
 *
 * A stored `chapter_number` of `-1` (or any negative / NaN / infinite value) means "unknown",
 * the same convention Tachiyomi/Mihon extensions use for `SChapter.chapter_number`.
 */
object ChapterNumberParser {

    private const val NUMBER = """(\d+(?:\.\d+)?)"""

    /** "Chapter 12.5", "Ch. 3", "Ch3", "Episode 7", "Ep.7", "#12". */
    private val KEYWORD_REGEX = Regex(
        """(?:\b(?:chapter|chap|ch|episode|ep)(?![a-z])\.?\s*#?\s*|#\s*)$NUMBER""",
        RegexOption.IGNORE_CASE,
    )

    /** "45 - Title", "45. Title", "12.5". */
    private val LEADING_REGEX = Regex("""^\s*$NUMBER(?!\d)""")

    private val ANY_NUMBER_REGEX = Regex(NUMBER)

    fun isKnown(number: Float): Boolean = !number.isNaN() && !number.isInfinite() && number >= 0f

    /** Stored number when known, otherwise whatever [parseName] can extract from [name]. */
    fun parse(storedNumber: Float, name: String): Float? =
        if (isKnown(storedNumber)) storedNumber else parseName(name)

    /**
     * Extracts a chapter number from a display name. Priority: explicit keyword
     * ("Chapter", "Ch.", "Episode", "Ep", "#"), then a leading number, then the first number.
     * Returns null when the name contains no number at all.
     */
    fun parseName(name: String): Float? {
        if (name.isBlank()) return null
        val raw = KEYWORD_REGEX.find(name)?.groupValues?.get(1)
            ?: LEADING_REGEX.find(name)?.groupValues?.get(1)
            ?: ANY_NUMBER_REGEX.find(name)?.groupValues?.get(1)
            ?: return null
        return raw.toFloatOrNull()?.takeIf { isKnown(it) }
    }
}
