package gg.earu.chatsounds.audio

/** A fully-decoded sound: mono float PCM at its native sample rate, loudness pre-analyzed. */
class PcmClip(
    val samples: FloatArray,
    val sampleRate: Int,
    val normalizeGain: Float,
) {
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate
    val byteSize: Long get() = samples.size.toLong() * Float.SIZE_BYTES
}

/**
 * Loudness normalization so no sound is overly quiet or loud: a simplified gated RMS in the
 * spirit of EBU R128 / ITU BS.1770. Constants are the parity contract with webaudio.lua.
 */
object LoudnessAnalyzer {
    private const val TARGET_RMS = 0.125f      // -18 dBFS gated RMS reference
    private const val MIN_GAIN = 0.25f         // -12 dB floor
    private const val MAX_GAIN = 4.0f          // +12 dB ceiling
    private const val BLOCK_MS = 100           // gating block size
    private const val ABS_GATE = 1e-6          // -60 dBFS mean-square absolute gate
    private const val REL_GATE = 0.1           // -10 dB relative to the ungated mean
    private const val MAX_SAMPLES = 500_000    // analysis budget; longer buffers get strided

    fun computeGain(samples: FloatArray, sampleRate: Int): Float {
        if (samples.isEmpty()) return 1f

        val stride = if (samples.size > MAX_SAMPLES) samples.size / MAX_SAMPLES + 1 else 1
        val blockLen = maxOf(1, sampleRate * BLOCK_MS / 1000 / stride)

        // Per-block mean squares plus overall peak.
        val blockMs = ArrayList<Double>()
        var peak = 0f
        var acc = 0.0
        var accCount = 0
        var i = 0
        while (i < samples.size) {
            val v = samples[i]
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            acc += v.toDouble() * v
            accCount++
            if (accCount >= blockLen) {
                blockMs.add(acc / accCount)
                acc = 0.0
                accCount = 0
            }
            i += stride
        }
        if (accCount > 0) blockMs.add(acc / accCount)

        // Absolute gate, then relative gate against the ungated mean; plain RMS fallback.
        var gated = blockMs.filter { it > ABS_GATE }
        if (gated.isNotEmpty()) {
            val ungatedMean = gated.sum() / gated.size
            val relGated = gated.filter { it > ungatedMean * REL_GATE }
            if (relGated.isNotEmpty()) gated = relGated
        }
        val meanSquare = if (gated.isNotEmpty()) {
            gated.sum() / gated.size
        } else {
            blockMs.sum() / blockMs.size
        }
        if (meanSquare <= 0.0) return 1f

        var gain = (TARGET_RMS / Math.sqrt(meanSquare).toFloat()).coerceIn(MIN_GAIN, MAX_GAIN)
        // Peak-limit so a boosted sound can never clip.
        if (peak > 0f) gain = minOf(gain, 1f / peak)
        return gain
    }
}
