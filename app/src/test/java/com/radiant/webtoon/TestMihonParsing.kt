package com.radiant.webtoon

import com.google.gson.Gson
import com.radiant.webtoon.core.source.RepoExtensionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TestMihonParsing {
    @Test
    fun parsesRepoExtensionEntryFromJsonArray() {
        val json = """
            [
              {
                "name": "Manhwa18.cc",
                "pkg": "eu.kanade.tachiyomi.extension.all.manhwa18cc",
                "apk": "apk/eu.kanade.tachiyomi.extension.all.manhwa18cc-v1.4.1.apk",
                "lang": "all",
                "versionCode": 1,
                "version": "1.4.1"
              }
            ]
        """.trimIndent()

        val gson = Gson()
        val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
        val extensions: List<RepoExtensionEntry> = gson.fromJson(json, listType)
        val entry = extensions.find { it.id == "eu.kanade.tachiyomi.extension.all.manhwa18cc" }

        assertNotNull("Entry should not be null", entry)
        assertEquals("apk/eu.kanade.tachiyomi.extension.all.manhwa18cc-v1.4.1.apk", entry!!.fileName)
    }
}
