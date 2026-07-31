package gg.earu.chatsounds.playback

import gg.earu.chatsounds.audio.DspParams
import gg.earu.chatsounds.audio.PcmClip
import gg.earu.chatsounds.audio.Voice
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The stream facade modifiers drive — a port of the GMod webaudio stream's mutation
 * surface. Scheduling fields (duration/overlap/lifetime) are read by the playback
 * orchestrator; setters write into the voice's [DspParams] for the DSP thread.
 *
 * "JS domain" below = sample positions at the output rate, matching the browser's
 * decodeAudioData-resampled buffers so loop counts, seeks, and LFO phases stay
 * bit-compatible with the GMod implementation.
 */
class ChatStream(val clip: PcmClip, val dsp: DspParams, private val outRate: Int) {
    /** Scheduling duration in seconds; modifiers scale it (pitch, cutoff, loop, ...). */
    var duration: Double = 0.0
    var overlap: Boolean = true
    var lifetime: Double? = null

    var voice: Voice? = null

    /** Buffer length in JS-domain samples (GMod GetSampleCount). */
    val sampleCount: Double get() = clip.samples.size.toDouble() * outRate / clip.sampleRate

    /** Parity with UpdatePlaybackSpeed: negative rate sets a STICKY reverse flag, speed is abs. */
    fun setPlaybackRate(rate: Double) {
        if (rate < 0) dsp.reverse = true
        dsp.jsSpeed = abs(rate)
    }

    fun setVolume(volume: Double) {
        dsp.volumeMod = volume.toFloat()
    }

    fun setFilterType(type: Int) {
        dsp.filterType = type
    }

    fun setFilterFraction(fraction: Double) {
        dsp.filterFraction = fraction.coerceIn(0.0, 1.0).toFloat()
    }

    fun setEcho(enabled: Boolean) {
        dsp.useEcho = enabled
    }

    fun setEchoDelay(seconds: Double) {
        dsp.echoDelaySamples = ceil(outRate * seconds.coerceIn(0.0, 5.0)).toInt().coerceAtLeast(1)
    }

    fun setEchoFeedback(feedback: Double) {
        dsp.echoFeedback = feedback.toFloat()
    }

    fun setPitchLfoTime(time: Double) {
        dsp.lfoPitchTime = time
    }

    fun setPitchLfoAmount(amount: Double) {
        dsp.lfoPitchAmount = amount
    }

    fun setVolumeLfoTime(time: Double) {
        dsp.lfoVolumeTime = time
    }

    fun setVolumeLfoAmount(amount: Double) {
        dsp.lfoVolumeAmount = amount
    }

    /** GMod parity: true = infinite (-1), false = 1, number = that many playthroughs. */
    fun setMaxLoopCount(value: Any) {
        dsp.maxLoop = when (value) {
            is Boolean -> if (value) -1 else 1
            is Number -> value.toInt()
            else -> 1
        }
    }

    /** JS-domain sample position (skip/startpos: sampleCount * fraction). */
    fun setSamplePosition(position: Double) {
        dsp.seekPosition = position
    }

    fun stop() {
        voice?.stop()
    }
}
