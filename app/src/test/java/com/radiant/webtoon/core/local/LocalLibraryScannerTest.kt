package com.radiant.webtoon.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalLibraryScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseChapterNumber_extractsDigits() {
        assertEquals(12.0, LocalLibraryScanner.parseChapterNumber("Chapter 12")!!, 0.0)
        assertEquals(3.5, LocalLibraryScanner.parseChapterNumber("ch_03.5")!!, 0.0)
        assertNull(LocalLibraryScanner.parseChapterNumber("Prologue"))
    }

    @Test
    fun scanFileRoot_detectsChapterSubdirectories() {
        val root = tempFolder.newFolder("Webtoons")
        val series = File(root, "Test Series").also { it.mkdirs() }
        val ch1 = File(series, "Chapter 01").also { it.mkdirs() }
        File(ch1, "001.jpg").writeBytes(byteArrayOf(1))
        File(ch1, "002.jpg").writeBytes(byteArrayOf(2))
        val ch2 = File(series, "Chapter 02").also { it.mkdirs() }
        File(ch2, "001.png").writeBytes(byteArrayOf(3))

        val result = LocalFileScanner.scanFileRoot(root)

        assertEquals(1, result.size)
        val localSeries = result.first()
        assertEquals("Test Series", localSeries.title)
        assertEquals(2, localSeries.chapters.size)
        assertEquals(1.0, localSeries.chapters.first().number)
        assertEquals(2.0, localSeries.chapters.last().number)
        assertTrue(localSeries.chapters.all { it.localPath.isNotBlank() })
    }

    @Test
    fun scanFileRoot_detectsSingleChapterSeriesWithImagesInRoot() {
        val root = tempFolder.newFolder("Webtoons")
        val series = File(root, "One Shot").also { it.mkdirs() }
        File(series, "page1.webp").writeBytes(byteArrayOf(1))
        File(series, "page2.webp").writeBytes(byteArrayOf(2))

        val result = LocalFileScanner.scanFileRoot(root)

        assertEquals(1, result.size)
        assertEquals(1, result.first().chapters.size)
        assertEquals(1.0, result.first().chapters.first().number)
        assertEquals(2, result.first().chapters.first().pageCount)
    }

    @Test
    fun scanFileRoot_detectsCbzAtSeriesRoot() {
        val root = tempFolder.newFolder("Webtoons")
        val series = File(root, "Archive Series").also { it.mkdirs() }
        val cbz = File(series, "Chapter 10.cbz")
        writeTestCbz(cbz, listOf("001.jpg", "002.jpg"))

        val result = LocalFileScanner.scanFileRoot(root)

        assertEquals(1, result.size)
        assertEquals(1, result.first().chapters.size)
        assertEquals(10.0, result.first().chapters.first().number)
        assertEquals(2, result.first().chapters.first().pageCount)
    }

    @Test
    fun scanFileRoot_detectsSingleChapterCbzAtLibraryRoot() {
        val root = tempFolder.newFolder("Webtoons")
        val cbz = File(root, "Standalone.cbz")
        writeTestCbz(cbz, listOf("001.jpg"))

        val result = LocalFileScanner.scanFileRoot(root)

        assertEquals(1, result.size)
        assertEquals("Standalone", result.first().title)
        assertEquals(1, result.first().chapters.size)
        assertNotNull(result.first().chapters.first().localPath)
    }

    @Test
    fun scanFileRoot_mixedStructure() {
        val root = tempFolder.newFolder("Webtoons")

        val folderSeries = File(root, "Folder Series").also { it.mkdirs() }
        val ch = File(folderSeries, "Chapter 1").also { it.mkdirs() }
        File(ch, "01.jpg").writeBytes(byteArrayOf(1))

        val cbzSeries = File(root, "CBZ Series").also { it.mkdirs() }
        writeTestCbz(File(cbzSeries, "Chapter 2.cbz"), listOf("01.jpg"))

        val result = LocalFileScanner.scanFileRoot(root)

        assertEquals(2, result.size)
        assertTrue(result.any { it.title == "Folder Series" && it.chapters.size == 1 })
        assertTrue(result.any { it.title == "CBZ Series" && it.chapters.size == 1 })
    }

    private fun writeTestCbz(file: File, entries: List<String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }
}
