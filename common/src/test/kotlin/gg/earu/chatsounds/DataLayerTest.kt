package gg.earu.chatsounds

import gg.earu.chatsounds.data.Msgpack
import gg.earu.chatsounds.data.RepositoryCompiler
import gg.earu.chatsounds.net.HttpQueue
import gg.earu.chatsounds.util.sha1Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class DataLayerTest {
    @Test
    fun `msgpack reads array16 of string triples`() {
        val out = java.io.ByteArrayOutputStream()
        fun str(s: String) {
            if (s.length <= 31) {
                out.write(0xa0 or s.length)
            } else {
                out.write(0xd9); out.write(s.length) // str8
            }
            out.write(s.toByteArray())
        }
        out.write(0xdc); out.write(0); out.write(2) // array16, 2 entries
        out.write(0x93); str("hl2"); str("my_sound.ogg"); str("sounds/chatsounds/hl2/my_sound.ogg")
        out.write(0x93); str("tf2"); str("Other-Sound.ogg"); str("sounds/chatsounds/tf2/Other-Sound.ogg")

        val entries = Msgpack.readSoundList(out.toByteArray())
        assertEquals(2, entries.size)
        assertEquals("hl2", entries[0].realm)
        assertEquals("my_sound.ogg", entries[0].name)
        assertEquals("sounds/chatsounds/tf2/Other-Sound.ogg", entries[1].path)
    }

    @Test
    fun `key normalization matches gmod`() {
        assertEquals("my sound", RepositoryCompiler.normalizeKey("My_Sound.ogg", trim = false))
        assertEquals("other sound", RepositoryCompiler.normalizeKey("Other-Sound.ogg", trim = true))
        assertEquals("a b c", RepositoryCompiler.normalizeKey("a  b\t\nc", trim = true))
        // .ogg only strips as a suffix; other dots are untouched
        assertEquals("x.oggy", RepositoryCompiler.normalizeKey("x.oggy", trim = true))
    }

    @Test
    fun `sound path encoding percent-escapes digits in filename and spaces`() {
        assertEquals(
            "https://x/y1/my %32%30 sound.ogg".replace(" ", "%20"),
            HttpQueue.encodeSoundPath("https://x/y1/my 20 sound.ogg"),
        )
    }

    @Test
    fun `sha1 is lowercase hex`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc"))
    }
}
