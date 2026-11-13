package com.radiant.webtoon.core.domain

import com.radiant.webtoon.core.database.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTargetTest {

    private fun ch(
        n: Float,
        read: Boolean = false,
        page: Int = 0,
        offset: Int = 0,
        url: String = "c$n",
    ) = Chapter(
        url = url,
        manga_id = "m",
        name = "Chapter $n",
        read = read,
        last_page_read = page,
        last_page_offset = offset,
        chapter_number = n,
        source = "s",
    )

    /** Room returns chapters newest first. */
    private fun newestFirst(vararg chapters: Chapter) = chapters.sortedByDescending { it.chapter_number }

    @Test
    fun emptyListHasNoTarget() {
        assertNull(ResumeTarget.resolve(emptyList(), null))
    }

    @Test
    fun freshTitleStartsAtFirstChapter() {
        val target = ResumeTarget.resolve(newestFirst(ch(1f), ch(2f), ch(3f)), null)!!
        assertEquals(ResumeTarget.Kind.START, target.kind)
        assertEquals("c1.0", target.chapter.url)
    }

    @Test
    fun unfinishedHistoryChapterResumesInPlace() {
        val chapters = newestFirst(ch(1f, read = true), ch(2f, page = 7), ch(3f))
        val target = ResumeTarget.resolve(chapters, lastOpenedChapterUrl = "c2.0")!!
        assertEquals(ResumeTarget.Kind.RESUME, target.kind)
        assertEquals("c2.0", target.chapter.url)
        assertEquals(7, target.chapter.last_page_read)
    }

    @Test
    fun finishedHistoryChapterMovesToNextUnread() {
        val chapters = newestFirst(ch(1f, read = true), ch(2f, read = true), ch(3f))
        val target = ResumeTarget.resolve(chapters, lastOpenedChapterUrl = "c2.0")!!
        assertEquals(ResumeTarget.Kind.RESUME, target.kind)
        assertEquals("c3.0", target.chapter.url)
    }

    @Test
    fun progressWithoutHistoryUsesNewestTouchedChapter() {
        val chapters = newestFirst(ch(1f, read = true), ch(2f, offset = 120), ch(3f))
        val target = ResumeTarget.resolve(chapters, lastOpenedChapterUrl = null)!!
        assertEquals("c2.0", target.chapter.url)
    }

    @Test
    fun skippedEarlierChapterIsPickedWhenNothingUnreadAfterAnchor() {
        val chapters = newestFirst(ch(1f), ch(2f, read = true), ch(3f, read = true))
        val target = ResumeTarget.resolve(chapters, lastOpenedChapterUrl = "c3.0")!!
        assertEquals(ResumeTarget.Kind.RESUME, target.kind)
        assertEquals("c1.0", target.chapter.url)
    }

    @Test
    fun allReadOffersReadAgainFromStart() {
        val chapters = newestFirst(ch(1f, read = true), ch(2f, read = true))
        val target = ResumeTarget.resolve(chapters, lastOpenedChapterUrl = "c2.0")!!
        assertEquals(ResumeTarget.Kind.READ_AGAIN, target.kind)
        assertEquals("c1.0", target.chapter.url)
    }

    @Test
    fun unnumberedChaptersFollowReversedSourceOrder() {
        val sourceOrder = listOf(ch(-1f, url = "newest"), ch(-1f, url = "middle"), ch(-1f, url = "oldest"))
        assertEquals(
            listOf("oldest", "middle", "newest"),
            ResumeTarget.readingOrder(sourceOrder).map { it.url },
        )
    }

    @Test
    fun staleHistoryUrlFallsBackToProgress() {
        val chapters = newestFirst(ch(1f, read = true), ch(2f))
        val target = ResumeTarget.resolve(chapters, lastOpenedChapterUrl = "removed")!!
        assertEquals("c2.0", target.chapter.url)
    }
}
