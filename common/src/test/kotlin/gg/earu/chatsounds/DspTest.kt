package gg.earu.chatsounds

import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.audio.DspParams
import gg.earu.chatsounds.audio.LoudnessAnalyzer
import gg.earu.chatsounds.audio.PcmClip
import gg.earu.chatsounds.audio.Voice
import gg.earu.chatsounds.audio.VoiceParams
import gg.earu.chatsounds.playback.ChatStream
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DSP parity checks against the webaudio.lua ScriptProcessor semantics (no OpenAL involved). */
class DspTest {
    private fun voice(samples: FloatArray, rate: Int = 48_000, gain: Float = 1f, dsp: DspParams = DspParams()): Voice =
        Voice(PcmClip(samples, rate, gain), VoiceParams(), dsp)

    private fun render(voice: Voice, maxBlocks: Int = 10_000): FloatArray {
        val out = ArrayList<Float>()
        var blocks = 0
        while (!voice.allQueued && blocks++ < maxBlocks) {
            val produced = AudioEngine.synthesizeBlock(voice)
            for (i in 0 until produced) out.add(AudioEngine.blockFloats[i])
            if (produced == 0) break
        }
        return out.toFloatArray()
    }

    @Test
    fun `default plays exactly once`() {
        val n = 4800
        val v = voice(FloatArray(n) { 0.5f })
        val out = render(v)
        // JS: done when position > len (strictly), so one extra sample sneaks in.
        assertTrue(abs(out.size - n) <= 1, "expected ~$n samples, got ${out.size}")
        assertTrue(out.take(100).all { it == 0.5f })
    }

    @Test
    fun `maxLoop plays that many times`() {
        val n = 2400
        val dsp = DspParams().apply { maxLoop = 3 }
        val out = render(voice(FloatArray(n) { 0.25f }, dsp = dsp))
        assertTrue(abs(out.size - n * 3) <= 1, "expected ~${n * 3}, got ${out.size}")
    }

    @Test
    fun `double speed halves output length`() {
        val n = 4800
        val dsp = DspParams().apply { jsSpeed = 2.0 }
        val out = render(voice(FloatArray(n) { 0.5f }, dsp = dsp))
        assertTrue(abs(out.size - n / 2) <= 1, "expected ~${n / 2}, got ${out.size}")
    }

    @Test
    fun `reverse plays samples backwards`() {
        val n = 4800
        val ramp = FloatArray(n) { it.toFloat() / n }
        val dsp = DspParams().apply { reverse = true }
        val out = render(voice(ramp, dsp = dsp))
        // First reverse sample reads index len-position; values must descend.
        assertTrue(out[10] > out[out.size - 10], "reverse should descend: ${out[10]} vs ${out[out.size - 10]}")
    }

    @Test
    fun `volume modifier scales and normalization gain folds in`() {
        val dsp = DspParams().apply { volumeMod = 0.5f }
        val out = render(voice(FloatArray(1000) { 0.8f }, gain = 0.5f, dsp = dsp))
        assertTrue(abs(out[50] - 0.8f * 0.5f * 0.5f) < 1e-6)
    }

    @Test
    fun `lowpass smooths a step, highpass removes dc`() {
        val step = FloatArray(4800) { 0.8f }
        val lowDsp = DspParams().apply { filterType = 1; filterFraction = 0.1f }
        val low = render(voice(step, dsp = lowDsp))
        assertTrue(low[0] < 0.2f && low[200] > 0.7f, "lowpass should approach input: ${low[0]} -> ${low[200]}")

        val highDsp = DspParams().apply { filterType = 2; filterFraction = 0.1f }
        val high = render(voice(step, dsp = highDsp))
        assertTrue(abs(high[0]) > 0.5f && abs(high[400]) < 0.05f, "highpass should decay dc: ${high[0]} -> ${high[400]}")
    }

    @Test
    fun `echo repeats an impulse with feedback decay`() {
        val n = 4800
        val impulse = FloatArray(n).also { it[0] = 1f }
        val dsp = DspParams().apply {
            useEcho = true
            echoDelaySamples = 1000
            echoFeedback = 0.5f
        }
        val out = render(voice(impulse, dsp = dsp))
        assertTrue(abs(out[0] - 1f) < 1e-6)
        assertTrue(abs(out[1000] - 0.5f) < 1e-6, "first echo at delay: ${out[1000]}")
        assertTrue(abs(out[2000] - 0.25f) < 1e-6, "second echo decays: ${out[2000]}")
        // Tail keeps ringing past the dry end, then dies below the floor.
        assertTrue(out.size > n, "echo tail should outlive the clip: ${out.size}")
    }

    @Test
    fun `seek jumps the playhead`() {
        val n = 4800
        val ramp = FloatArray(n) { it.toFloat() / n }
        val dsp = DspParams().apply { seekPosition = 2400.0 }
        val out = render(voice(ramp, dsp = dsp))
        assertTrue(abs(out[0] - 0.5f) < 1e-3, "seek to midpoint should start at 0.5: ${out[0]}")
        assertTrue(abs(out.size - 2400) <= 1)
    }

    @Test
    fun `chatstream maps modifier surface onto dsp`() {
        val clip = PcmClip(FloatArray(48_000), 48_000, 1f)
        val dsp = DspParams()
        val stream = ChatStream(clip, dsp, 48_000)

        stream.setPlaybackRate(-2.0)
        assertTrue(dsp.reverse); assertEquals(2.0, dsp.jsSpeed)

        stream.setMaxLoopCount(true); assertEquals(-1, dsp.maxLoop)
        stream.setMaxLoopCount(false); assertEquals(1, dsp.maxLoop)
        stream.setMaxLoopCount(5); assertEquals(5, dsp.maxLoop)

        stream.setEchoDelay(0.5)
        assertEquals(24_000, dsp.echoDelaySamples)

        assertEquals(48_000.0, stream.sampleCount)
    }

    @Test
    fun `loudness gain boosts quiet and is peak limited`() {
        val quiet = FloatArray(48_000) { (sin(it / 40.0) * 0.05).toFloat() }
        val g = LoudnessAnalyzer.computeGain(quiet, 48_000)
        assertTrue(g > 1.5f, "quiet content should be boosted, got $g")

        val loud = FloatArray(48_000) { (sin(it / 40.0) * 0.9).toFloat() }
        val gl = LoudnessAnalyzer.computeGain(loud, 48_000)
        assertTrue(gl <= 1f / 0.9f + 1e-3, "gain must be peak limited, got $gl")
        assertTrue(gl >= 0.25f)
    }
}
