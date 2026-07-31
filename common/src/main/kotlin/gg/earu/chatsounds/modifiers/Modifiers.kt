package gg.earu.chatsounds.modifiers

import gg.earu.chatsounds.data.SoundVariant
import gg.earu.chatsounds.parser.expr.ExprFn
import gg.earu.chatsounds.playback.ChatStream
import kotlin.math.abs
import kotlin.math.floor

/**
 * Port of lua/neo-chatsounds/modifiers/. Modifier keys are the GMod FILENAMES (that is what
 * the parser matched against), so users type `:lfopitch(...)`, `:rep(...)`, etc. Values are
 * dynamically typed like the Lua originals: Double, DoubleArray, or String.
 */
abstract class Modifier(val key: String) {
    open val legacySyntax: String? = null
    open val onlyLegacy: Boolean = false
    open val noInheritance: Boolean = false
    abstract val defaultValue: Any
    open val legacyDefaultValue: Any? = null

    open fun parseArgs(args: String): Any = defaultValue
    open fun legacyParseArgs(args: String): Any = parseArgs(args)

    /** rep: how many times the sound/group is duplicated at flatten time. */
    open fun duplicateCount(inst: ModifierInstance): Int? = null

    /** select/realm: adjust the variant pool or index before playback. Null = untouched. */
    open fun onSelection(inst: ModifierInstance, index: Int, sounds: List<SoundVariant>): Pair<Int, List<SoundVariant>>? = null

    open fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {}
    open fun onStreamThink(inst: ModifierInstance, stream: ChatStream) {}
}

/** One parsed use of a modifier in a message, with its per-playback mutable state. */
class ModifierInstance(
    val def: Modifier,
    var value: Any?,
    val exprFn: ExprFn?,
    val isLegacy: Boolean,
    val startIndex: Int,
    val endIndex: Int,
) {
    var startTime: Double = 0.0
    var streamStarted: Boolean = false

    fun now(): Double = System.nanoTime() / 1e9

    /** GMod GetValue pattern for scalar modifiers: expression -> clamp, else stored value. */
    fun numberValue(default: Double, clamp: (Double) -> Double): Double {
        if (exprFn != null) {
            val n = exprFn.eval()?.firstOrNull() ?: return default
            return clamp(n)
        }
        return (value as? Double) ?: default
    }

    /** GMod GetValue pattern for pair modifiers, clamping each element independently. */
    fun pairValue(default: DoubleArray, clamp1: (Double) -> Double, clamp2: (Double) -> Double): DoubleArray {
        if (exprFn != null) {
            val list = exprFn.eval() ?: return default
            if (list.isEmpty()) return default
            val a = list.getOrNull(0)?.let(clamp1) ?: default[0]
            val b = list.getOrNull(1)?.let(clamp2) ?: default[1]
            return doubleArrayOf(a, b)
        }
        return (value as? DoubleArray) ?: default
    }

    /** Modifiers whose GetValue ignores expressions entirely (rep, skip, startpos, loop, overlap). */
    fun rawNumber(default: Double): Double =
        if (exprFn != null) default else (value as? Double) ?: default
}

private fun num(args: String): Double? = args.trim().toDoubleOrNull()

private fun lerp(m: Double, a: Double, b: Double) = (b - a) * m + a

object PitchModifier : Modifier("pitch") {
    override val legacySyntax = "%"
    override val defaultValue: Any = 1.0

    override fun parseArgs(args: String): Any = num(args)?.coerceIn(-50.0, 50.0) ?: defaultValue
    override fun legacyParseArgs(args: String): Any = ((num(args) ?: 100.0) / 100.0).coerceIn(-50.0, 50.0)

    fun value(inst: ModifierInstance) = inst.numberValue(1.0) { it.coerceIn(-50.0, 50.0) }

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        stream.duration /= abs(value(inst)).coerceAtLeast(1e-9)
        stream.overlap = false
    }

    override fun onStreamThink(inst: ModifierInstance, stream: ChatStream) {
        stream.setPlaybackRate(value(inst))
    }
}

object VolumeModifier : Modifier("volume") {
    override val legacySyntax = "^"
    override val defaultValue: Any = 1.0

    override fun parseArgs(args: String): Any = num(args)?.let { abs(it) } ?: 1.0
    override fun legacyParseArgs(args: String): Any = num(args)?.let { abs(it / 100.0) } ?: 1.0

    override fun onStreamThink(inst: ModifierInstance, stream: ChatStream) {
        stream.setVolume(inst.numberValue(1.0) { abs(it) })
    }
}

object LegacyPitchModifier : Modifier("legacy_pitch") {
    override val legacySyntax = "%%"
    override val onlyLegacy = true
    override val defaultValue: Any = doubleArrayOf(100.0, 100.0)

    override fun parseArgs(args: String): Any {
        // Lua splits legacy args on "." — "%%50.80" lerps 50 -> 80.
        val parts = args.split(".")
        val start = (parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 100.0).coerceIn(1.0, 255.0)
        val end = (parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: start).coerceIn(1.0, 255.0)
        return doubleArrayOf(start, end)
    }

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        val v = inst.value as? DoubleArray ?: doubleArrayOf(100.0, 100.0)
        stream.duration /= abs(v[0]) / 100.0
        stream.setMaxLoopCount(true)
        inst.startTime = inst.now()
    }

    override fun onStreamThink(inst: ModifierInstance, stream: ChatStream) {
        val v = inst.pairValue(doubleArrayOf(100.0, 100.0), { it.coerceIn(1.0, 255.0) }, { it.coerceIn(1.0, 255.0) })
        val f = (inst.now() - inst.startTime) / stream.duration
        stream.setPlaybackRate(lerp(f, v[0], v[1]) / 100.0)
        if (stream.overlap && f >= 1) stream.stop()
    }
}

object LegacyVolumeModifier : Modifier("legacy_volume") {
    override val legacySyntax = "^^"
    override val onlyLegacy = true
    override val defaultValue: Any = doubleArrayOf(100.0, 100.0)

    override fun parseArgs(args: String): Any {
        val parts = args.split(".")
        val start = (parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 100.0).coerceAtLeast(1.0)
        val end = (parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: start).coerceAtLeast(1.0)
        return doubleArrayOf(start, end)
    }

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        inst.startTime = inst.now()
    }

    override fun onStreamThink(inst: ModifierInstance, stream: ChatStream) {
        val v = inst.pairValue(doubleArrayOf(100.0, 100.0), { it.coerceAtLeast(1.0) }, { it.coerceAtLeast(1.0) })
        val f = (inst.now() - inst.startTime) / stream.duration
        stream.setVolume(lerp(f, v[0], v[1]) / 100.0)
    }
}

object DurationModifier : Modifier("duration") {
    override val legacySyntax = "="
    override val defaultValue: Any = 0.0

    override fun parseArgs(args: String): Any = num(args)?.coerceAtLeast(0.0) ?: -1.0

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        if (inst.value != -1.0) {
            stream.duration = inst.numberValue(0.0) { it.coerceAtLeast(0.0) }
        }
        if (inst.isLegacy) stream.overlap = true
    }
}

object CutoffModifier : Modifier("cutoff") {
    override val legacySyntax = "--"
    override val defaultValue: Any = 100.0

    override fun parseArgs(args: String): Any = num(args)?.coerceAtLeast(0.0) ?: defaultValue

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        stream.duration *= inst.numberValue(100.0) { it.coerceAtLeast(0.0) } / 100.0
        stream.overlap = false
    }
}

object SkipModifier : Modifier("skip") {
    override val legacySyntax = "++"
    override val defaultValue: Any = 0.0

    override fun parseArgs(args: String): Any = (num(args)?.coerceAtLeast(0.0) ?: 0.0) / 100.0

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        stream.duration -= stream.duration * inst.rawNumber(0.0)
    }

    override fun onStreamThink(inst: ModifierInstance, stream: ChatStream) {
        if (!inst.streamStarted) {
            inst.streamStarted = true
            stream.setSamplePosition(stream.sampleCount * inst.rawNumber(0.0))
        }
    }
}

object StartposModifier : Modifier("startpos") {
    override val defaultValue: Any = 0.0

    override fun parseArgs(args: String): Any = (num(args)?.coerceAtLeast(0.0) ?: 0.0) / 100.0

    override fun onStreamThink(inst: ModifierInstance, stream: ChatStream) {
        if (!inst.streamStarted) {
            inst.streamStarted = true
            stream.setSamplePosition(stream.sampleCount * inst.rawNumber(0.0))
        }
    }
}

object SelectModifier : Modifier("select") {
    override val legacySyntax = "#"
    override val defaultValue: Any = 0.0

    override fun parseArgs(args: String): Any = num(args)?.coerceAtLeast(1.0) ?: -1.0

    override fun onSelection(inst: ModifierInstance, index: Int, sounds: List<SoundVariant>): Pair<Int, List<SoundVariant>>? {
        if (inst.exprFn != null || inst.value == -1.0) return null
        // GMod indexes are 1-based; the caller clamps.
        return ((inst.value as? Double)?.toInt() ?: return null) to sounds
    }
}

object RealmModifier : Modifier("realm") {
    override val defaultValue: Any = ""

    override fun parseArgs(args: String): Any = args.trim()

    override fun onSelection(inst: ModifierInstance, index: Int, sounds: List<SoundVariant>): Pair<Int, List<SoundVariant>>? {
        val realm = inst.value as? String ?: return null
        if (inst.exprFn != null || realm.isEmpty()) return null
        return index to sounds.filter { it.realm == realm }
    }
}

object RepModifier : Modifier("rep") {
    override val legacySyntax = "*"
    override val defaultValue: Any = 1.0
    override val noInheritance = true

    override fun parseArgs(args: String): Any = num(args)?.coerceAtLeast(1.0) ?: 1.0

    override fun duplicateCount(inst: ModifierInstance): Int = inst.rawNumber(1.0).toInt()
}

object LoopModifier : Modifier("loop") {
    override val defaultValue: Any = -1.0 // no argument means loop forever

    /** Keep a single sound from hogging the whole message. */
    private const val MAX_LOOPS = 100.0

    /** An endless sound cannot be waited on; `sh` still clears it earlier. */
    private const val MAX_INFINITE_LOOP_DURATION = 30.0

    override fun parseArgs(args: String): Any {
        val n = num(args)
        if (n == null || n < 0) return defaultValue
        return floor(n).coerceAtMost(MAX_LOOPS)
    }

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        val loopCount = inst.rawNumber(-1.0)
        if (loopCount == 0.0) {
            stream.setMaxLoopCount(false)
            return
        }

        stream.setMaxLoopCount(if (loopCount < 0) true else loopCount.toInt())

        if (loopCount < 0) {
            // The sound never ends on its own; let the rest of the message play over it.
            stream.overlap = true
            stream.lifetime = MAX_INFINITE_LOOP_DURATION
        } else {
            // Multiplying keeps duration-scaling modifiers working regardless of order.
            stream.duration *= loopCount
        }
    }
}

object OverlapModifier : Modifier("overlap") {
    override val defaultValue: Any = 0.0

    override fun parseArgs(args: String): Any = num(args)?.coerceAtLeast(0.0) ?: 0.0

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        stream.overlap = inst.rawNumber(0.0) != 0.0
    }
}

object EchoModifier : Modifier("echo") {
    override val defaultValue: Any = doubleArrayOf(0.25, 0.5)

    override fun parseArgs(args: String): Any {
        val parts = args.split(",")
        val delay = (parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 0.25).coerceAtLeast(0.0)
        val feedback = (parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.5).coerceAtLeast(0.0)
        return doubleArrayOf(delay, feedback)
    }

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        val v = inst.pairValue(doubleArrayOf(0.25, 0.5), { it.coerceAtLeast(0.0) }, { it.coerceAtLeast(0.0) })
        stream.setEcho(true)
        stream.setEchoDelay(v[0])
        stream.setEchoFeedback(v[1])
    }
}

object LowpassModifier : Modifier("lowpass") {
    override val defaultValue: Any = 0.5

    override fun parseArgs(args: String): Any = num(args)?.coerceAtMost(1.0) ?: defaultValue

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        stream.setFilterType(1)
        stream.setFilterFraction(inst.numberValue(0.5) { it.coerceAtMost(1.0) })
    }
}

object HighpassModifier : Modifier("highpass") {
    override val defaultValue: Any = 0.5

    override fun parseArgs(args: String): Any = num(args)?.coerceAtMost(1.0) ?: defaultValue

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        stream.setFilterType(2)
        stream.setFilterFraction(inst.numberValue(0.5) { it.coerceAtMost(1.0) })
    }
}

object LfoPitchModifier : Modifier("lfopitch") {
    override val defaultValue: Any = doubleArrayOf(5.0, 0.1)

    override fun parseArgs(args: String): Any {
        val parts = args.split(",")
        val time = (parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 5.0).coerceAtLeast(0.0)
        val amount = (parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.1).coerceAtLeast(0.0)
        return doubleArrayOf(time, amount)
    }

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        val v = inst.pairValue(doubleArrayOf(5.0, 0.1), { it.coerceAtLeast(0.0) }, { it.coerceAtLeast(0.0) })
        stream.setPitchLfoAmount(v[1])
        stream.setPitchLfoTime(v[0])
    }
}

object LfoVolumeModifier : Modifier("lfovolume") {
    override val defaultValue: Any = doubleArrayOf(5.0, 0.1)

    override fun parseArgs(args: String): Any {
        val parts = args.split(",")
        val time = (parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 5.0).coerceAtLeast(0.0)
        val amount = (parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.1).coerceAtLeast(0.0)
        return doubleArrayOf(time, amount)
    }

    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) {
        val v = inst.pairValue(doubleArrayOf(5.0, 0.1), { it.coerceAtLeast(0.0) }, { it.coerceAtLeast(0.0) })
        stream.setVolumeLfoAmount(v[1])
        stream.setVolumeLfoTime(v[0])
    }
}

/** A legacy_* entry synthesized from a modifier with a LegacySyntax, GMod-registry style. */
class LegacyModifier(val base: Modifier) : Modifier("legacy_" + base.key) {
    override val defaultValue: Any get() = base.legacyDefaultValue ?: base.defaultValue
    override val noInheritance: Boolean get() = base.noInheritance
    override fun parseArgs(args: String): Any = base.legacyParseArgs(args)
    override fun duplicateCount(inst: ModifierInstance): Int? = base.duplicateCount(inst)
    override fun onSelection(inst: ModifierInstance, index: Int, sounds: List<SoundVariant>) = base.onSelection(inst, index, sounds)
    override fun onStreamInit(inst: ModifierInstance, stream: ChatStream) = base.onStreamInit(inst, stream)
    override fun onStreamThink(inst: ModifierInstance, stream: ChatStream) = base.onStreamThink(inst, stream)
}

object Modifiers {
    private val base = listOf(
        CutoffModifier, DurationModifier, EchoModifier, HighpassModifier,
        LegacyPitchModifier, LegacyVolumeModifier, LfoPitchModifier, LfoVolumeModifier,
        LoopModifier, LowpassModifier, OverlapModifier, PitchModifier, RealmModifier,
        RepModifier, SelectModifier, SkipModifier, StartposModifier, VolumeModifier,
    )

    /** Parser lookup: bare keys (non-onlyLegacy) plus synthesized "legacy_<key>" entries. */
    val lookup: Map<String, Modifier> = buildMap {
        for (mod in base) {
            if (!mod.onlyLegacy) put(mod.key, mod)
            if (mod.legacySyntax != null) put("legacy_${mod.key}", LegacyModifier(mod))
        }
    }

    /** Legacy syntaxes longest-first with their rewrite target names (parser rewrite pass). */
    val legacySyntaxes: List<Pair<String, String>> = base
        .mapNotNull { mod -> mod.legacySyntax?.let { it to "legacy_${mod.key}" } }
        .sortedByDescending { it.first.length }
}
