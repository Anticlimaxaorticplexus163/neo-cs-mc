package gg.earu.chatsounds.client

import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.modifiers.Modifiers
import gg.earu.chatsounds.parser.GroupNode
import gg.earu.chatsounds.parser.Parser
import gg.earu.chatsounds.parser.SoundNode
import java.util.Locale

/**
 * ShouldHideMessage port: long messages that are (almost) entirely sound triggers get
 * replaced with "Hidden chatsounds message from X". Divergence from GMod, on purpose: the
 * Lua version compared the NON-sound share against the threshold (and its index-pair walk
 * checked a Type that never matched), which effectively hid every long message; this
 * implements the documented intent — hide when >= 95% of the text IS sound triggers.
 */
object HideText {
    private const val CONTEXT_SEPARATOR = ";"
    private const val BIG_CS_THRESHOLD = 200
    private const val MIN_PERCENTAGE_FOR_HIDING = 95.0

    fun shouldHide(rawText: String): Boolean {
        if (!ClientConfig.data.enabled || !ClientConfig.data.hideText) return false
        if (DataLoader.loading != null) return false

        val gated = gg.earu.chatsounds.playback.ChatsoundsPlayer.effectiveText(rawText) ?: return false
        val text = gated.lowercase(Locale.ROOT)
        if (text.length < BIG_CS_THRESHOLD) return false

        val lookup = DataLoader.lookup
        if (lookup.list.containsKey(text.trim())) return true // trivially just one big sound

        var soundShareSum = 0.0
        val chunks = text.split(CONTEXT_SEPARATOR)
        for (chunk in chunks) {
            val originalLen = chunk.length
            if (originalLen == 0) continue

            // Remove legacy modifier syntax so indexes stay meaningful.
            var cleaned = chunk
            for ((syntax, _) in Modifiers.legacySyntaxes) {
                cleaned = cleaned.replace(Regex(Regex.escape(syntax) + "[0-9.]+"), "")
                    .replace(syntax, "")
            }

            val pairs = indexPairs(Parser.parse(cleaned, lookup))
            if (pairs.any { it.third >= BIG_CS_THRESHOLD }) return true // spotted a big sound

            var soundLen = 0
            for ((start, end, _) in pairs) {
                soundLen += (end - start + 1).coerceAtLeast(0)
            }
            // Scope/modifier punctuation counts toward the sound share too.
            val punct = cleaned.count { it == '(' || it == ')' || it == ':' }
            soundShareSum += ((soundLen + punct).toDouble() / originalLen * 100).coerceAtMost(100.0)
        }

        return soundShareSum / chunks.size >= MIN_PERCENTAGE_FOR_HIDING
    }

    /** (startIndex, endIndex incl. trailing modifiers, keyLength) per sound, 1-based. */
    private fun indexPairs(group: GroupNode, out: MutableList<Triple<Int, Int, Int>> = ArrayList()): List<Triple<Int, Int, Int>> {
        for (child in group.children) {
            when {
                child is SoundNode -> {
                    var end = child.endIndex
                    if (child.modifiers.isNotEmpty()) {
                        val lastModifierEnd = child.modifiers.maxOf { it.endIndex }
                        if (lastModifierEnd > end) end = lastModifierEnd
                    }
                    out.add(Triple(child.startIndex, end, child.key.length))
                }
                child is GroupNode && !child.isModifierExpression -> indexPairs(child, out)
            }
        }
        return out
    }
}
