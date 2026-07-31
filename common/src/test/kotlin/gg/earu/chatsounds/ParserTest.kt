package gg.earu.chatsounds

import gg.earu.chatsounds.data.SoundLookup
import gg.earu.chatsounds.modifiers.ModifierInstance
import gg.earu.chatsounds.parser.GroupNode
import gg.earu.chatsounds.parser.ParseNode
import gg.earu.chatsounds.parser.Parser
import gg.earu.chatsounds.parser.SoundNode
import gg.earu.chatsounds.parser.TriggerMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserTest {
    private fun lookup(vararg keys: String): SoundLookup {
        val map = HashMap<String, List<gg.earu.chatsounds.data.SoundVariant>>()
        for (key in keys) map[key] = emptyList()
        map["sh"] = emptyList()
        return SoundLookup(map, keys.maxOf { it.length })
    }

    private fun flattenSoundNodes(group: GroupNode, out: MutableList<SoundNode> = ArrayList()): List<SoundNode> {
        for (child in group.children) {
            when {
                child is SoundNode -> out.add(child)
                child is GroupNode && !child.isModifierExpression -> flattenSoundNodes(child, out)
            }
        }
        return out
    }

    private fun allModifiers(node: SoundNode): List<ModifierInstance> {
        val out = node.modifiers.toMutableList()
        var scope: GroupNode? = node.parentScope
        while (scope != null) {
            scope.modifiers?.let { out.addAll(it) }
            scope = scope.parent
        }
        return out
    }

    @Test
    fun `plain sound is matched`() {
        val group = Parser.parse("gay", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        assertEquals(listOf("gay"), sounds.map { it.key })
    }

    @Test
    fun `longest key wins over prefix`() {
        val lk = lookup("standing", "standing here")
        val triggers = TriggerMatcher.parseSoundTriggers("standing here", lk)
        assertEquals(listOf("standing here"), triggers.map { it.key })
    }

    @Test
    fun `modern modifier with args attaches to sound`() {
        val group = Parser.parse("gay:echo(0.2,0.9)", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        assertEquals(1, sounds.size)
        val echo = sounds[0].modifiers.single { it.def.key == "echo" }
        val value = echo.value as DoubleArray
        assertEquals(0.2, value[0]); assertEquals(0.9, value[1])
    }

    @Test
    fun `legacy pitch syntax rewrites and scales`() {
        val group = Parser.parse("standing here%50", lookup("standing here"))
        val sounds = flattenSoundNodes(group)
        assertEquals(1, sounds.size)
        val pitch = sounds[0].modifiers.single { it.def.key == "legacy_pitch" && it.isLegacy }
        assertEquals(0.5, pitch.value) // LegacyParseArgs: 50 / 100
    }

    @Test
    fun `double-percent becomes legacy_legacy_pitch with dot-split lerp args`() {
        val group = Parser.parse("gay%%50.80", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        val inst = sounds[0].modifiers.single { it.def.key == "legacy_legacy_pitch" }
        val v = inst.value as DoubleArray
        assertEquals(50.0, v[0]); assertEquals(80.0, v[1])
    }

    @Test
    fun `scope modifiers inherit to every sound inside`() {
        val group = Parser.parse("(gay gay):pitch(2)", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        assertEquals(2, sounds.size)
        for (sound in sounds) {
            val pitch = allModifiers(sound).single { it.def.key == "pitch" }
            assertEquals(2.0, pitch.value)
        }
    }

    @Test
    fun `modifier argument scope does not play its sounds`() {
        // "gay" also appears inside the argument scope; it must not become a playable sound.
        val group = Parser.parse("gay:realm(gay)", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        assertEquals(1, sounds.size)
    }

    @Test
    fun `yelling wraps message in volume`() {
        val group = Parser.parse("gay!!", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        assertEquals(1, sounds.size)
        val volume = allModifiers(sounds[0]).single { it.def.key == "volume" }
        assertEquals(2.0, volume.value)
    }

    @Test
    fun `expression modifier compiles`() {
        val group = Parser.parse("gay:pitch([sin(t)*2])", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        val pitch = sounds[0].modifiers.single { it.def.key == "pitch" }
        assertTrue(pitch.exprFn != null, "expression should compile")
        val v = pitch.exprFn!!.eval()
        assertTrue(v != null && v.size == 1 && v[0] in -2.0..2.0)
    }

    @Test
    fun `star repeat duplicates at flatten via rep modifier`() {
        val group = Parser.parse("gay*3", lookup("gay"))
        val sounds = flattenSoundNodes(group)
        val rep = sounds[0].modifiers.single { it.def.key == "legacy_rep" }
        assertEquals(3.0, rep.value)
    }

    @Test
    fun `unmatched text yields no sounds`() {
        val group = Parser.parse("hello world nothing here", lookup("gay"))
        assertEquals(0, flattenSoundNodes(group).size)
    }

    @Test
    fun `quotes are stripped before matching`() {
        val group = Parser.parse("\"gay\"", lookup("gay"))
        assertEquals(1, flattenSoundNodes(group).size)
    }
}
