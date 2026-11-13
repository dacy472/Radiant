package com.radiant.webtoon.core.source

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Decodes a snapshot of the live Keiyoushi `index.pb` (src/test/resources/repo) the same way
 * [ExtensionLoader.fetchRepoIndex] does, and checks every curated source is installable.
 * Refresh the snapshot with:
 * `curl -L -o app/src/test/resources/repo/keiyoushi-index.pb https://github.com/keiyoushi/extensions/raw/repo/index.pb`
 */
@OptIn(ExperimentalSerializationApi::class)
class KeiyoushiCatalogFixtureTest {

    private val repoUrl = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"

    private fun loadEntries(): List<RepoExtensionEntry> {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("repo/keiyoushi-index.pb")) {
            "missing fixture repo/keiyoushi-index.pb"
        }.use { it.readBytes() }
        val bytes = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }
        val store = ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
        return store.toRepoEntries(repoUrl).filter { ExtensionAllowlist.isAllowedRepoEntry(it) }
    }

    @Test
    fun everyDefaultSlugResolvesToADownloadableApk() {
        val bySlug = ExtensionAllowlist.dedupeBySlugPreferEn(
            loadEntries(),
            packageSelector = { it.id },
        ).associateBy { ExtensionAllowlist.slugOf(it.id) }

        val missing = ExtensionAllowlist.DEFAULT_INSTALL_SLUGS.filter { it !in bySlug }
        assertTrue("default extensions missing from repo: $missing", missing.isEmpty())

        for (slug in ExtensionAllowlist.DEFAULT_INSTALL_SLUGS) {
            val entry = bySlug.getValue(slug)
            val apk = entry.apkUrl.orEmpty()
            assertTrue("$slug has no https apk url: '$apk'", apk.startsWith("https://") && apk.endsWith(".apk"))
            assertTrue("$slug versionCode must be positive", entry.versionCode > 0)
            assertEquals(repoUrl, entry.repoUrl)
        }
    }

    @Test
    fun curatedExtensionsTargetASupportedExtensionLib() {
        val tooNew = loadEntries().filter {
            ExtensionLibCompat.check(it.extensionLibVersion) == ExtensionLibCompatibility.TooNew
        }
        assertTrue(
            "extensions need a newer extension-lib than host ${ExtensionLibCompat.SUPPORTED_VERSION}: " +
                tooNew.map { "${it.id}=${it.extensionLibVersion}" },
            tooNew.isEmpty(),
        )
    }
}
