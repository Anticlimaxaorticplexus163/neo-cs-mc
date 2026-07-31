package gg.earu.chatsounds

import gg.earu.chatsounds.data.RepositoryCompiler
import gg.earu.chatsounds.data.SoundLookup
import gg.earu.chatsounds.parser.TriggerMatcher
import gg.earu.chatsounds.platform.Platform
import gg.earu.chatsounds.playback.SoundDownloader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hits the real CDN chain (PAC3-Server/chatsounds-valve-games portal folder, the smallest).
 * Opt-in: CHATSOUNDS_IT=1 ./gradlew :common:test --tests "*LiveDataIntegrationTest*"
 */
@EnabledIfEnvironmentVariable(named = "CHATSOUNDS_IT", matches = "1")
class LiveDataIntegrationTest {
    @Test
    fun `compile portal repo, match a trigger, download one ogg`() = runBlocking {
        val tmp = Files.createTempDirectory("chatsounds-it")
        Chatsounds.init(object : Platform {
            override val configDir: Path = tmp
            override val isClient = false
            override val modVersion = "it"
        })

        val compiler = RepositoryCompiler(tmp)
        val recompiled = compiler.buildFromMsgpack("PAC3-Server/chatsounds-valve-games", "master", "portal", false)
        assertTrue(recompiled, "first compile should not hit a cache")

        val lookup = SoundLookup.merge(compiler.repositories)
        assertTrue(lookup.list.size > 100, "portal should have >100 keys, got ${lookup.list.size}")
        assertTrue(lookup.maxKeyLength > 0)

        // Pick a real key and check the trigger matcher finds it embedded in a sentence.
        val key = lookup.list.keys.first { it != "sh" && !it.contains(';') && it.length in 4..20 }
        val triggers = TriggerMatcher.parseSoundTriggers("zzz qqq $key qqq", lookup)
        assertEquals(listOf(key), triggers.map { it.key }, "expected to match '$key'")

        // Second compile must be served from the disk cache.
        val compiler2 = RepositoryCompiler(tmp)
        assertEquals(false, compiler2.buildFromMsgpack("PAC3-Server/chatsounds-valve-games", "master", "portal", false))

        // Download one variant and sanity-check the ogg magic.
        val variant = lookup.list[key]!!.first()
        val file = SoundDownloader.ensure(variant)
        assertNotNull(file, "download failed for ${variant.url}")
        val magic = Files.newInputStream(file).use { it.readNBytes(4) }
        assertEquals("OggS", String(magic, Charsets.US_ASCII))

        println("IT OK: ${lookup.list.size} keys, matched '$key', downloaded ${variant.url}")
    }
}
