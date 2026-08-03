package com.radiant.webtoon.core.backup

import com.radiant.webtoon.core.database.Category
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.database.Manga
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPayloadTest {
    @Test
    fun `backup payload stores expected counts`() {
        val payload = BackupPayload(
            version = 2,
            generatedAt = 1L,
            library = listOf(
                Manga(url = "id", source = "src", title = "Title", thumbnail_url = "thumb")
            ),
            downloads = listOf(
                DownloadTask(
                    chapterId = "ch",
                    sourceId = "src",
                    webtoonId = "id",
                    webtoonTitle = "Title",
                    chapterTitle = "Ch 1"
                )
            ),
            categories = listOf(Category(id = 1, name = "Default", order = 0)),
            categoryMappings = emptyList()
        )
        assertEquals(1, payload.library.size)
        assertEquals(1, payload.downloads.size)
        assertEquals(1, payload.categories.size)
        assertEquals(0, payload.history.size)
        assertEquals(0, payload.extensionRepos.size)
    }
}
