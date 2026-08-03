package com.radiant.webtoon.core.source

/**
 * Curated English Keiyoushi extensions Radiant supports.
 * Matched by package slug (last segment of pkg id), verified against the live index.
 */
object ExtensionAllowlist {

    /**
     * Package suffixes known to exist on Keiyoushi (2026-07):
     * - MangaK → pkg slug mangabuddy (display name "MangaK")
     * - mangakakalot kept as related coverage
     * - MaangaReader → mangareadercc + mangareadersite
     */
    val SLUGS: Set<String> = setOf(
        "asurascans",
        "mangabat",
        "mangabuddy",
        "mangakakalot",
        "mangadex",
        "manga18fx",
        "flamecomics",
        "mangafire",
        "mangareadercc",
        "mangareadersite",
        "manhwa18cc",
        "templescan",
        "toonily",
    )

    /** Preferred first-launch installs (subset of [SLUGS], in priority order). */
    val DEFAULT_INSTALL_SLUGS: List<String> = listOf(
        "mangadex",
        "asurascans",
        "mangafire",
        "mangabat",
        "mangabuddy",
        "flamecomics",
        "templescan",
    )

    fun slugOf(pkgOrId: String): String =
        pkgOrId.substringAfterLast('.').lowercase().trim()

    fun isAllowed(pkgOrId: String): Boolean {
        val normalized = pkgOrId.lowercase().trim()
        if (normalized.isEmpty()) return false
        val slug = slugOf(normalized)
        if (slug !in SLUGS) return false
        // Only English / multi-lang packs: …extension.en.{slug} or …extension.all.{slug}
        return normalized.endsWith(".en.$slug") ||
            normalized.endsWith(".all.$slug") ||
            normalized == slug
    }
}
