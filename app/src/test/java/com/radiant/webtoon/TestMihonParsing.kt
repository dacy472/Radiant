package com.radiant.webtoon

import com.google.gson.Gson
import com.radiant.webtoon.core.source.RepoExtensionEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.Assert.*

class TestMihonParsing {
    @Test
    fun testParse() {
        val url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        val trimmedBody = body.trimStart()
        
        val gson = Gson()
        val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
        val extensions: List<RepoExtensionEntry> = gson.fromJson(trimmedBody, listType)
        val entry = extensions.find { it.id == "eu.kanade.tachiyomi.extension.all.manhwa18cc" }
        
        println("Found entry: $entry")
        assertNotNull("Entry should not be null", entry)
        if (entry != null) {
            println("apk/fileName is: ${entry.fileName}")
            assertNotNull("fileName should not be null", entry.fileName)
            val baseRepoUrl = url.substringBeforeLast("/")
            val downloadUrl = "$baseRepoUrl/${entry.fileName}"
            println("Download URL: $downloadUrl")
        }
    }
}
