package gg.earu.chatsounds.client

import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.data.TrieNode
import gg.earu.chatsounds.modifiers.Modifiers
import gg.earu.chatsounds.parser.TriggerMatcher
import java.util.Locale

/**
 * Chat autocomplete state machine (port of completion.lua). Pure logic: the loader module
 * renders [suggestions] under the chat input and calls [cycle] on Tab.
 */
object CompletionEngine {
    class Suggestion(val text: String, val extra: String? = null)

    @Volatile var suggestions: List<Suggestion> = emptyList()
        private set
    @Volatile var index: Int = -1
        private set

    private val MODIFIER_PATTERN = Regex(":([a-zA-Z0-9_]+)[\\[\\]()a-zA-Z0-9\\s,.]*$")
    private val MODIFIER_ARGS_PATTERN = Regex(":[a-zA-Z0-9_]+\\(([\\[\\]a-zA-Z0-9\\s,.]*)$")
    private val SELECT_INDEX_PATTERN = Regex("#(\\d+)$")
    private val SELECT_NO_ARGS_PATTERN = Regex("#$")

    fun clear() {
        suggestions = emptyList()
        index = -1
    }

    /** Tab: returns the replacement text, cycling (Shift/Ctrl reverses). */
    fun cycle(reverse: Boolean): String? {
        val current = suggestions
        if (current.isEmpty()) return null
        index = (index + if (reverse) -1 else 1).mod(current.size)
        return current[index].text
    }

    fun onTextChanged(rawText: String) {
        build(rawText)
    }

    fun build(rawInput: String) {
        val cleaned = rawInput.replace(Regex("[\\s\n\r\t]+"), " ").replace(Regex("[\"']"), "").trim()
        if (cleaned.isEmpty()) {
            clear()
            return
        }

        val out = ArrayList<Suggestion>()
        val added = HashSet<String>()

        val words = cleaned.split(" ")
        val lastWordRaw = words.last()
        val isUpperCase = lastWordRaw.any { it.isLetter() } && lastWordRaw.uppercase(Locale.ROOT) == lastWordRaw

        val text = cleaned.lowercase(Locale.ROOT)
        val lastWord = lastWordRaw.lowercase(Locale.ROOT)

        // select's OnCompletion (the "#n" variant browser) takes priority.
        if (selectCompletion(text, out, added, isUpperCase)) {
            suggestions = out
            index = -1
            return
        }

        if (processModifierCompletion(text, out, added, isUpperCase)) {
            suggestions = out
            index = -1
            return
        }

        val trie = DataLoader.trie
        if (trie != null && lastWord.isNotEmpty()) {
            var sounds: List<String> = emptyList()
            val root = trie.roots[lastWord[0].toString()]
            if (root != null) {
                if (root.depth > 1) {
                    var node: TrieNode = root
                    for (i in 1 until lastWord.length) {
                        val next = node.keys[lastWord[i].toString()] ?: break
                        node = next
                    }
                    sounds = node.sounds
                    for (child in node.keys.values) {
                        addNestedSuggestions(child, text, out, added, isUpperCase)
                    }
                } else {
                    sounds = root.sounds
                }
            }

            for (soundKey in sounds) {
                if (soundKey.contains(text) && added.add(soundKey)) {
                    out.add(Suggestion(if (isUpperCase) soundKey.uppercase(Locale.ROOT) else soundKey))
                }
            }
        }

        out.sortWith(compareBy({ it.text.length }, { it.text }))
        suggestions = out
        index = -1
    }

    private fun addNestedSuggestions(node: TrieNode, text: String, out: MutableList<Suggestion>, added: MutableSet<String>, isUpperCase: Boolean) {
        for (soundKey in node.sounds) {
            if (soundKey.contains(text) && added.add(soundKey)) {
                out.add(Suggestion(if (isUpperCase) soundKey.uppercase(Locale.ROOT) else soundKey))
            }
        }
        for (child in node.keys.values) {
            addNestedSuggestions(child, text, out, added, isUpperCase)
        }
    }

    /** ":pit" -> modifier names; ":echo(0.2," -> argument type hints (completion.lua port). */
    private fun processModifierCompletion(text: String, out: MutableList<Suggestion>, added: MutableSet<String>, isUpperCase: Boolean): Boolean {
        val modifierMatch = MODIFIER_PATTERN.find(text) ?: return false
        val modifier = modifierMatch.groupValues[1]
        val arguments = MODIFIER_ARGS_PATTERN.find(text)?.groupValues?.get(1)

        val withoutModifier = text.replace(MODIFIER_PATTERN, "").trim()
        if (withoutModifier.isEmpty()) return false

        if (arguments == null) {
            for (name in Modifiers.lookup.keys) {
                if (name.startsWith(modifier) && added.add(name)) {
                    val suggestion = "$withoutModifier:$name"
                    out.add(Suggestion(if (isUpperCase) suggestion.uppercase(Locale.ROOT) else suggestion))
                }
            }
        } else {
            val mod = Modifiers.lookup[modifier] ?: return true
            var suggestArguments = arguments
            val splitArgs = arguments.split(",")

            val default = mod.defaultValue
            if (default is DoubleArray) {
                var currentAmount = 0
                var appendComma = true
                for (arg in splitArgs) {
                    val isEmpty = arg.trim().isEmpty()
                    appendComma = !isEmpty && appendComma
                    if (!isEmpty) currentAmount++
                }
                if (default.size != currentAmount) {
                    val types = ArrayList<String>()
                    for (i in default.indices) {
                        if (i < currentAmount) continue
                        val comma = appendComma && i == currentAmount
                        types.add((if (comma) ", " else "") + "[number]")
                    }
                    suggestArguments += types.joinToString(", ")
                }
            } else if (splitArgs[0].trim().isEmpty()) {
                val typeName = if (default is String) "string" else "number"
                suggestArguments = "$suggestArguments[$typeName]"
            }

            val suggestion = "$withoutModifier:$modifier($suggestArguments)"
            out.add(Suggestion(if (isUpperCase) suggestion.uppercase(Locale.ROOT) else suggestion))
        }
        return true
    }

    /** select.lua OnCompletion: "key#" or "key#n" cycles through the key's variants. */
    private fun selectCompletion(text: String, out: MutableList<Suggestion>, added: MutableSet<String>, isUpperCase: Boolean): Boolean {
        val match = SELECT_INDEX_PATTERN.find(text) ?: SELECT_NO_ARGS_PATTERN.find(text) ?: return false
        val requested = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1

        val stripped = text.replace(SELECT_INDEX_PATTERN, "").replace(SELECT_NO_ARGS_PATTERN, "")
        val lookup = DataLoader.lookup
        val triggers = TriggerMatcher.parseSoundTriggers(stripped, lookup)
        if (triggers.isEmpty()) return false

        val last = triggers.last()
        val variants = lookup.list[last.key].orEmpty()
        if (variants.isEmpty()) return false

        val index = minOf(requested, variants.size)
        for (i in index..variants.size + index) {
            val relative = maxOf(1, i % (variants.size + 1))
            val variant = variants[relative - 1]
            if (added.add(variant.url)) {
                val suggestion = stripped.substring(0, last.startIndex) + last.key + "#" + relative +
                    stripped.substring(minOf(stripped.length, last.endIndex + 1))
                out.add(
                    Suggestion(
                        if (isUpperCase) suggestion.uppercase(Locale.ROOT) else suggestion,
                        extra = ":realm( ${variant.realm} )",
                    )
                )
            }
        }
        return true
    }
}
