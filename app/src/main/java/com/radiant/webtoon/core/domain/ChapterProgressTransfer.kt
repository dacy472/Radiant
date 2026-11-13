package com.radiant.webtoon.core.domain

import com.radiant.webtoon.core.database.Chapter

/** Pure mapping of reading state from a matched old chapter onto the new source's chapter row. */
object ChapterProgressTransfer {

    /**
     * Copies `read`, `bookmark`, `last_page_read`, `last_page_offset` and `date_fetch` from [old];
     * every identity field of [new] (url, manga_id, source, name, scanlator, chapter_number,
     * date_upload) is kept so the Room row is updated in place.
     */
    fun apply(old: Chapter, new: Chapter): Chapter = new.copy(
        read = old.read,
        bookmark = old.bookmark,
        last_page_read = old.last_page_read,
        last_page_offset = old.last_page_offset,
        date_fetch = old.date_fetch,
    )
}
