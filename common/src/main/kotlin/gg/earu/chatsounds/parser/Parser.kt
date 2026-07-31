package gg.earu.chatsounds.parser

import gg.earu.chatsounds.data.SoundLookup
import gg.earu.chatsounds.modifiers.Modifier
import gg.earu.chatsounds.modifiers.ModifierInstance
import gg.earu.chatsounds.modifiers.Modifiers
import gg.earu.chatsounds.parser.expr.ExprFn
import gg.earu.chatsounds.parser.expr.Expressions
import java.util.Locale

/**
 * AST nodes. Indexes are 1-BASED (Lua parity) — modifier/sound attachment and the hide-text
 * heuristic rely on exact GMod index comparisons, so the whole parser speaks Lua indexing
 * and only converts at char access.
 */
sealed class ParseNode {
    var startIndex: Int = -1
    var endIndex: Int = -1
}

class GroupNode(val parent: GroupNode?, val root: Boolean = false) : ParseNode() {
    val children: MutableList<ParseNode> = ArrayList()
    var modifiers: MutableList<ModifierInstance>? = null
    var expressionFn: ExprFn? = null
    /** Scopes consumed as a modifier's argument are excluded from playback. */
    var isModifierExpression: Boolean = false

    // Transient during parsing; merged into children by processScopeChildren.
    internal var sounds: MutableList<SoundNode>? = null
}

class SoundNode(val key: String, val parentScope: GroupNode) : ParseNode() {
    val modifiers: MutableList<ModifierInstance> = ArrayList()
}

/**
 * Full port of parser.lua's parse_str: a single-pass RIGHT-TO-LEFT scanner. `(...)` scopes,
 * `:name(args)` modifiers, `[expr]` compiled expressions, legacy syntaxes rewritten to
 * modern form up front.
 */
object Parser {
    private val IGNORED_CHARS = Regex("[\"']")
    private val YELLING_PATTERN = Regex("(![?!1]*)$")
    private val SPACE_SPLIT = Regex("[\t\n\r ]+")
    /** Lua's `[a-z]+` (no underscore) — the `%%`-inside-`[]` restore miss is preserved on purpose. */
    private val LEGACY_IN_EXPR = Regex(":legacy_([a-z]+)\\((.+)\\)")

    fun parse(rawInput: String, lookup: SoundLookup): GroupNode = parseStr(rawInput.lowercase(Locale.ROOT), lookup)

    // Lua-index helpers: luaSub is inclusive 1-based on both ends.
    private fun luaSub(s: String, i: Int, j: Int): String =
        if (i > j || i > s.length) "" else s.substring(i - 1, minOf(j, s.length))

    private class Ctx(val scopes: ArrayDeque<GroupNode>, val lookup: SoundLookup) {
        val modifiers: MutableList<ModifierInstance> = ArrayList()
        var currentStr: String = ""
        var inLuaExpression = false
        var luaStringEndIndex = -1
        var lastParsedSoundEndIndex: Int? = null
    }

    private fun parseStr(rawInput: String, lookup: SoundLookup): GroupNode {
        var raw = rawInput.replace(IGNORED_CHARS, "")

        val global = GroupNode(parent = null, root = true)
        global.startIndex = 1
        global.endIndex = raw.length
        if (raw.trim().isEmpty()) return global

        // Convert legacy modifiers into modern ones, longest syntax first.
        for ((syntax, targetName) in Modifiers.legacySyntaxes) {
            raw = raw.replace(Regex(Regex.escape(syntax) + "([0-9.]+)")) { ":$targetName(${it.groupValues[1]})" }
        }

        // PreParse (volume.lua's yelling hook): trailing "!!!" wraps the text in :volume(n).
        YELLING_PATTERN.find(raw)?.let { match ->
            if (match.value.isNotEmpty()) {
                val volume = maxOf(match.value.length, 1)
                raw = "(${raw.replace(YELLING_PATTERN, "")}):volume($volume)"
            }
        }

        val ctx = Ctx(ArrayDeque(listOf(global)), lookup)

        for (index in raw.length downTo 1) {
            when (val char = raw[index - 1]) {
                ')' -> handleScopeOpen(raw, index, ctx)
                '(' -> handleScopeClose(raw, index, ctx)
                ':' -> handleModifier(raw, index, ctx)
                ']' -> {
                    ctx.inLuaExpression = true
                    ctx.luaStringEndIndex = index - 1
                }
                '[' -> handleExpression(raw, index, ctx)
                else -> ctx.currentStr = char + ctx.currentStr
            }
        }

        parseSounds(raw, 1, ctx)
        processScopeChildren(global)
        return global
    }

    /** ')' seen while scanning backwards = a scope BEGINS here. */
    private fun handleScopeOpen(raw: String, index: Int, ctx: Ctx) {
        if (ctx.inLuaExpression) return

        parseSounds(raw, index, ctx)

        val parentScope = ctx.scopes.last()
        val newScope = GroupNode(parent = parentScope)
        newScope.endIndex = index

        if (ctx.modifiers.isNotEmpty()) {
            // Assigned to the scope; flattened into the modifier if this scope becomes one.
            newScope.modifiers = ctx.modifiers.toMutableList()
            ctx.modifiers.clear()
        }

        parentScope.children.add(0, newScope)
        ctx.scopes.addLast(newScope)
    }

    private fun handleScopeClose(raw: String, index: Int, ctx: Ctx) {
        if (ctx.inLuaExpression) return
        if (ctx.scopes.last().root) return

        parseSounds(raw, index, ctx)

        val scope = ctx.scopes.removeLast()
        scope.startIndex = index
        processScopeChildren(scope)
    }

    private fun handleModifier(raw: String, index: Int, ctx: Ctx) {
        if (ctx.inLuaExpression) return

        val modifierName = ctx.currentStr.trim().split(SPACE_SPLIT).firstOrNull() ?: ""
        val def: Modifier? = Modifiers.lookup[modifierName]
        val curScope = ctx.scopes.last()
        var endIndex = index + modifierName.length
        var modifier: ModifierInstance? = null

        if (curScope.children.isNotEmpty()) {
            val lastScopeChild = curScope.children[0]
            if (def != null && lastScopeChild is GroupNode) {
                val alreadyAssigned = lastScopeChild.isModifierExpression
                if (!alreadyAssigned) {
                    lastScopeChild.isModifierExpression = true
                    lastScopeChild.modifiers?.let { previous ->
                        ctx.modifiers.addAll(previous)
                        lastScopeChild.modifiers = null
                    }
                    // Don't play the potential sounds inside the modifier's argument scope.
                    lastScopeChild.sounds = ArrayList()
                }

                val value: Any? = if (alreadyAssigned) {
                    def.defaultValue
                } else {
                    def.parseArgs(luaSub(raw, lastScopeChild.startIndex + 1, lastScopeChild.endIndex - 1))
                }

                modifier = ModifierInstance(
                    def = def,
                    value = value,
                    exprFn = lastScopeChild.expressionFn,
                    isLegacy = modifierName.startsWith("legacy_"),
                    startIndex = index,
                    endIndex = if (alreadyAssigned) index + modifierName.length else lastScopeChild.endIndex,
                )
                endIndex = modifier.endIndex
            }
        } else if (def != null) {
            modifier = ModifierInstance(
                def = def,
                value = def.defaultValue,
                exprFn = null,
                isLegacy = modifierName.startsWith("legacy_"),
                startIndex = index,
                endIndex = index + modifierName.length,
            )
            endIndex = modifier.endIndex
        }

        if (modifier != null) {
            ctx.modifiers.add(0, modifier)
        }

        // Lua quirk preserved: the "resume after the modifier name" find uses plain-text
        // matching of the space PATTERN, which never matches — so the whole current string
        // is always consumed ("gay:echo (gay porno)" does not work in GMod either).
        ctx.currentStr = ""

        parseSounds(raw, endIndex + 1, ctx)
    }

    private fun handleExpression(raw: String, index: Int, ctx: Ctx) {
        ctx.inLuaExpression = false

        var luaStr = luaSub(raw, index + 1, ctx.luaStringEndIndex)

        // Restore legacy syntax that the rewrite pass converted inside the expression.
        luaStr = luaStr.replace(LEGACY_IN_EXPR) { match ->
            val legacy = Modifiers.lookup["legacy_${match.groupValues[1]}"]
            val syntax = (legacy as? gg.earu.chatsounds.modifiers.LegacyModifier)?.base?.legacySyntax
            if (syntax != null) syntax + match.groupValues[2] else ""
        }

        ctx.scopes.last().expressionFn = Expressions.compile(luaStr)
    }

    /** Flushes the accumulated text into sound nodes on the current scope. */
    private fun parseSounds(raw: String, index: Int, ctx: Ctx) {
        val lookup = ctx.lookup
        if (ctx.currentStr.isEmpty()) return

        val curScope = ctx.scopes.last()
        val sounds = curScope.sounds ?: ArrayList<SoundNode>().also { curScope.sounds = it }

        if (lookup.list.containsKey(ctx.currentStr)) {
            val sound = SoundNode(ctx.currentStr, curScope)
            sound.startIndex = index
            sound.endIndex = index + ctx.currentStr.length
            sounds.add(sound)
        } else {
            val currentStr = ctx.currentStr
            val spans = TriggerMatcher.wordSpans(currentStr)
            var wordIndex = 0
            while (wordIndex < spans.size) {
                val matches = TriggerMatcher.findKeyMatches(currentStr, spans, wordIndex, lookup)
                var matched = false
                // Longest match first, falling back on shorter ones not found in the raw string.
                for (i in matches.indices.reversed()) {
                    val match = matches[i]
                    val from0 = (ctx.lastParsedSoundEndIndex ?: index) - 1
                    val at0 = raw.indexOf(match.key, startIndex = maxOf(0, from0))
                    if (at0 >= 0) {
                        val chunkStart = at0 + 1
                        val chunkEnd = at0 + match.key.length
                        ctx.lastParsedSoundEndIndex = chunkEnd

                        val sound = SoundNode(match.key, curScope)
                        sound.startIndex = chunkStart
                        sound.endIndex = chunkEnd
                        sounds.add(sound)

                        wordIndex = match.endWord + 1
                        matched = true
                        break
                    }
                }
                if (!matched) wordIndex++
            }
        }

        // Attach pending modifiers to the last parsed sound (right-to-left accumulation).
        val lastSound = sounds.lastOrNull()
        if (lastSound != null) {
            for (i in ctx.modifiers.indices.reversed()) {
                val modifier = ctx.modifiers[i]
                if (modifier.startIndex < lastSound.endIndex) break
                lastSound.modifiers.add(modifier)
                ctx.modifiers.removeAt(i)
            }
        }

        ctx.currentStr = ""
        ctx.lastParsedSoundEndIndex = null
    }

    private fun processScopeChildren(scope: GroupNode) {
        val sounds = scope.sounds ?: return
        scope.children.addAll(sounds)
        scope.children.sortBy { it.startIndex }
        scope.sounds = null
    }
}
