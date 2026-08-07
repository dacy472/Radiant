package com.radiant.webtoon.core.source

/**
 * Strict curated English Keiyoushi extensions Radiant supports.
 * Only sources in [ALLOWED_SOURCE_NAMES] / [SLUGS] may be fetched, installed, or shown.
 */
object ExtensionAllowlist {

    /** Exact display names from the Keiyoushi repo. */
    val ALLOWED_SOURCE_NAMES: Set<String> = setOf(
        "Asura Scans",
        "MangaDex",
        "MangaFire",
        "Flame Comics",
        "Mangabat",
        "MangaK",
        "Temple Scan",
        "Madara Scans",
        "MadaraDex",
        "Manhwa18.cc",
        "Manga18fx",
    )

    /** Display name -> Keiyoushi package slug. */
    val SOURCE_NAME_TO_SLUG: Map<String, String> = mapOf(
        "Asura Scans" to "asurascans",
        "MangaDex" to "mangadex",
        "MangaFire" to "mangafire",
        "Flame Comics" to "flamecomics",
        "Mangabat" to "mangabat",
        "MangaK" to "mangabuddy",
        "Temple Scan" to "templescan",
        "Madara Scans" to "madarascans",
        "MadaraDex" to "madaradex",
        "Manhwa18.cc" to "manhwa18cc",
        "Manga18fx" to "manga18fx",
    )

    val SLUGS: Set<String> = SOURCE_NAME_TO_SLUG.values.toSet()

    val DEFAULT_INSTALL_SLUGS: List<String> = listOf(
        "mangadex",
        "asurascans",
        "mangafire",
        "mangabat",
        "flamecomics",
        "templescan",
    )

    /** Keiyoushi repo stubs shown when the host app is unsupported — not real sources. */
    val REPO_STUB_SLUGS: Set<String> = setOf("keiyoushi", "mihon")

    fun isRepoStub(pkgOrId: String): Boolean = slugOf(pkgOrId) in REPO_STUB_SLUGS

    /** Extension package langs allowed in advanced mode. */
    val ADVANCED_EXTENSION_LANGS: Set<String> = setOf("en", "all")

    fun slugOf(pkgOrId: String): String =
        pkgOrId.substringAfterLast('.').lowercase().trim()

    fun extensionLangOf(pkgOrId: String): String? {
        val normalized = pkgOrId.lowercase().trim()
        val marker = ".extension."
        val idx = normalized.indexOf(marker)
        if (idx < 0) return null
        val after = normalized.substring(idx + marker.length)
        return after.substringBefore('.').takeIf { it.isNotEmpty() }
    }

    /** Lower value = higher priority when picking between en/all variants. */
    fun extensionLangPriority(lang: String?): Int = when (lang?.lowercase()?.trim()) {
        "en" -> 0
        "all" -> 1
        null, "" -> 2
        else -> 3
    }

    private fun packageFromIcon(icon: String): String? = when {
        icon.startsWith("pkg:") -> icon.removePrefix("pkg:")
        icon.startsWith("apk:") -> java.io.File(icon.removePrefix("apk:")).nameWithoutExtension
        else -> null
    }

    fun sourceSlug(source: Source): String {
        SOURCE_NAME_TO_SLUG[source.name]?.let { return it }
        if (source is TachiyomiSourceAdapter) {
            packageFromIcon(source.icon)?.let { return slugOf(it) }
        }
        return source.name.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    fun isAllowedByName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        return trimmed.isNotEmpty() && trimmed in ALLOWED_SOURCE_NAMES
    }

    private fun matchesPackagePattern(normalized: String): Boolean {
        val slug = slugOf(normalized)
        return normalized.endsWith(".en.$slug") ||
            normalized.endsWith(".all.$slug") ||
            normalized == slug
    }

    fun isCuratedSlug(pkgOrId: String): Boolean {
        val normalized = pkgOrId.lowercase().trim()
        if (normalized.isEmpty()) return false
        val slug = slugOf(normalized)
        if (slug !in SLUGS) return false
        return matchesPackagePattern(normalized)
    }

    /** Repo catalog entry — match by display [RepoExtensionEntry.name] or package slug. */
    fun isAllowedRepoEntry(entry: RepoExtensionEntry): Boolean =
        isAllowedRepoEntry(entry.name, entry.id)

    fun isAllowedRepoEntry(name: String?, pkgOrId: String): Boolean {
        if (isRepoStub(pkgOrId)) return false
        if (isCuratedSlug(pkgOrId)) return true
        return isAllowedByName(name)
    }

    /** Curated allowlist extensions plus [VerifiedExtensions] test packs — eligible for auto-update. */
    fun isAutoUpdatable(pkgOrId: String): Boolean =
        isCuratedSlug(pkgOrId) || VerifiedExtensions.isVerified(pkgOrId)

    fun isLanguageAllowed(pkgOrId: String, advancedMode: Boolean): Boolean {
        if (!advancedMode) return true // curated slugs are already en/all
        val lang = extensionLangOf(pkgOrId) ?: return true
        return lang in ADVANCED_EXTENSION_LANGS
    }

    /**
     * Strict allowlist: package must match a curated slug.
     * Advanced mode only relaxes language policy — it does not bypass the source list.
     */
    fun isAllowed(pkgOrId: String, advancedMode: Boolean = false): Boolean {
        val normalized = pkgOrId.lowercase().trim()
        if (normalized.isEmpty()) return false
        if (!isCuratedSlug(normalized)) return false
        return if (advancedMode) {
            isLanguageAllowed(normalized, advancedMode = true)
        } else {
            true
        }
    }

    /** Startup purge — strict display name + package slug matching. */
    fun isStrictlyAllowed(pkgOrId: String, displayName: String? = null): Boolean =
        isAllowedRepoEntry(displayName, pkgOrId)

    fun packagesToPurge(
        packageIds: Collection<String>,
        displayNameOf: (String) -> String? = { null },
    ): List<String> = packageIds
        .distinct()
        .filter { id -> !isStrictlyAllowed(id, displayNameOf(id)) }
        .sorted()

    /** True when [candidate] should replace [incumbent] for the same slug. */
    fun isPreferredExtensionPackage(candidate: String, incumbent: String): Boolean {
        val candidatePriority = extensionLangPriority(extensionLangOf(candidate))
        val incumbentPriority = extensionLangPriority(extensionLangOf(incumbent))
        if (candidatePriority != incumbentPriority) {
            return candidatePriority < incumbentPriority
        }
        return candidate < incumbent
    }

    /**
     * When the same slug appears as both `.en.` and `.all.` packages, keep the English build.
     * Skipped in advanced mode so both variants remain available when explicitly installed.
     */
    fun <T> dedupeBySlugPreferEn(
        items: List<T>,
        packageSelector: (T) -> String,
        advancedMode: Boolean = false,
    ): List<T> {
        if (advancedMode || items.isEmpty()) return items
        val winners = linkedMapOf<String, T>()
        val order = mutableListOf<String>()
        for (item in items) {
            val pkg = packageSelector(item)
            val slug = slugOf(pkg)
            val existing = winners[slug]
            if (existing == null) {
                winners[slug] = item
                order.add(slug)
            } else if (isPreferredExtensionPackage(pkg, packageSelector(existing))) {
                winners[slug] = item
            }
        }
        return order.mapNotNull { winners[it] }
    }

    fun dedupePackageIdsPreferEn(
        packageIds: List<String>,
        advancedMode: Boolean = false,
    ): List<String> = dedupeBySlugPreferEn(packageIds, { it }, advancedMode)

    fun sourcePackageName(source: Source): String? {
        val icon = source.icon
        return when {
            icon.startsWith("pkg:") -> icon.removePrefix("pkg:")
            icon.startsWith("apk:") -> java.io.File(icon.removePrefix("apk:")).nameWithoutExtension
            else -> null
        }
    }

    fun dedupeSourcesBySlugPreferEn(
        sources: List<Source>,
        advancedMode: Boolean = false,
    ): List<Source> = dedupeBySlugPreferEn(
        items = sources,
        packageSelector = { source -> sourcePackageName(source) ?: source.id },
        advancedMode = advancedMode,
    )
}
