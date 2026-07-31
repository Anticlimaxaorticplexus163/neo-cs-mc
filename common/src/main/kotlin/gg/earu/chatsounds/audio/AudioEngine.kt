package gg.earu.chatsounds.audio

import gg.earu.chatsounds.Chatsounds
import org.lwjgl.openal.AL10
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Parameters written by the game thread each tick and read by the DSP thread at block boundaries. */
class VoiceParams {
    /** User-facing volume multiplier (modifiers, category volume, mod config). */
    @Volatile var volume: Float = 1f
    /** DSP playback-rate multiplier — unclamped, ±50x territory; negative means reverse (M2). */
    @Volatile var pitch: Float = 1f
    @Volatile var x: Double = 0.0
    @Volatile var y: Double = 0.0
    @Volatile var z: Double = 0.0
    /** First-person own voice: position is relative to the listener (0,0,0 = "flat volume"). */
    @Volatile var relative: Boolean = false
    @Volatile var maxDistance: Float = 64f
}

/**
 * One playing sound: an OpenAL source fed 20 ms mono blocks synthesized by the DSP thread.
 * The DSP does its own rate conversion (position advances by pitch * srcRate/outRate per
 * output sample, nearest-neighbor — that aliasing IS the chatsounds sound), so AL_PITCH is
 * never used and extreme rates work.
 */
class Voice internal constructor(
    val clip: PcmClip,
    val params: VoiceParams,
) {
    @Volatile var stopRequested: Boolean = false
    /** True once the source drained every synthesized sample (or was stopped). */
    @Volatile var finished: Boolean = false

    // DSP-thread state.
    internal var source = 0
    internal var freeBuffers = IntArray(0)
    internal var freeCount = 0
    internal var position = 0.0
    internal var allQueued = false
    internal var initialized = false

    fun stop() {
        stopRequested = true
    }
}

/**
 * The mixer: a dedicated daemon thread owning our own AL sources on Minecraft's AL context.
 * OpenAL has no per-thread context binding, so calling AL from this thread is safe as long
 * as the game has initialized its sound library — and vanilla keeps the AL listener
 * (position, orientation, master gain) updated every frame for free.
 */
object AudioEngine {
    private const val OUT_RATE = 48_000
    private const val BLOCK_FRAMES = 960 // 20 ms
    private const val QUEUE_DEPTH = 4

    private val pending = ConcurrentLinkedQueue<Voice>()
    private val voices = CopyOnWriteArrayList<Voice>()
    @Volatile private var stopAllRequested = false
    @Volatile private var running = false

    private val blockShorts = ShortArray(BLOCK_FRAMES)
    private val blockBytes: ByteBuffer = ByteBuffer.allocateDirect(BLOCK_FRAMES * 2).order(ByteOrder.nativeOrder())

    fun start() {
        if (running) return
        running = true
        thread(name = "chatsounds-audio", isDaemon = true) { runLoop() }
    }

    fun play(clip: PcmClip, params: VoiceParams): Voice {
        val voice = Voice(clip, params)
        pending.add(voice)
        return voice
    }

    /** The "sh" path: immediate stop of every chatsound voice. */
    fun stopAll() {
        stopAllRequested = true
    }

    val activeVoices: Int get() = voices.size

    private fun runLoop() {
        Chatsounds.logger.info("Audio engine thread started")
        while (running) {
            try {
                tick()
            } catch (e: Throwable) {
                Chatsounds.logger.error("Audio engine tick failed", e)
            }
            Thread.sleep(5)
        }
    }

    private fun tick() {
        if (stopAllRequested) {
            stopAllRequested = false
            for (voice in voices) voice.stopRequested = true
        }

        while (true) {
            val voice = pending.poll() ?: break
            voices.add(voice)
        }

        for (voice in voices) {
            if (!voice.initialized) initVoice(voice)
            pump(voice)
            if (voice.finished) {
                destroyVoice(voice)
                voices.remove(voice)
            }
        }

        val err = AL10.alGetError()
        if (err != AL10.AL_NO_ERROR) {
            Chatsounds.logger.warn("OpenAL error 0x{}", Integer.toHexString(err))
        }
    }

    private fun initVoice(voice: Voice) {
        voice.source = AL10.alGenSources()
        voice.freeBuffers = IntArray(QUEUE_DEPTH)
        AL10.alGenBuffers(voice.freeBuffers)
        voice.freeCount = QUEUE_DEPTH

        // Vanilla's global model is AL_LINEAR_DISTANCE_CLAMPED; mirror its per-source setup.
        AL10.alSourcef(voice.source, AL10.AL_ROLLOFF_FACTOR, 1f)
        AL10.alSourcef(voice.source, AL10.AL_REFERENCE_DISTANCE, 0f)
        AL10.alSourcef(voice.source, AL10.AL_MAX_DISTANCE, voice.params.maxDistance)
        voice.initialized = true
    }

    private fun pump(voice: Voice) {
        val src = voice.source

        if (voice.stopRequested) {
            AL10.alSourceStop(src)
            voice.finished = true
            return
        }

        // Reclaim processed buffers.
        var processed = AL10.alGetSourcei(src, AL10.AL_BUFFERS_PROCESSED)
        while (processed-- > 0) {
            val buf = AL10.alSourceUnqueueBuffers(src)
            if (voice.freeCount < voice.freeBuffers.size) {
                voice.freeBuffers[voice.freeCount++] = buf
            }
        }

        // Apply game-thread params.
        val p = voice.params
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, if (p.relative) AL10.AL_TRUE else AL10.AL_FALSE)
        if (p.relative) {
            AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f)
        } else {
            AL10.alSource3f(src, AL10.AL_POSITION, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
        }
        AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, p.maxDistance)
        AL10.alSourcef(src, AL10.AL_GAIN, maxOf(0f, p.volume))

        // Synthesize while the queue has room.
        while (!voice.allQueued && voice.freeCount > 0) {
            val produced = synthesizeBlock(voice)
            if (produced == 0) break
            val buf = voice.freeBuffers[--voice.freeCount]
            blockBytes.clear()
            blockBytes.asShortBuffer().put(blockShorts, 0, produced)
            blockBytes.limit(produced * 2)
            AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, blockBytes, OUT_RATE)
            AL10.alSourceQueueBuffers(src, buf)
        }

        val state = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE)
        val queued = AL10.alGetSourcei(src, AL10.AL_BUFFERS_QUEUED)
        if (state != AL10.AL_PLAYING && queued > 0) {
            // Initial start and underrun recovery share this path.
            AL10.alSourcePlay(src)
        }
        if (voice.allQueued && queued == 0 && state != AL10.AL_PLAYING) {
            voice.finished = true
        }
    }

    /**
     * Fills [blockShorts]; returns frames produced (0 = clip exhausted). M1 DSP: rate
     * conversion + normalization gain + clamp. The full parity chain (filters, echo, LFOs,
     * loops, reverse) lands in M2 inside this loop.
     */
    private fun synthesizeBlock(voice: Voice): Int {
        val samples = voice.clip.samples
        val gain = voice.clip.normalizeGain
        val speed = voice.params.pitch.toDouble() * voice.clip.sampleRate / OUT_RATE
        var position = voice.position
        var produced = 0

        while (produced < BLOCK_FRAMES) {
            val idx = position.toInt()
            if (idx >= samples.size || idx < 0) {
                voice.allQueued = true
                break
            }
            var v = samples[idx] * gain
            if (v.isNaN()) v = 0f
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            blockShorts[produced++] = (v * 32767f).toInt().toShort()
            position += speed
        }

        voice.position = position
        return produced
    }

    private fun destroyVoice(voice: Voice) {
        if (!voice.initialized) return
        AL10.alSourceStop(voice.source)
        var queued = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_QUEUED)
        while (queued-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(voice.source))
        AL10.alDeleteSources(voice.source)
        for (i in 0 until voice.freeCount) AL10.alDeleteBuffers(voice.freeBuffers[i])
        voice.initialized = false
    }
}
