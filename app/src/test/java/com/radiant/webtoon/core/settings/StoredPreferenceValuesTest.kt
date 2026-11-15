package com.radiant.webtoon.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredPreferenceValuesTest {
    @Test
    fun stringStoredAsNumberOrBooleanIsCoerced() {
        assertEquals("1", StoredPreferenceValues.asString(1))
        assertEquals("true", StoredPreferenceValues.asString(true))
        assertEquals("12", StoredPreferenceValues.asString(12L))
        assertNull(StoredPreferenceValues.asString(null))
    }

    @Test
    fun booleanStoredAsStringOrNumberIsCoerced() {
        assertEquals(true, StoredPreferenceValues.asBoolean("true"))
        assertEquals(false, StoredPreferenceValues.asBoolean("0"))
        assertEquals(true, StoredPreferenceValues.asBoolean(1))
        assertNull(StoredPreferenceValues.asBoolean("sometimes"))
    }

    @Test
    fun stringSetAcceptsASingleLegacyString() {
        assertEquals(setOf("en"), StoredPreferenceValues.asStringSet("en"))
        assertEquals(setOf("en", "all"), StoredPreferenceValues.asStringSet(setOf("en", "all")))
    }

    @Test
    fun rewritesOnlyMismatchedTypes() {
        val stored = mapOf(
            "theme_mode" to "dark",
            "nsfw_filter" to "false",
            "pinned_sources" to "6247824327199706550",
            "missing" to 1,
        )
        val kinds = mapOf(
            "theme_mode" to StoredPreferenceValues.Kind.STRING,
            "nsfw_filter" to StoredPreferenceValues.Kind.BOOLEAN,
            "pinned_sources" to StoredPreferenceValues.Kind.STRING_SET,
            "absent" to StoredPreferenceValues.Kind.STRING,
        )
        val rewrites = StoredPreferenceValues.rewrites(stored, kinds)
        assertEquals(false, rewrites["nsfw_filter"])
        assertEquals(setOf("6247824327199706550"), rewrites["pinned_sources"])
        assertTrue("theme_mode" !in rewrites)
        assertTrue("absent" !in rewrites)
    }

    @Test
    fun legacyKeyIsCopiedWhenTheNewKeyIsMissing() {
        val stored = mapOf("readingDirection" to "ltr")
        val copies = StoredPreferenceValues.legacyCopies(
            stored = stored,
            aliases = mapOf("reading_direction" to listOf("readingDirection")),
            kinds = mapOf("reading_direction" to StoredPreferenceValues.Kind.STRING),
        )
        assertEquals("ltr", copies["reading_direction"])
    }

    @Test
    fun legacyKeyIsNotCopiedOverAnExistingValue() {
        val stored = mapOf("reading_direction" to "vertical", "readingDirection" to "ltr")
        val copies = StoredPreferenceValues.legacyCopies(
            stored = stored,
            aliases = mapOf("reading_direction" to listOf("readingDirection")),
            kinds = mapOf("reading_direction" to StoredPreferenceValues.Kind.STRING),
        )
        assertTrue(copies.isEmpty())
    }

    @Test
    fun prefixedOverrideStoredAsBooleanBecomesAString() {
        val rewrites = StoredPreferenceValues.prefixedStringRewrites(
            mapOf("reading_mode_override:series" to true, "reading_mode_override:ok" to "webtoon"),
            "reading_mode_override:",
        )
        assertEquals(mapOf("reading_mode_override:series" to "true"), rewrites)
    }

    @Test
    fun unparsableIntFallsBackWithoutThrowing() {
        assertEquals(2, StoredPreferenceValues.asInt("nope", 2))
        assertEquals(4, StoredPreferenceValues.asInt("4", 2))
        assertEquals(1, StoredPreferenceValues.asInt(null, 1))
    }
}
