package gg.earu.chatsounds.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal msgpack reader for chatsounds `list.msgpack` manifests: an array of 3-element
 * arrays of strings `{realm, name, relativePath}`. Only the type bytes those manifests can
 * contain are implemented; anything else is a hard error (swap in msgpack-core if a repo
 * ever produces exotic encodings).
 */
object Msgpack {
    class Entry(val realm: String, val name: String, val path: String)

    fun readSoundList(bytes: ByteArray): List<Entry> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = readArrayHeader(buf)
        val entries = ArrayList<Entry>(count)
        repeat(count) {
            val len = readArrayHeader(buf)
            require(len == 3) { "Expected 3-tuple in list.msgpack, got $len elements" }
            entries.add(Entry(readString(buf), readString(buf), readString(buf)))
        }
        return entries
    }

    private fun readArrayHeader(buf: ByteBuffer): Int {
        val b = buf.get().toInt() and 0xff
        return when {
            b in 0x90..0x9f -> b and 0x0f
            b == 0xdc -> buf.short.toInt() and 0xffff
            b == 0xdd -> buf.int.also { require(it >= 0) { "array32 too large" } }
            else -> error("Unexpected msgpack type byte 0x%02x, expected array".format(b))
        }
    }

    private fun readString(buf: ByteBuffer): String {
        val b = buf.get().toInt() and 0xff
        val len = when {
            b in 0xa0..0xbf -> b and 0x1f
            b == 0xd9 -> buf.get().toInt() and 0xff
            b == 0xda -> buf.short.toInt() and 0xffff
            b == 0xdb -> buf.int.also { require(it >= 0) { "str32 too large" } }
            else -> error("Unexpected msgpack type byte 0x%02x, expected string".format(b))
        }
        val out = ByteArray(len)
        buf.get(out)
        return String(out, Charsets.UTF_8)
    }
}
