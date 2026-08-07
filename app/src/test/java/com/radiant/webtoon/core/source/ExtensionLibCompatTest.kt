package com.radiant.webtoon.core.source

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionLibCompatTest {

    @Test
    fun compatibleWhenNoVersionDeclared() {
        assertEquals(ExtensionLibCompatibility.Compatible, ExtensionLibCompat.check(null))
    }

    @Test
    fun compatibleWithSupportedVersion() {
        assertEquals(ExtensionLibCompatibility.Compatible, ExtensionLibCompat.check("1.6"))
        assertEquals(ExtensionLibCompatibility.Compatible, ExtensionLibCompat.check("1.5"))
    }

    @Test
    fun tooNewWhenMajorAhead() {
        assertEquals(ExtensionLibCompatibility.TooNew, ExtensionLibCompat.check("2.0"))
    }
}
