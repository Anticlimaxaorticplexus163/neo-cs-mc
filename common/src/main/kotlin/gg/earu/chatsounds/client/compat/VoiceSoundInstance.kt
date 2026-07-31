package gg.earu.chatsounds.client.compat

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.audio.VoiceParams
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundManager
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource

/**
 * Minimal SoundInstance facade over a voice's live params, handed to Dynamic Surroundings.
 * DS only reads position (captureState) and category; it never resolves the sound through
 * the sound manager.
 */
class VoiceSoundInstance(private val params: VoiceParams) : SoundInstance {
    private val location = ResourceLocation.fromNamespaceAndPath(Chatsounds.MOD_ID, "voice")

    override fun getLocation(): ResourceLocation = location
    override fun resolve(manager: SoundManager): WeighedSoundEvents? = null
    override fun getSound(): Sound? = null
    override fun getSource(): SoundSource = SoundSource.PLAYERS
    override fun isLooping(): Boolean = false
    override fun isRelative(): Boolean = params.relative
    override fun getDelay(): Int = 0
    override fun getVolume(): Float = params.volume
    override fun getPitch(): Float = 1f
    override fun getX(): Double = params.x
    override fun getY(): Double = params.y
    override fun getZ(): Double = params.z
    override fun getAttenuation(): SoundInstance.Attenuation = SoundInstance.Attenuation.LINEAR
}
