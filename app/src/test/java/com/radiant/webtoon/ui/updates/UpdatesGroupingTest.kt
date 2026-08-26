package com.radiant.webtoon.ui.updates

import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.UpdateItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class UpdatesGroupingTest {

    @Test
    fun groupsByCalendarDayWithTodayYesterdayAndExactDate() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val now = calendar(2026, Calendar.AUGUST, 23, 13, 20).timeInMillis
        val today = calendar(2026, Calendar.AUGUST, 23, 9, 0).timeInMillis
        val yesterday = calendar(2026, Calendar.AUGUST, 22, 18, 0).timeInMillis
        val lastWeekSameBucket = calendar(2026, Calendar.AUGUST, 18, 12, 0).timeInMillis
        val olderSameDay = calendar(2026, Calendar.AUGUST, 18, 8, 0).timeInMillis
        val olderDay = calendar(2026, Calendar.AUGUST, 10, 11, 0).timeInMillis

        val groups = groupUpdatesByDate(
            updates = listOf(
                item("Today Ch", today),
                item("Yesterday Ch", yesterday),
                item("Aug 18 evening", lastWeekSameBucket),
                item("Aug 18 morning", olderSameDay),
                item("Aug 10", olderDay),
            ),
            nowMillis = now,
        )

        assertEquals(listOf("Today", "Yesterday", "August 18, 2026", "August 10, 2026"), groups.map { it.label })
        assertEquals(1, groups[0].items.size)
        assertEquals(1, groups[1].items.size)
        assertEquals(2, groups[2].items.size)
        assertEquals("Aug 18 evening", groups[2].items[0].chapter.name)
        assertEquals("Aug 18 morning", groups[2].items[1].chapter.name)
        assertEquals(1, groups[3].items.size)
    }

    private fun item(name: String, fetchedAt: Long): UpdateItem {
        return UpdateItem(
            chapter = Chapter(
                url = name,
                manga_id = "manga",
                name = name,
                date_fetch = fetchedAt,
                chapter_number = 1f,
                source = "src",
            ),
            webtoonTitle = "Swordmaster",
            webtoonThumbnail = null,
            sourceId = "src",
        )
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
