package gg.earu.chatsounds.parser

import gg.earu.chatsounds.data.SoundLookup

/**
 * Flat sound-key matching (port of parser.lua's get_word_spans / find_sound_key_matches /
 * ParseSoundTriggers). Matching every substring is quadratic on long messages; instead the
 * text is split into word spans ONCE and chunks are grown incrementally from the CLEANED
 * words (quote chars stripped, like the sound keys are matched). A cleaned chunk longer
 * than the longest key in the lookup can never match, which bounds the work per word.
 * Longest match wins.
 */
object TriggerMatcher {
    private val SPACE_CHARS = charArrayOf(' ', '\t', '\n', '\r')
    private val IGNORED_CHARS = Regex("[\"']")

    /** [start, endExclusive) into the original string; [cleaned] has quote chars stripped. */
    class WordSpan(val start: Int, val endExclusive: Int, val cleaned: String)

    class KeyMatch(val key: String, val endWord: Int)

    class SoundTrigger(val key: String, val startIndex: Int, val endIndex: Int)

    private fun isSpace(c: Char) = c in SPACE_CHARS

    fun wordSpans(str: String): List<WordSpan> {
        val spans = ArrayList<WordSpan>()
        var wordStart = -1
        for (i in str.indices) {
            if (isSpace(str[i])) {
                if (wordStart >= 0) {
                    spans.add(WordSpan(wordStart, i, str.substring(wordStart, i).replace(IGNORED_CHARS, "")))
                    wordStart = -1
                }
            } else if (wordStart < 0) {
                wordStart = i
            }
        }
        if (wordStart >= 0) {
            spans.add(WordSpan(wordStart, str.length, str.substring(wordStart).replace(IGNORED_CHARS, "")))
        }
        return spans
    }

    /**
     * Every sound key matching a chunk starting at [startWord], shortest to longest, with the
     * word index each match ends at.
     */
    fun findKeyMatches(str: String, spans: List<WordSpan>, startWord: Int, lookup: SoundLookup): List<KeyMatch> {
        val matches = ArrayList<KeyMatch>()
        val maxKeyLength = lookup.maxKeyLength

        var cleanedChunk = spans[startWord].cleaned
        if (cleanedChunk.isEmpty()) return matches // quote chars only; matches start from the next word

        if (cleanedChunk.length <= maxKeyLength && lookup.list.containsKey(cleanedChunk)) {
            matches.add(KeyMatch(cleanedChunk, startWord))
        }

        for (j in startWord + 1 until spans.size) {
            val prev = spans[j - 1]
            val span = spans[j]
            cleanedChunk = cleanedChunk + str.substring(prev.endExclusive, span.start) + span.cleaned
            if (cleanedChunk.length > maxKeyLength) break // no sound key is that long

            // quote-only words leave trailing spaces behind once cleaned
            val chunk = cleanedChunk.trim()
            if (lookup.list.containsKey(chunk)) {
                matches.add(KeyMatch(chunk, j))
            }
        }
        return matches
    }

    /** Expects lowercased input (the caller lowercases whole messages, GMod-parser style). */
    fun parseSoundTriggers(rawStr: String, lookup: SoundLookup): List<SoundTrigger> {
        val str = rawStr.trim()
        if (str.isEmpty()) return emptyList()

        if (lookup.list.containsKey(str)) {
            return listOf(SoundTrigger(str, 0, str.length))
        }

        val sounds = ArrayList<SoundTrigger>()
        val spans = wordSpans(str)
        var wordIndex = 0
        while (wordIndex < spans.size) {
            val matches = findKeyMatches(str, spans, wordIndex, lookup)
            val best = matches.lastOrNull() // longest match wins
            if (best != null) {
                val startChar = spans[wordIndex].start
                sounds.add(SoundTrigger(best.key, startChar, startChar + best.key.length))
                wordIndex = best.endWord + 1
            } else {
                wordIndex++
            }
        }
        return sounds
    }
}
