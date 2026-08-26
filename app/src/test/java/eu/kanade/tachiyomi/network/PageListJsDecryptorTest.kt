package eu.kanade.tachiyomi.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageListJsDecryptorTest {

    @Test
    fun parsesJsonArrayLiteral() {
        val urls = PageListJsDecryptor.parseUrlListFromJson(
            """["https://a.com/1.webp","https://a.com/2.webp"]""",
        )
        assertEquals(2, urls.size)
    }

    @Test
    fun detectsEncryptedImagesField() {
        val encrypted = PageListJsDecryptor.extractEncryptedImagesField(
            """{"pageProps":{"chapter":{"images":"YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZGVm"}}}""",
        )
        assertEquals("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZGVm", encrypted)
    }

    @Test
    fun ignoresShortImagesString() {
        val encrypted = PageListJsDecryptor.extractEncryptedImagesField(
            """{"pageProps":{"chapter":{"images":"short"}}}""",
        )
        assertNull(encrypted)
    }
}
