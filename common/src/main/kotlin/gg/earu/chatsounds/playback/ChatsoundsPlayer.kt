package gg.earu.chatsounds.playback

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.audio.PcmCache
import gg.earu.chatsounds.audio.Voice
import gg.earu.chatsounds.audio.VoiceParams
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.data.SoundVariant
import gg.earu.chatsounds.parser.TriggerMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Client playback orchestration (port of player.lua's client half). A message splits on ';'
 * into independent parallel contexts; within one context downloads are launched up front and
 * sounds then play strictly in order, each "done" after its duration (minus the trailing
 * white-noise fudge) while the tail may keep ringing.
 */
object ChatsoundsPlayer {
    private const val CONTEXT_SEPARATOR = ';'

    /** GMod trims ~75 ms of trailing hiss off every sound's scheduling duration. */
    private const val WHITENOISE_FUDGE_SECONDS = 0.075

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var enabled = true

    private class ActiveSound(val speakerId: UUID?, val params: VoiceParams, val voice: Voice)

    private val activeSounds = CopyOnWriteArrayList<ActiveSound>()

    /** Plays every chatsound in [text], positioned at the player entity [speakerId] (null = local/unpositioned). */
    fun play(speakerId: UUID?, text: String) {
        if (!enabled) return
        if (DataLoader.loading != null) return
        if (text.startsWith(CONTEXT_SEPARATOR)) return

        val lowered = text.lowercase(Locale.ROOT)
        for (chunk in lowered.split(CONTEXT_SEPARATOR)) {
            scope.launch { playContext(speakerId, chunk) }
        }
    }

    fun stopAll() {
        AudioEngine.stopAll()
    }

    private suspend fun playContext(speakerId: UUID?, chunk: String) {
        val lookup = DataLoader.lookup
        val triggers = TriggerMatcher.parseSoundTriggers(chunk, lookup)
        if (triggers.isEmpty()) return

        // Seeded like GMod (rounded current time + i * 1028) so clients hearing the same
        // message at the same moment pick the same variants.
        val timeSeed = System.currentTimeMillis() / 1000L

        class Queued(val trigger: TriggerMatcher.SoundTrigger, val variant: SoundVariant?)

        var lastSound: SoundVariant? = null
        val queued = triggers.mapIndexed { i, trigger ->
            if (trigger.key == "sh") return@mapIndexed Queued(trigger, null)
            val variant = VariantSelector.select(
                lookup.list[trigger.key].orEmpty(),
                lastSound,
                seed = timeSeed + i * 1028L,
            ) ?: return@mapIndexed Queued(trigger, null)
            lastSound = variant
            Queued(trigger, variant)
        }

        // Downloads all fire immediately (bounded by the HTTP queue); playback then walks in order.
        val downloads = queued.map { q -> q.variant?.let { v -> scope.async { SoundDownloader.ensure(v) } } }

        for ((i, q) in queued.withIndex()) {
            if (q.trigger.key == "sh") {
                stopAll()
                continue
            }
            val variant = q.variant ?: continue
            val file = downloads[i]?.await() ?: continue

            val clip = try {
                PcmCache.getOrDecode(variant.url, file)
            } catch (e: Exception) {
                Chatsounds.logger.warn("Failed to decode {}: {}", variant.url, e.message)
                continue
            }

            val params = VoiceParams()
            params.volume = 0f // silent until the first client tick positions it
            val voice = AudioEngine.play(clip, params)
            activeSounds.add(ActiveSound(speakerId, params, voice))

            val duration = clip.durationSeconds / abs(params.pitch.toDouble()).coerceAtLeast(0.01) - WHITENOISE_FUDGE_SECONDS
            if (duration > 0) delay((duration * 1000).toLong())
        }
    }

    /**
     * Game-thread tick: snapshot speaker entity positions and volumes into each voice's
     * param block (read by the DSP thread at block boundaries).
     */
    fun clientTick() {
        if (activeSounds.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level
        val categoryVolume = mc.options.getSoundSourceVolume(SoundSource.PLAYERS)

        for (active in activeSounds) {
            if (active.voice.finished) {
                activeSounds.remove(active)
                continue
            }
            val params = active.params
            val speaker = active.speakerId?.let { level?.getPlayerByUUID(it) }
            when {
                active.speakerId == null -> {
                    // Local/unpositioned playback: flat volume at the listener.
                    params.relative = true
                    params.volume = categoryVolume
                }
                speaker == null -> {
                    // Speaker not in this level / out of tracking range: silent (GMod's dormant behavior).
                    params.volume = 0f
                }
                speaker === mc.player && mc.options.cameraType.isFirstPerson -> {
                    params.relative = true
                    params.volume = categoryVolume
                }
                else -> {
                    params.relative = false
                    val eye = speaker.eyePosition
                    params.x = eye.x
                    params.y = eye.y
                    params.z = eye.z
                    params.volume = categoryVolume
                }
            }
        }
    }
}
