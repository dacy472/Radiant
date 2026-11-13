package com.radiant.webtoon.core.domain

import com.radiant.webtoon.core.database.Chapter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Matches chapters between two webtoons by parsed floating-point chapter number.
 * Pure logic — no Android or Room dependencies at runtime.
 */
object ChapterMigrationMatcher {

    /** Two chapter numbers closer than this are considered equal. */
    const val EPSILON = 0.0001f

    fun parseChapterNumber(chapter: Chapter): Float? =
        ChapterNumberParser.parse(chapter.chapter_number, chapter.name)

    fun parseChapterNumberFromName(name: String): Float? = ChapterNumberParser.parseName(name)

    /**
     * Pairs each old chapter with at most one new chapter that has the same number (± [EPSILON]);
     * each new chapter is used at most once. When several new chapters share a number the one with
     * the same scanlator, then the same normalised title, then the first in list order is chosen.
     * Old chapters with no counterpart (or an unknown number) are simply absent from the result.
     *
     * The result is ordered like [oldChapters].
     */
    fun matchChapters(
        oldChapters: List<Chapter>,
        newChapters: List<Chapter>,
    ): List<Pair<Chapter, Chapter>> {
        if (oldChapters.isEmpty() || newChapters.isEmpty()) return emptyList()

        // Bucket new chapters by number quantised to EPSILON so lookups stay O(1) per old chapter.
        val buckets = HashMap<Long, MutableList<Candidate>>()
        newChapters.forEachIndexed { index, chapter ->
            val number = parseChapterNumber(chapter) ?: return@forEachIndexed
            buckets.getOrPut(bucketKey(number)) { mutableListOf() }
                .add(Candidate(index, chapter, number))
        }

        val used = HashSet<Int>()
        val matches = ArrayList<Pair<Chapter, Chapter>>()
        for (old in oldChapters) {
            val number = parseChapterNumber(old) ?: continue
            val key = bucketKey(number)
            val candidates = (key - 1..key + 1)
                .flatMap { buckets[it].orEmpty() }
                .filter { it.index !in used && abs(it.number - number) < EPSILON }
                .sortedBy { it.index }
            val best = pickBest(old, candidates) ?: continue
            used += best.index
            matches += old to best.chapter
        }
        return matches
    }

    private fun pickBest(old: Chapter, candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val oldScanlator = old.scanlator?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (oldScanlator != null) {
            candidates.firstOrNull { it.chapter.scanlator?.trim()?.lowercase() == oldScanlator }
                ?.let { return it }
        }
        val oldTitle = normalizeTitle(old.name)
        if (oldTitle.isNotEmpty()) {
            candidates.firstOrNull { normalizeTitle(it.chapter.name) == oldTitle }
                ?.let { return it }
        }
        return candidates[0]
    }

    private fun normalizeTitle(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    private fun bucketKey(number: Float): Long = (number / EPSILON).roundToLong()

    private class Candidate(val index: Int, val chapter: Chapter, val number: Float)
}
