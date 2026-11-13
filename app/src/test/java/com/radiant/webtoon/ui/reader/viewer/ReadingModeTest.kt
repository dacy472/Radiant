package com.radiant.webtoon.ui.reader.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingModeTest {

    @Test
    fun mapsStoredPreferenceIdsOntoViewerFamilies() {
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromPrefId("webtoon"))
        assertEquals(ReadingMode.CONTINUOUS_VERTICAL, ReadingMode.fromPrefId("continuous_vertical"))
        assertEquals(ReadingMode.VERTICAL, ReadingMode.fromPrefId("vertical_pager"))
        assertEquals(ReadingMode.LEFT_TO_RIGHT, ReadingMode.fromPrefId("ltr"))
        assertEquals(ReadingMode.RIGHT_TO_LEFT, ReadingMode.fromPrefId("rtl"))

        assertEquals(ViewerFamily.RECYCLER_WEBTOON, ReadingMode.WEBTOON.family)
        assertEquals(ViewerFamily.RECYCLER_CONTINUOUS, ReadingMode.CONTINUOUS_VERTICAL.family)
        assertEquals(ViewerFamily.PAGER_VERTICAL, ReadingMode.VERTICAL.family)
        assertEquals(ViewerFamily.PAGER_L2R, ReadingMode.LEFT_TO_RIGHT.family)
        assertEquals(ViewerFamily.PAGER_R2L, ReadingMode.RIGHT_TO_LEFT.family)
    }

    @Test
    fun legacyVerticalPreferenceIsTheGaplessWebtoonStrip() {
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromPrefId("vertical"))
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromPrefId(null))
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromPrefId(""))
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromPrefId("nope"))
    }

    @Test
    fun webtoonModesUseTheWebtoonCropSetting() {
        assertTrue(ReadingMode.WEBTOON.usesWebtoonCrop)
        assertTrue(ReadingMode.CONTINUOUS_VERTICAL.usesWebtoonCrop)
        assertFalse(ReadingMode.LEFT_TO_RIGHT.usesWebtoonCrop)
        assertFalse(ReadingMode.RIGHT_TO_LEFT.usesWebtoonCrop)
        assertFalse(ReadingMode.VERTICAL.usesWebtoonCrop)
    }

    @Test
    fun seriesKeyAndCycleCoverEveryMode() {
        assertEquals("src|series", ReadingMode.seriesKey("src", "series"))
        val seen = mutableSetOf<ReadingMode>()
        var mode = ReadingMode.DEFAULT
        repeat(ReadingMode.entries.size) {
            seen += mode
            mode = mode.next()
        }
        assertEquals(ReadingMode.entries.toSet(), seen)
        assertEquals(ReadingMode.DEFAULT, mode)
    }
}
