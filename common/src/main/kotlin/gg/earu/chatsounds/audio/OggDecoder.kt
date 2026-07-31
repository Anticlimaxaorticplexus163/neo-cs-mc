package gg.earu.chatsounds.audio

import org.lwjgl.stb.STBVorbis
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.libc.LibCStdlib

/**
 * Full-decode Ogg Vorbis to mono float PCM via stb_vorbis — the exact decoder Minecraft
 * itself ships (LWJGL). Chatsounds are short clips; random access (reverse, loops, seeks)
 * wants the whole buffer in memory, same as the GMod addon's decodeAudioData approach.
 */
object OggDecoder {
    /** Refuse to fully decode absurdly long files (config later); ~60s at 48k stereo ≈ 23 MB of floats. */
    private const val MAX_DURATION_SECONDS = 60

    fun decode(bytes: ByteArray): PcmClip {
        val mem = MemoryUtil.memAlloc(bytes.size)
        try {
            mem.put(bytes).flip()
            MemoryStack.stackPush().use { stack ->
                val channelsBuf = stack.mallocInt(1)
                val rateBuf = stack.mallocInt(1)
                val pcm = STBVorbis.stb_vorbis_decode_memory(mem, channelsBuf, rateBuf)
                    ?: error("stb_vorbis failed to decode ogg (${bytes.size} bytes)")
                try {
                    val channels = channelsBuf.get(0)
                    val rate = rateBuf.get(0)
                    val frames = pcm.remaining() / channels
                    require(frames <= rate * MAX_DURATION_SECONDS) {
                        "Sound too long: ${frames / rate}s (max ${MAX_DURATION_SECONDS}s)"
                    }

                    // Downmix to mono: mono is mandatory for OpenAL spatialization.
                    val mono = FloatArray(frames)
                    val scale = 1f / (32768f * channels)
                    for (i in 0 until frames) {
                        var acc = 0
                        for (c in 0 until channels) acc += pcm.get(i * channels + c)
                        mono[i] = acc * scale
                    }

                    return PcmClip(mono, rate, LoudnessAnalyzer.computeGain(mono, rate))
                } finally {
                    LibCStdlib.free(pcm)
                }
            }
        } finally {
            MemoryUtil.memFree(mem)
        }
    }
}
