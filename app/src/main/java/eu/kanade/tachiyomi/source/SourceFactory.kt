package eu.kanade.tachiyomi.source

/**
 * A factory for creating sources. Some extensions use this to provide multiple sources
 * from a single APK (e.g., different languages or domains).
 */
interface SourceFactory {
    /**
     * Create a list of sources.
     */
    fun createSources(): List<Source>
}
