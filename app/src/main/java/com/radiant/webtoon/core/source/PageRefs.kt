package com.radiant.webtoon.core.source

/**
 * String references for chapter pages.
 *
 * Most pages are plain image URLs. A page whose extension only exposes a page URL (the image URL
 * comes from [eu.kanade.tachiyomi.source.online.HttpSource.getImageUrl]) is kept as a *deferred*
 * ref so the slot is never dropped; the reader and downloader resolve it on demand, as Mihon does.
 */
object PageRefs {
    private const val DEFERRED_PREFIX = "radiant-page:"

    data class Deferred(val index: Int, val pageUrl: String)

    fun deferred(index: Int, pageUrl: String): String = "$DEFERRED_PREFIX$index:$pageUrl"

    fun isDeferred(ref: String): Boolean = ref.startsWith(DEFERRED_PREFIX)

    /** `true` for refs that need the network (http(s) image URLs and deferred page URLs). */
    fun isRemote(ref: String): Boolean =
        isDeferred(ref) || ref.startsWith("http://", ignoreCase = true) ||
            ref.startsWith("https://", ignoreCase = true)

    fun parseDeferred(ref: String): Deferred? {
        if (!isDeferred(ref)) return null
        val body = ref.removePrefix(DEFERRED_PREFIX)
        val separator = body.indexOf(':')
        if (separator <= 0) return null
        val index = body.substring(0, separator).toIntOrNull() ?: return null
        val pageUrl = body.substring(separator + 1)
        if (pageUrl.isEmpty()) return null
        return Deferred(index, pageUrl)
    }
}
